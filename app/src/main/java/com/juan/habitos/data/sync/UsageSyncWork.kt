package com.juan.habitos.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.juan.habitos.HabitosApplication
import com.juan.habitos.data.remote.UsageSyncCoordinator
import com.juan.habitos.data.remote.UsageSyncOutcome
import com.juan.habitos.data.remote.UsageSyncStatus
import com.juan.habitos.domain.model.DeviceCredentials
import com.juan.habitos.domain.model.DeviceIdentity
import com.juan.habitos.domain.model.DeviceMode
import com.juan.habitos.domain.model.DeviceSession
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.repository.DeviceIdentityLocalStore
import com.juan.habitos.domain.repository.DeviceSessionRepository
import com.juan.habitos.domain.repository.ProfileLocalStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first

interface UsageWorkScheduler {
    fun ensureScheduled()
    fun cancel()
}

class WorkManagerUsageWorkScheduler(context: Context) : UsageWorkScheduler {
    private val workManager = WorkManager.getInstance(context)

    override fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<UsageSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "modera_usage_metrics_periodic"
    }
}

internal data class UsageWorkEligibility(
    val mode: DeviceMode?,
    val profile: Profile?,
    val onboardingComplete: Boolean,
    val credentials: DeviceCredentials,
    val identity: DeviceIdentity,
    val usageAccessGranted: Boolean,
) {
    val profileId: String?
        get() = profile?.id

    val eligible: Boolean
        get() = mode == DeviceMode.CHILD &&
            profile?.active == true &&
            onboardingComplete &&
            profileId != null &&
            identity.profileId == profileId &&
            identity.deviceId != null &&
            credentials.accessToken != null &&
            usageAccessGranted
}

class UsageWorkController(
    private val sessionRepository: DeviceSessionRepository,
    private val profileStore: ProfileLocalStore,
    private val identityStore: DeviceIdentityLocalStore,
    private val hasUsageAccess: () -> Boolean,
    private val scheduler: UsageWorkScheduler,
    private val coordinator: UsageSyncCoordinator,
) {
    fun start(scope: CoroutineScope): Job = combine(
        sessionRepository.session,
        profileStore.profile,
        profileStore.onboardingComplete,
        identityStore.credentials,
        identityStore.identity,
    ) { session, profile, onboarding, credentials, identity ->
        UsageWorkEligibility(session.mode, profile, onboarding, credentials, identity, safeUsageAccess())
    }
        .distinctUntilChanged()
        .onEach(::applySchedule)
        .launchIn(scope)

    suspend fun reconcile() {
        applySchedule(currentEligibility())
    }

    suspend fun resumeAfterMonitoringAuthorization(profileId: String) {
        coordinator.resumeAfterMonitoringAuthorization(profileId)
        reconcile()
    }

    suspend fun synchronizeFromWorker(): UsageSyncOutcome? {
        val eligibility = currentEligibility()
        if (!eligibility.eligible) {
            scheduler.cancel()
            return null
        }
        val profileId = requireNotNull(eligibility.profileId)
        val token = requireNotNull(eligibility.credentials.accessToken)
        if (coordinator.isAuthorizationPaused(profileId, token)) {
            scheduler.cancel()
            return null
        }
        return coordinator.synchronize()
    }

    private suspend fun currentEligibility(): UsageWorkEligibility {
        val session = sessionRepository.session.first()
        val profile = profileStore.profile.first()
        val onboarding = profileStore.onboardingComplete.first()
        val credentials = identityStore.credentials.first()
        val identity = identityStore.identity.first()
        return UsageWorkEligibility(session.mode, profile, onboarding, credentials, identity, safeUsageAccess())
    }

    private suspend fun applySchedule(eligibility: UsageWorkEligibility) {
        if (!eligibility.eligible) {
            scheduler.cancel()
            return
        }
        val profileId = requireNotNull(eligibility.profileId)
        val token = requireNotNull(eligibility.credentials.accessToken)
        if (coordinator.isAuthorizationPaused(profileId, token)) scheduler.cancel() else scheduler.ensureScheduled()
    }

    private fun safeUsageAccess() = runCatching(hasUsageAccess).getOrDefault(false)
}

class UsageSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HabitosApplication
        val outcome = app.usageWorkController.synchronizeFromWorker() ?: return Result.success()
        return outcome.toWorkerResult()
    }
}

private fun UsageSyncOutcome.toWorkerResult(): ListenableWorker.Result = when (status) {
    UsageSyncStatus.RETRYABLE_FAILURE -> ListenableWorker.Result.retry()
    UsageSyncStatus.PERMANENT_FAILURE -> ListenableWorker.Result.failure()
    UsageSyncStatus.SYNCED,
    UsageSyncStatus.AUTHORIZATION_PAUSED,
    UsageSyncStatus.MISSING_CONFIGURATION,
    UsageSyncStatus.PERMISSION_MISSING,
    UsageSyncStatus.QUEUE_FULL -> ListenableWorker.Result.success()
}
