package com.juan.habitos.data.sync

import com.juan.habitos.data.device.CollectedUsageEvent
import com.juan.habitos.data.device.F12CaptureAssessment
import com.juan.habitos.data.device.F12EvidenceDelivery
import com.juan.habitos.data.device.F12EvidenceDeliveryState
import com.juan.habitos.data.device.F12EvidenceReceipt
import com.juan.habitos.data.device.F12EvidenceState
import com.juan.habitos.data.device.F12RawEvidenceCaptureCoordinator
import com.juan.habitos.data.device.F12RawEvidenceContract
import com.juan.habitos.data.device.F12RawEvidenceRecord
import com.juan.habitos.data.device.UsageCollectorRun
import com.juan.habitos.data.device.UsageCollectorRunFactory
import com.juan.habitos.data.device.UsageEventRunCollector
import com.juan.habitos.data.local.F12EvidenceSaveResult
import com.juan.habitos.data.local.F12EvidenceSaveStatus
import com.juan.habitos.data.local.F12RawEvidenceStore
import com.juan.habitos.data.remote.F12EvidenceDeliverer
import com.juan.habitos.data.remote.F12EvidenceDeliveryResult
import com.juan.habitos.data.remote.F12EvidenceTransportCoordinator
import com.juan.habitos.domain.model.DeviceCredentials
import com.juan.habitos.domain.model.DeviceIdentity
import com.juan.habitos.domain.model.DeviceMode
import com.juan.habitos.domain.model.DeviceSession
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.repository.DeviceIdentityLocalStore
import com.juan.habitos.domain.repository.DeviceSessionRepository
import com.juan.habitos.domain.repository.ProfileLocalStore
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class F12RawEvidenceWorkTest {
    private val now = Instant.parse("2026-11-24T04:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val zone = ZoneId.of("America/Argentina/Buenos_Aires")

    @Test fun `closed-day plan changes only after local midnight and is oldest first`() {
        val beforeMidnight = f12ClosedDays(Instant.parse("2026-11-24T02:59:59Z"), zone, 3)
        val afterMidnight = f12ClosedDays(Instant.parse("2026-11-24T03:00:00Z"), zone, 3)

        assertEquals(
            listOf(LocalDate.parse("2026-11-20"), LocalDate.parse("2026-11-21"), LocalDate.parse("2026-11-22")),
            beforeMidnight,
        )
        assertEquals(
            listOf(LocalDate.parse("2026-11-21"), LocalDate.parse("2026-11-22"), LocalDate.parse("2026-11-23")),
            afterMidnight,
        )
    }

    @Test fun `first run captures bounded pending days oldest first`() = runTest {
        val fixture = Fixture(recoveryDays = 3)

        val outcome = fixture.automation().synchronize()

        assertEquals(F12RawEvidenceAutomationStatus.COMPLETED, outcome.status)
        assertEquals(listOf("2026-11-21", "2026-11-22", "2026-11-23"), outcome.capturedDates.map(LocalDate::toString))
        assertEquals(listOf("2026-11-21", "2026-11-22", "2026-11-23"), fixture.store.records.map { it.localDate })
        assertEquals(6, fixture.collector.requests.size)
        assertTrue(fixture.store.records.all { it.timezoneId == zone.id })
        assertTrue(fixture.store.records.all { "DAY_COMPLETENESS_NOT_ASSERTED" in it.quality.qualityFlags })
    }

    @Test fun `application restart reuses stored captures and does not duplicate HTTP`() = runTest {
        val fixture = Fixture(recoveryDays = 2)
        val first = fixture.automation().synchronize()
        val requestsAfterFirstRun = fixture.collector.requests.toList()

        val second = fixture.automation().synchronize()

        assertEquals(F12RawEvidenceAutomationStatus.COMPLETED, first.status)
        assertEquals(F12RawEvidenceAutomationStatus.COMPLETED, second.status)
        assertEquals(listOf("2026-11-22", "2026-11-23"), second.reusedDates.map(LocalDate::toString))
        assertEquals(requestsAfterFirstRun, fixture.collector.requests)
        assertEquals(2, fixture.deliverer.sent.size)
        assertEquals(2, fixture.store.records.size)
    }

    @Test fun `transient retry sends the exact stored evidence and same capture id`() = runTest {
        val fixture = Fixture(recoveryDays = 1)
        fixture.deliverer.results += F12EvidenceDeliveryResult.Retryable(503, "temporary")

        val first = fixture.automation().synchronize()
        val storedAfterFailure = fixture.store.records.single()
        val originalSummary = storedAfterFailure.exportPairs.map { it.summaryBytes.copyOf() }
        val originalEvents = storedAfterFailure.exportPairs.map { it.eventsBytes.copyOf() }
        val second = fixture.automation().synchronize()

        assertEquals(F12RawEvidenceAutomationStatus.RETRYABLE_FAILURE, first.status)
        assertEquals(F12RawEvidenceAutomationStatus.COMPLETED, second.status)
        assertEquals(2, fixture.deliverer.sent.size)
        assertEquals(listOf(storedAfterFailure.captureId, storedAfterFailure.captureId), fixture.deliverer.sent.map { it.captureId })
        fixture.deliverer.sent.last().exportPairs.forEachIndexed { index, pair ->
            assertTrue(originalSummary[index].contentEquals(pair.summaryBytes))
            assertTrue(originalEvents[index].contentEquals(pair.eventsBytes))
        }
        assertEquals(2, fixture.store.records.single().delivery.attemptCount)
    }

    @Test fun `missing usage permission is explicit failed evidence and never measured zero`() = runTest {
        val fixture = Fixture(recoveryDays = 1)
        fixture.collector.permissionAvailable = false

        val outcome = fixture.automation().synchronize()
        val record = fixture.store.records.single()
        val summaries = record.exportPairs.map { it.summaryBytes.toString(Charsets.UTF_8) }

        assertEquals(F12RawEvidenceAutomationStatus.COMPLETED, outcome.status)
        assertEquals(F12EvidenceState.AVAILABLE, record.evidenceState)
        assertEquals(F12CaptureAssessment.CAPTURE_FAILED, record.quality.assessment)
        assertFalse(record.quality.dayQuerySucceeded)
        assertTrue("DAY_QUERY_FAILED" in record.quality.qualityFlags)
        assertTrue(summaries.all { "USAGE_ACCESS_NOT_GRANTED" in it })
    }

    @Test fun `unexportable capture stores UNAVAILABLE rather than an empty measured day`() = runTest {
        val fixture = Fixture(recoveryDays = 1)
        fixture.collector.failure = IllegalStateException("collector unavailable")

        val outcome = fixture.automation().synchronize()
        val record = fixture.store.records.single()

        assertEquals(F12RawEvidenceAutomationStatus.COMPLETED, outcome.status)
        assertEquals(listOf(LocalDate.parse("2026-11-23")), outcome.unavailableDates)
        assertEquals(F12EvidenceState.UNAVAILABLE, record.evidenceState)
        assertTrue(record.exportPairs.isEmpty())
        assertEquals(F12CaptureAssessment.CAPTURE_FAILED, record.quality.assessment)
    }

    @Test fun `stored retry outside query recovery window is still delivered`() = runTest {
        val fixture = Fixture(recoveryDays = 1)
        val old = F12RawEvidenceContract.unavailableRecord(
            PROFILE_ID,
            DEVICE_ID,
            "2026-10-01",
            zone.id,
            1,
            Instant.parse("2026-10-02T04:00:00Z"),
        ).copy(delivery = F12EvidenceDelivery(state = F12EvidenceDeliveryState.RETRY_PENDING))
        fixture.store.save(old)

        val outcome = fixture.automation().synchronize()

        assertEquals(F12RawEvidenceAutomationStatus.COMPLETED, outcome.status)
        assertTrue(old.captureId in outcome.deliveredCaptureIds)
        assertEquals(listOf(old.captureId, fixture.store.records.first { it.localDate == "2026-11-23" }.captureId), fixture.deliverer.sent.map { it.captureId })
    }

    @Test fun `forbidden response pauses durable delivery until monitoring is authorized again`() = runTest {
        val fixture = Fixture(recoveryDays = 1)
        fixture.deliverer.results += F12EvidenceDeliveryResult.Forbidden

        val first = fixture.automation().synchronize()
        val restarted = fixture.automation()

        assertEquals(F12RawEvidenceAutomationStatus.AUTHORIZATION_PAUSED, first.status)
        assertEquals(F12EvidenceDeliveryState.AUTHENTICATION_BLOCKED, fixture.store.records.single().delivery.state)
        assertTrue(restarted.isAuthorizationPaused(PROFILE_ID, DEVICE_ID))

        restarted.resumeAfterMonitoringAuthorization(PROFILE_ID, DEVICE_ID)
        val resumed = restarted.synchronize()

        assertEquals(F12RawEvidenceAutomationStatus.COMPLETED, resumed.status)
        assertEquals(F12EvidenceDeliveryState.DELIVERED, fixture.store.records.single().delivery.state)
        assertEquals(2, fixture.deliverer.sent.size)
    }

    @Test fun `work is cancelled for inactive unlinked or authorization-paused profile`() = runTest {
        val profileStore = TestProfileStore()
        val identityStore = TestIdentityStore()
        val sessionStore = TestSessionStore()
        val scheduler = RecordingScheduler()
        val automation = RecordingAutomation()
        val controller = F12RawEvidenceWorkController(true, sessionStore, profileStore, identityStore, scheduler, automation)

        controller.reconcile()
        assertEquals(1, scheduler.scheduled)

        profileStore.profile.value = profileStore.profile.value!!.copy(active = false)
        controller.reconcile()
        identityStore.identity.value = identityStore.identity.value.copy(profileId = "another-profile")
        profileStore.profile.value = profileStore.profile.value!!.copy(active = true)
        controller.reconcile()
        identityStore.identity.value = identityStore.identity.value.copy(profileId = PROFILE_ID)
        automation.paused = true
        controller.reconcile()

        assertEquals(3, scheduler.cancelled)
        assertEquals(1, scheduler.scheduled)
    }

    private inner class Fixture(recoveryDays: Int) {
        val profileStore = TestProfileStore()
        val identityStore = TestIdentityStore()
        val store = MemoryEvidenceStore()
        val collector = RecordingCollector(clock)
        val deliverer = RecordingDeliverer()
        private val capture = F12RawEvidenceCaptureCoordinator(
            true,
            profileStore,
            identityStore,
            collector,
            store,
            clock,
        )
        private val transport = F12EvidenceTransportCoordinator(true, store, deliverer, clock = clock)
        private val recoveryWindowDays = recoveryDays

        fun automation() = F12RawEvidenceAutomationCoordinator(
            true,
            profileStore,
            identityStore,
            store,
            capture,
            transport,
            clock,
            { zone },
            recoveryWindowDays,
        )
    }

    private class RecordingCollector(private val clock: Clock) : UsageEventRunCollector {
        val requests = mutableListOf<Pair<Long, Long>>()
        var permissionAvailable = true
        var failure: RuntimeException? = null

        override fun collectRun(requestedStartEpochMillis: Long, requestedEndEpochMillis: Long): UsageCollectorRun {
            failure?.let { throw it }
            requests += requestedStartEpochMillis to requestedEndEpochMillis
            val runId = UUID.randomUUID().toString()
            return if (permissionAvailable) {
                UsageCollectorRunFactory.success(
                    collectorRunId = runId,
                    requestedStartEpochMillis = requestedStartEpochMillis,
                    requestedEndEpochMillis = requestedEndEpochMillis,
                    collectedAtEpochMillis = clock.millis(),
                    androidVersion = "Android test",
                    deviceModel = "TEST-DEVICE",
                    eventsInQueryOrder = emptyList<CollectedUsageEvent>(),
                    androidApiLevel = 35,
                    manufacturer = "TEST",
                )
            } else {
                UsageCollectorRunFactory.permissionUnavailable(
                    collectorRunId = runId,
                    requestedStartEpochMillis = requestedStartEpochMillis,
                    requestedEndEpochMillis = requestedEndEpochMillis,
                    collectedAtEpochMillis = clock.millis(),
                    androidVersion = "Android test",
                    deviceModel = "TEST-DEVICE",
                    androidApiLevel = 35,
                    manufacturer = "TEST",
                )
            }
        }
    }

    private class RecordingDeliverer : F12EvidenceDeliverer {
        val sent = mutableListOf<F12RawEvidenceRecord>()
        val results = ArrayDeque<F12EvidenceDeliveryResult>()

        override suspend fun deliver(record: F12RawEvidenceRecord): F12EvidenceDeliveryResult {
            sent += record
            return results.removeFirstOrNull() ?: F12EvidenceDeliveryResult.Received(201, receipt(record))
        }

        private fun receipt(record: F12RawEvidenceRecord) = F12EvidenceReceipt(
            receiptId = UUID.randomUUID().toString(),
            captureId = record.captureId,
            status = "RECEIVED",
            evidenceState = record.evidenceState,
            localDate = record.localDate,
            revision = record.revision,
            contentSha256 = record.contentSha256,
            pairCount = record.exportPairs.size,
            totalBytes = record.exportPairs.sumOf { it.summaryBytes.size.toLong() + it.eventsBytes.size },
            receivedAt = "2026-11-24T04:01:00Z",
            expiresAt = null,
        )
    }

    private class MemoryEvidenceStore : F12RawEvidenceStore {
        val records = mutableListOf<F12RawEvidenceRecord>()

        override suspend fun save(record: F12RawEvidenceRecord): F12EvidenceSaveResult {
            val existing = records.firstOrNull {
                it.profileId == record.profileId && it.deviceId == record.deviceId &&
                    it.localDate == record.localDate && it.revision == record.revision
            }
            if (existing != null) return F12EvidenceSaveResult(F12EvidenceSaveStatus.ALREADY_PRESENT, existing)
            records += record
            records.sortWith(compareBy<F12RawEvidenceRecord> { it.localDate }.thenBy { it.revision })
            return F12EvidenceSaveResult(F12EvidenceSaveStatus.CREATED, record)
        }

        override suspend fun list(profileId: String) = records.filter { it.profileId == profileId }
        override suspend fun read(captureId: String) = records.firstOrNull { it.captureId == captureId }
        override suspend fun updateDelivery(
            captureId: String,
            transform: (F12EvidenceDelivery) -> F12EvidenceDelivery,
        ): F12RawEvidenceRecord {
            val index = records.indexOfFirst { it.captureId == captureId }
            require(index >= 0)
            return records[index].copy(delivery = transform(records[index].delivery)).also { records[index] = it }
        }
    }

    private class TestProfileStore : ProfileLocalStore {
        override val profile = MutableStateFlow<Profile?>(Profile("Child", "fox", 9, id = PROFILE_ID))
        override val onboardingComplete = MutableStateFlow(true)
        override suspend fun save(profile: Profile) { this.profile.value = profile }
        override suspend fun completeOnboarding() { onboardingComplete.value = true }
    }

    private class TestIdentityStore : DeviceIdentityLocalStore {
        override val identity = MutableStateFlow(DeviceIdentity("installation", DEVICE_ID, PROFILE_ID))
        override val credentials = MutableStateFlow(DeviceCredentials(accessToken = "device-token"))
        override suspend fun getOrCreateInstallationId() = "installation"
        override suspend fun saveBootstrap(token: String, expiresAt: String) = Unit
        override suspend fun clearBootstrap() = Unit
        override suspend fun saveAuthenticatedLink(profileId: String, deviceId: String, accessToken: String) {
            identity.value = identity.value.copy(profileId = profileId, deviceId = deviceId)
            credentials.value = DeviceCredentials(accessToken = accessToken)
        }
        override suspend fun clearDeviceAccessToken() { credentials.value = DeviceCredentials() }
    }

    private class TestSessionStore : DeviceSessionRepository {
        override val session = MutableStateFlow(DeviceSession(mode = DeviceMode.CHILD))
        override val adultAccessToken = MutableStateFlow<String?>(null)
        override suspend fun setMode(mode: DeviceMode) { session.value = session.value.copy(mode = mode) }
        override suspend fun authenticateAdult(email: String) = Unit
        override suspend fun addLinkedProfile(profileKey: String) = Unit
        override suspend fun saveAdultAccessToken(token: String) = Unit
        override suspend fun clearAdultAuthentication() = Unit
    }

    private class RecordingScheduler : F12RawEvidenceWorkScheduler {
        var scheduled = 0
        var cancelled = 0
        override fun ensureScheduled() { scheduled++ }
        override fun cancel() { cancelled++ }
    }

    private class RecordingAutomation : F12RawEvidenceAutomation {
        var paused = false
        override suspend fun synchronize() = F12RawEvidenceAutomationOutcome(F12RawEvidenceAutomationStatus.COMPLETED)
        override suspend fun isAuthorizationPaused(profileId: String, deviceId: String) = paused
        override suspend fun resumeAfterMonitoringAuthorization(profileId: String, deviceId: String) { paused = false }
    }

    private companion object {
        const val PROFILE_ID = "profile-1"
        const val DEVICE_ID = "device-1"
    }
}
