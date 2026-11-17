package com.juan.habitos.data.device

import com.juan.habitos.data.local.AutomaticOfferOpportunityStore
import com.juan.habitos.domain.model.DeviceCredentials
import com.juan.habitos.domain.model.DeviceIdentity
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.model.PendingAutomaticOffer
import com.juan.habitos.domain.repository.DeviceIdentityLocalStore
import com.juan.habitos.domain.repository.ProfileLocalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomaticOfferOpportunityTest {
    private val now = 10_000_000L

    @Test fun `continuous interactive interval becomes eligible at 45 minutes`() {
        val evidence = evaluator(events(now - 45 * MINUTE to INTERACTIVE)).evaluate("profile", "device", now)

        assertEquals(InteractiveOpportunityState.ELIGIBLE, evidence.state)
        assertEquals(45 * MINUTE, evidence.continuousInteractiveMillis)
        assertEquals("INTERACTIVE", evidence.interactiveState)
    }

    @Test fun `separate intervals are not accumulated`() {
        val evidence = evaluator(events(
            now - 80 * MINUTE to INTERACTIVE,
            now - 50 * MINUTE to NON_INTERACTIVE,
            now - 30 * MINUTE to INTERACTIVE,
        )).evaluate("profile", "device", now)

        assertEquals(InteractiveOpportunityState.BELOW_THRESHOLD, evidence.state)
        assertEquals(30 * MINUTE, evidence.continuousInteractiveMillis)
    }

    @Test fun `screen off closes the interval`() {
        val evidence = evaluator(events(now - 60 * MINUTE to INTERACTIVE, now - MINUTE to NON_INTERACTIVE))
            .evaluate("profile", "device", now)

        assertEquals(InteractiveOpportunityState.SCREEN_NON_INTERACTIVE, evidence.state)
        assertNull(evidence.intervalId)
    }

    @Test fun `missing events produce missing evidence rather than inferred usage`() {
        val evidence = evaluator(events()).evaluate("profile", "device", now)

        assertEquals(InteractiveOpportunityState.MISSING_EVIDENCE, evidence.state)
        assertEquals("NO_SCREEN_STATE_EVIDENCE", evidence.quality.reason)
    }

    @Test fun `restart invalidates a previously open interval`() {
        val evidence = evaluator(events(now - 60 * MINUTE to INTERACTIVE, now - 5 * MINUTE to SHUTDOWN, now - 4 * MINUTE to STARTUP))
            .evaluate("profile", "device", now)

        assertEquals(InteractiveOpportunityState.MISSING_EVIDENCE, evidence.state)
        assertEquals(true, evidence.quality.lifecycleInterruptionObserved)
    }

    @Test fun `interactive event after restart starts a new interval`() {
        val first = evaluator(events(now - 90 * MINUTE to INTERACTIVE)).evaluate("profile", "device", now)
        val second = evaluator(events(
            now - 90 * MINUTE to INTERACTIVE,
            now - 60 * MINUTE to SHUTDOWN,
            now - 59 * MINUTE to STARTUP,
            now - 45 * MINUTE to INTERACTIVE,
        )).evaluate("profile", "device", now)

        assertEquals(InteractiveOpportunityState.ELIGIBLE, second.state)
        assertNotEquals(first.intervalId, second.intervalId)
    }

    @Test fun `repeated collection keeps stable identifiers and coordinator prevents retry duplicates`() = runTest {
        val collector = MutableCollector(events(now - 50 * MINUTE to INTERACTIVE))
        val store = MemoryEvidenceStore()
        val coordinator = AutomaticOfferOpportunityCoordinator(
            true, Profiles(), Identity(), OpenInteractiveIntervalEvaluator(collector), store, { now },
        )

        val first = coordinator.evaluate()
        collector.run = events(now - 50 * MINUTE to INTERACTIVE, runId = "retry-run")
        val retry = coordinator.evaluate()

        assertEquals(AutomaticOfferEvaluationStatus.RECORDED, first.status)
        assertEquals(AutomaticOfferEvaluationStatus.DUPLICATE, retry.status)
        assertEquals(first.evidence?.seriesId, retry.evidence?.seriesId)
        assertEquals(first.evidence?.intervalId, retry.evidence?.intervalId)
        assertEquals(1, store.pending().size)
    }

    @Test fun `collector failure is reported as missing evidence`() {
        val failure = UsageCollectorRunFactory.permissionUnavailable(
            requestedStartEpochMillis = 0, requestedEndEpochMillis = now, collectedAtEpochMillis = now,
            androidVersion = "test", deviceModel = "test",
        )
        val evidence = evaluator(failure).evaluate("profile", "device", now)
        assertEquals(InteractiveOpportunityState.MISSING_EVIDENCE, evidence.state)
        assertEquals(CollectorErrorCode.USAGE_PERMISSION_NOT_AVAILABLE, evidence.quality.errorCode)
    }

    @Test fun `game evidence uses only the currently foreground package`() {
        val run = events(
            now - 3 * MINUTE to 23,
            now - 2 * MINUTE to 24,
            now - MINUTE to 23,
        ).copy(events = events(
            now - 3 * MINUTE to 23,
            now - 2 * MINUTE to 24,
            now - MINUTE to 23,
        ).events.mapIndexed { index, event ->
            event.copy(packageName = if (index < 2) "previous.app" else "current.game")
        })
        assertEquals("current.game", run.lastForegroundPackage())
    }

    @Test fun `paused foreground application leaves game state unknown`() {
        val base = events(now - 2 * MINUTE to 23, now - MINUTE to 24)
        val run = base.copy(events = base.events.map { it.copy(packageName = "game") })
        assertNull(run.lastForegroundPackage())
    }

    private fun evaluator(run: UsageCollectorRun) = OpenInteractiveIntervalEvaluator(MutableCollector(run))

    private fun events(vararg values: Pair<Long, Int>, runId: String = "run") = UsageCollectorRunFactory.success(
        collectorRunId = runId,
        requestedStartEpochMillis = 0,
        requestedEndEpochMillis = now,
        collectedAtEpochMillis = now,
        androidVersion = "test",
        deviceModel = "test",
        eventsInQueryOrder = values.map { (timestamp, type) ->
            CollectedUsageEvent(timestamp, type, "event-$type", null, null)
        },
    )

    private class MutableCollector(var run: UsageCollectorRun) : UsageEventRunCollector {
        override fun collectRun(requestedStartEpochMillis: Long, requestedEndEpochMillis: Long) = run
    }

    private class Profiles : ProfileLocalStore {
        override val profile = MutableStateFlow<Profile?>(Profile("Child", "fox", 9, id = "profile"))
        override val onboardingComplete = MutableStateFlow(true)
        override suspend fun save(profile: Profile) { this.profile.value = profile }
        override suspend fun completeOnboarding() { onboardingComplete.value = true }
    }

    private class Identity : DeviceIdentityLocalStore {
        override val identity = MutableStateFlow(DeviceIdentity("installation", "device", "profile"))
        override val credentials = MutableStateFlow(DeviceCredentials())
        override suspend fun getOrCreateInstallationId() = "installation"
        override suspend fun saveBootstrap(token: String, expiresAt: String) = Unit
        override suspend fun clearBootstrap() = Unit
        override suspend fun saveAuthenticatedLink(profileId: String, deviceId: String, accessToken: String) = Unit
        override suspend fun clearDeviceAccessToken() = Unit
    }

    private class MemoryEvidenceStore : AutomaticOfferOpportunityStore {
        private val evidence = mutableListOf<InteractiveOpportunityEvidence>()
        override fun recordIfAbsent(evidence: InteractiveOpportunityEvidence): Boolean =
            if (this.evidence.any { it.intervalId == evidence.intervalId }) false else true.also { this.evidence += evidence }
        override fun pending() = evidence.toList()
        override fun complete(intervalId: String) { evidence.removeAll { it.intervalId == intervalId } }
        private var offer: PendingAutomaticOffer? = null
        override fun savePendingOffer(offer: PendingAutomaticOffer) { this.offer = offer }
        override fun pendingOffer() = offer
        override fun updatePendingOffer(transform: (PendingAutomaticOffer?) -> PendingAutomaticOffer?) { offer = transform(offer) }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val INTERACTIVE = 15
        const val NON_INTERACTIVE = 16
        const val SHUTDOWN = 26
        const val STARTUP = 27
    }
}
