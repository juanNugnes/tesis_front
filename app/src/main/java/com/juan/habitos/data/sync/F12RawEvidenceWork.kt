package com.juan.habitos.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.juan.habitos.HabitosApplication
import com.juan.habitos.data.device.F12EvidenceDeliveryState
import com.juan.habitos.data.device.F12RawCaptureStatus
import com.juan.habitos.data.device.F12RawEvidenceCaptureCoordinator
import com.juan.habitos.data.device.F12RawEvidenceRecord
import com.juan.habitos.data.local.F12RawEvidenceStore
import com.juan.habitos.data.remote.F12EvidenceTransportCoordinator
import com.juan.habitos.data.remote.F12EvidenceTransportStatus
import com.juan.habitos.domain.model.DeviceMode
import com.juan.habitos.domain.repository.DeviceIdentityLocalStore
import com.juan.habitos.domain.repository.DeviceSessionRepository
import com.juan.habitos.domain.repository.ProfileLocalStore
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val F12_RAW_RECOVERY_WINDOW_DAYS = 7

internal fun f12ClosedDays(
    now: Instant,
    zoneId: ZoneId,
    recoveryWindowDays: Int = F12_RAW_RECOVERY_WINDOW_DAYS,
): List<LocalDate> {
    require(recoveryWindowDays > 0) { "F12 recovery window must be positive" }
    val today = now.atZone(zoneId).toLocalDate()
    return (recoveryWindowDays downTo 1).map { today.minusDays(it.toLong()) }
}

enum class F12RawEvidenceAutomationStatus {
    DISABLED,
    INELIGIBLE,
    COMPLETED,
    RETRYABLE_FAILURE,
    AUTHORIZATION_PAUSED,
}

data class F12RawEvidenceAutomationOutcome(
    val status: F12RawEvidenceAutomationStatus,
    val capturedDates: List<LocalDate> = emptyList(),
    val reusedDates: List<LocalDate> = emptyList(),
    val unavailableDates: List<LocalDate> = emptyList(),
    val deliveredCaptureIds: List<String> = emptyList(),
    val terminalCaptureIds: List<String> = emptyList(),
)

interface F12RawEvidenceAutomation {
    suspend fun synchronize(): F12RawEvidenceAutomationOutcome
    suspend fun isAuthorizationPaused(profileId: String, deviceId: String): Boolean
    suspend fun resumeAfterMonitoringAuthorization(profileId: String, deviceId: String)
}

/**
 * Materializes closed days before attempting HTTP. This lets WorkManager run without a network
 * constraint and preserves the exact encrypted bytes for a later idempotent retry.
 */
