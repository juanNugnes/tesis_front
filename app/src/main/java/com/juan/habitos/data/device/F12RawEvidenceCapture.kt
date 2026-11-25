package com.juan.habitos.data.device

import com.juan.habitos.data.local.F12EvidenceSaveResult
import com.juan.habitos.data.local.F12RawEvidenceStore
import com.juan.habitos.data.remote.F12EvidenceTransportCoordinator
import com.juan.habitos.data.remote.F12EvidenceTransportOutcome
import com.juan.habitos.data.remote.F12EvidenceTransportStatus
import com.juan.habitos.domain.repository.DeviceIdentityLocalStore
import com.juan.habitos.domain.repository.ProfileLocalStore
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class F12RawCaptureStatus { DISABLED, STORED, MISSING_CONFIGURATION }

data class F12RawCaptureOutcome(
    val status: F12RawCaptureStatus,
    val saveResult: F12EvidenceSaveResult? = null,
)

/**
 * Local-only entry point. It deliberately has no HTTP dependency and is not scheduled by WorkManager.
 */
class F12RawEvidenceCaptureCoordinator(
    private val enabled: Boolean,
    private val profileStore: ProfileLocalStore,
    private val identityStore: DeviceIdentityLocalStore,
    eventCollector: UsageEventRunCollector,
    private val evidenceStore: F12RawEvidenceStore,
    private val clock: Clock = Clock.systemUTC(),
    private val mutex: Mutex = Mutex(),
) {
    private val diagnosticCollector = DailyHistoricalUsageDiagnosticCollector(eventCollector, clock::millis)

    suspend fun captureClosedDay(
        localDate: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
        revision: Int = 1,
    ): F12RawCaptureOutcome = mutex.withLock {
        if (!enabled) return@withLock F12RawCaptureOutcome(F12RawCaptureStatus.DISABLED)
        require(localDate < LocalDate.now(clock.withZone(zoneId))) { "F12 raw evidence requires a closed local day" }
        val profile = profileStore.profile.first()
            ?: return@withLock F12RawCaptureOutcome(F12RawCaptureStatus.MISSING_CONFIGURATION)
        val profileId = profile.id?.takeIf(String::isNotBlank)
            ?: return@withLock F12RawCaptureOutcome(F12RawCaptureStatus.MISSING_CONFIGURATION)
        if (!profile.active || !profileStore.onboardingComplete.first()) {
            return@withLock F12RawCaptureOutcome(F12RawCaptureStatus.MISSING_CONFIGURATION)
        }
        val identity = identityStore.identity.first()
        val deviceId = identity.deviceId?.takeIf(String::isNotBlank)
            ?: return@withLock F12RawCaptureOutcome(F12RawCaptureStatus.MISSING_CONFIGURATION)
        if (identity.profileId != profileId) {
            return@withLock F12RawCaptureOutcome(F12RawCaptureStatus.MISSING_CONFIGURATION)
        }
        val diagnostic = diagnosticCollector.collect(
            date = localDate,
            zoneId = zoneId,
            profileId = profileId,
            deviceId = deviceId,
            includeOverlappingRuns = false,
        )
        val record = F12RawEvidenceContract.record(diagnostic, revision, clock.instant())
        F12RawCaptureOutcome(F12RawCaptureStatus.STORED, evidenceStore.save(record))
    }

    /**
     * Persists the contract sentinel when Android cannot export a closed day at all. Query failures
     * that can be represented by the original summaries must continue through [captureClosedDay]
     * instead, so they are never mistaken for a measured zero.
     */
    suspend fun recordUnavailableClosedDay(
        localDate: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
        revision: Int = 1,
    ): F12RawCaptureOutcome = mutex.withLock {
        if (!enabled) return@withLock F12RawCaptureOutcome(F12RawCaptureStatus.DISABLED)
        require(localDate < LocalDate.now(clock.withZone(zoneId))) { "F12 raw evidence requires a closed local day" }
        val profile = profileStore.profile.first()
            ?: return@withLock F12RawCaptureOutcome(F12RawCaptureStatus.MISSING_CONFIGURATION)
        val profileId = profile.id?.takeIf(String::isNotBlank)
            ?: return@withLock F12RawCaptureOutcome(F12RawCaptureStatus.MISSING_CONFIGURATION)
        if (!profile.active || !profileStore.onboardingComplete.first()) {
            return@withLock F12RawCaptureOutcome(F12RawCaptureStatus.MISSING_CONFIGURATION)
        }
        val identity = identityStore.identity.first()
        val deviceId = identity.deviceId?.takeIf(String::isNotBlank)
            ?: return@withLock F12RawCaptureOutcome(F12RawCaptureStatus.MISSING_CONFIGURATION)
        if (identity.profileId != profileId) {
            return@withLock F12RawCaptureOutcome(F12RawCaptureStatus.MISSING_CONFIGURATION)
        }
        val record = F12RawEvidenceContract.unavailableRecord(
            profileId = profileId,
            deviceId = deviceId,
            localDate = localDate.toString(),
            timezoneId = zoneId.id,
            revision = revision,
            createdAt = clock.instant(),
        )
        F12RawCaptureOutcome(F12RawCaptureStatus.STORED, evidenceStore.save(record))
    }
}

data class F12ManualEvidenceOutcome(
    val capture: F12RawCaptureOutcome?,
    val transport: F12EvidenceTransportOutcome,
)

/** Debug entry point: retries a stored revision instead of collecting replacement bytes. */
class F12RawEvidenceManualCoordinator(
    private val profileStore: ProfileLocalStore,
    private val evidenceStore: F12RawEvidenceStore,
    private val captureCoordinator: F12RawEvidenceCaptureCoordinator,
    private val transportCoordinator: F12EvidenceTransportCoordinator,
) {
    suspend fun captureAndDeliver(
        localDate: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
        revision: Int = 1,
    ): F12ManualEvidenceOutcome {
        val profileId = profileStore.profile.first()?.id
            ?: return F12ManualEvidenceOutcome(
                null,
                F12EvidenceTransportOutcome(F12EvidenceTransportStatus.EVIDENCE_NOT_FOUND),
            )
        val existing = evidenceStore.list(profileId).firstOrNull {
            it.localDate == localDate.toString() && it.revision == revision
        }
        if (existing != null) {
            return F12ManualEvidenceOutcome(null, transportCoordinator.deliver(existing.captureId))
        }
        val capture = captureCoordinator.captureClosedDay(localDate, zoneId, revision)
        val captureId = capture.saveResult?.record?.captureId
            ?: return F12ManualEvidenceOutcome(
                capture,
                F12EvidenceTransportOutcome(F12EvidenceTransportStatus.DISABLED),
            )
        return F12ManualEvidenceOutcome(capture, transportCoordinator.deliver(captureId))
    }
}
