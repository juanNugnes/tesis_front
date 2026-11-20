package com.juan.habitos.data.device

import com.juan.habitos.data.local.F12EvidenceSaveResult
import com.juan.habitos.data.local.F12RawEvidenceStore
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
}