class F12RawEvidenceAutomationCoordinator(
    private val enabled: Boolean,
    private val profileStore: ProfileLocalStore,
    private val identityStore: DeviceIdentityLocalStore,
    private val evidenceStore: F12RawEvidenceStore,
    private val captureCoordinator: F12RawEvidenceCaptureCoordinator,
    private val transportCoordinator: F12EvidenceTransportCoordinator,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
    private val recoveryWindowDays: Int = F12_RAW_RECOVERY_WINDOW_DAYS,
    private val mutex: Mutex = Mutex(),
) : F12RawEvidenceAutomation {
    override suspend fun synchronize(): F12RawEvidenceAutomationOutcome = mutex.withLock {
        if (!enabled) return@withLock F12RawEvidenceAutomationOutcome(F12RawEvidenceAutomationStatus.DISABLED)
        val profile = profileStore.profile.first()
            ?: return@withLock F12RawEvidenceAutomationOutcome(F12RawEvidenceAutomationStatus.INELIGIBLE)
        val profileId = profile.id?.takeIf(String::isNotBlank)
            ?: return@withLock F12RawEvidenceAutomationOutcome(F12RawEvidenceAutomationStatus.INELIGIBLE)
        val identity = identityStore.identity.first()
        val deviceId = identity.deviceId?.takeIf(String::isNotBlank)
            ?: return@withLock F12RawEvidenceAutomationOutcome(F12RawEvidenceAutomationStatus.INELIGIBLE)
        if (!profile.active || !profileStore.onboardingComplete.first() || identity.profileId != profileId) {
            return@withLock F12RawEvidenceAutomationOutcome(F12RawEvidenceAutomationStatus.INELIGIBLE)
        }

        val zoneId = zoneIdProvider()
        val closedDays = f12ClosedDays(clock.instant(), zoneId, recoveryWindowDays)
        val existing = evidenceStore.list(profileId).filter { it.deviceId == deviceId }
        val existingDates = existing.mapTo(mutableSetOf()) { it.localDate }
        val captured = mutableListOf<LocalDate>()
        val reused = mutableListOf<LocalDate>()
        val unavailable = mutableListOf<LocalDate>()

        for (date in closedDays) {
            if (date.toString() in existingDates) {
                reused += date
                continue
            }
            val capture = try {
                captureCoordinator.captureClosedDay(date, zoneId, revision = 1)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                captureCoordinator.recordUnavailableClosedDay(date, zoneId, revision = 1)
            }
            if (capture.status != F12RawCaptureStatus.STORED || capture.saveResult == null) {
                return@withLock F12RawEvidenceAutomationOutcome(
                    F12RawEvidenceAutomationStatus.INELIGIBLE,
                    captured,
                    reused,
                    unavailable,
                )
            }
            captured += date
            if (capture.saveResult.record.exportPairs.isEmpty()) unavailable += date
        }

        // Include retained records older than the collection window: once captured, delivery may
        // remain pending for longer than UsageEvents can still be queried.
        val records = evidenceStore.list(profileId)
            .filter { it.deviceId == deviceId }
            .sortedWith(compareBy<F12RawEvidenceRecord> { it.localDate }.thenBy { it.revision })
        val delivered = mutableListOf<String>()
        val terminal = mutableListOf<String>()
        for (record in records) {
            when (transportCoordinator.deliver(record.captureId).status) {
                F12EvidenceTransportStatus.DELIVERED -> delivered += record.captureId
                F12EvidenceTransportStatus.RETRYABLE_FAILURE,
                F12EvidenceTransportStatus.EVIDENCE_NOT_FOUND -> return@withLock F12RawEvidenceAutomationOutcome(
                    F12RawEvidenceAutomationStatus.RETRYABLE_FAILURE,
                    captured,
                    reused,
                    unavailable,
                    delivered,
                    terminal,
                )
                F12EvidenceTransportStatus.AUTHENTICATION_REQUIRED,
                F12EvidenceTransportStatus.FORBIDDEN -> return@withLock F12RawEvidenceAutomationOutcome(
                    F12RawEvidenceAutomationStatus.AUTHORIZATION_PAUSED,
                    captured,
                    reused,
                    unavailable,
                    delivered,
                    terminal,
                )
                F12EvidenceTransportStatus.CONFLICT,
                F12EvidenceTransportStatus.PERMANENT_FAILURE -> terminal += record.captureId
                F12EvidenceTransportStatus.DISABLED -> return@withLock F12RawEvidenceAutomationOutcome(
                    F12RawEvidenceAutomationStatus.DISABLED,
                    captured,
                    reused,
                    unavailable,
                    delivered,
                    terminal,
                )
            }
        }
        F12RawEvidenceAutomationOutcome(
            F12RawEvidenceAutomationStatus.COMPLETED,
            captured,
            reused,
            unavailable,
            delivered,
            terminal,
        )
    }

    override suspend fun isAuthorizationPaused(profileId: String, deviceId: String): Boolean =
        evidenceStore.list(profileId).any {
            it.deviceId == deviceId && it.delivery.state == F12EvidenceDeliveryState.AUTHENTICATION_BLOCKED
        }

    override suspend fun resumeAfterMonitoringAuthorization(profileId: String, deviceId: String) {
        evidenceStore.list(profileId)
            .filter { it.deviceId == deviceId && it.delivery.state == F12EvidenceDeliveryState.AUTHENTICATION_BLOCKED }
            .forEach { record ->
                evidenceStore.updateDelivery(record.captureId) {
                    it.copy(state = F12EvidenceDeliveryState.RETRY_PENDING, lastHttpCode = null, lastErrorCode = null)
                }
            }
    }
}

interface F12RawEvidenceWorkScheduler {
    fun ensureScheduled()
    fun cancel()
}

class WorkManagerF12RawEvidenceScheduler(context: Context) : F12RawEvidenceWorkScheduler {
    private val workManager = WorkManager.getInstance(context)

    override fun ensureScheduled() {
        // WorkManager is deliberately best effort: each run derives the closed local dates again,
        // so execution need not occur exactly at midnight and timezone changes remain safe.
        val request = PeriodicWorkRequestBuilder<F12RawEvidenceWorker>(24, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "modera_f12_raw_evidence_daily"
    }
}

internal data class F12RawEvidenceWorkEligibility(
    val enabled: Boolean,
    val childMode: Boolean,
    val profileActive: Boolean,
    val onboardingComplete: Boolean,
    val profileId: String?,
    val identityProfileId: String?,
    val deviceId: String?,
    val accessToken: String?,
) {
    val eligible: Boolean
        get() = enabled && childMode && profileActive && onboardingComplete && profileId != null &&
            identityProfileId == profileId && deviceId != null && accessToken != null
}

class F12RawEvidenceWorkController(
    private val enabled: Boolean,
    private val sessionRepository: DeviceSessionRepository,
    private val profileStore: ProfileLocalStore,
    private val identityStore: DeviceIdentityLocalStore,
    private val scheduler: F12RawEvidenceWorkScheduler,
    private val automation: F12RawEvidenceAutomation,
) {
    fun start(scope: CoroutineScope): Job = combine(
        sessionRepository.session,
        profileStore.profile,
        profileStore.onboardingComplete,
        identityStore.credentials,
        identityStore.identity,
    ) { session, profile, onboarding, credentials, identity ->
        F12RawEvidenceWorkEligibility(
            enabled = enabled,
            childMode = session.mode == DeviceMode.CHILD,
            profileActive = profile?.active == true,
            onboardingComplete = onboarding,
            profileId = profile?.id,
            identityProfileId = identity.profileId,
            deviceId = identity.deviceId,
            accessToken = credentials.accessToken,
        )
    }.distinctUntilChanged().onEach(::applySchedule).launchIn(scope)

    suspend fun reconcile() = applySchedule(currentEligibility())

    suspend fun resumeAfterMonitoringAuthorization(profileId: String) {
        val identity = identityStore.identity.first()
        if (identity.profileId == profileId && identity.deviceId != null) {
            automation.resumeAfterMonitoringAuthorization(profileId, identity.deviceId)
        }
        reconcile()
    }

    suspend fun synchronizeFromWorker(): F12RawEvidenceAutomationOutcome {
        val eligibility = currentEligibility()
        if (!eligibility.eligible) {
            scheduler.cancel()
            return F12RawEvidenceAutomationOutcome(F12RawEvidenceAutomationStatus.INELIGIBLE)
        }
        val profileId = requireNotNull(eligibility.profileId)
        val deviceId = requireNotNull(eligibility.deviceId)
        if (automation.isAuthorizationPaused(profileId, deviceId)) {
            scheduler.cancel()
            return F12RawEvidenceAutomationOutcome(F12RawEvidenceAutomationStatus.AUTHORIZATION_PAUSED)
        }
        return automation.synchronize().also {
            if (it.status == F12RawEvidenceAutomationStatus.AUTHORIZATION_PAUSED) scheduler.cancel()
        }
    }

    private suspend fun applySchedule(eligibility: F12RawEvidenceWorkEligibility) {
        if (!eligibility.eligible) {
            scheduler.cancel()
            return
        }
        val paused = automation.isAuthorizationPaused(
            requireNotNull(eligibility.profileId),
            requireNotNull(eligibility.deviceId),
        )
        if (paused) scheduler.cancel() else scheduler.ensureScheduled()
    }

    private suspend fun currentEligibility(): F12RawEvidenceWorkEligibility {
        val session = sessionRepository.session.first()
        val profile = profileStore.profile.first()
        val identity = identityStore.identity.first()
        val credentials = identityStore.credentials.first()
        return F12RawEvidenceWorkEligibility(
            enabled = enabled,
            childMode = session.mode == DeviceMode.CHILD,
            profileActive = profile?.active == true,
            onboardingComplete = profileStore.onboardingComplete.first(),
            profileId = profile?.id,
            identityProfileId = identity.profileId,
            deviceId = identity.deviceId,
            accessToken = credentials.accessToken,
        )
    }
}

class F12RawEvidenceWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (
        (applicationContext as HabitosApplication).f12RawEvidenceWorkController.synchronizeFromWorker().status
    ) {
        F12RawEvidenceAutomationStatus.RETRYABLE_FAILURE -> Result.retry()
        else -> Result.success()
    }
}
