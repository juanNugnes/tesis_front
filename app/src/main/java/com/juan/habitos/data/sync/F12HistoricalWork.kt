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
import com.juan.habitos.data.remote.F12HistoricalSyncCoordinator
import com.juan.habitos.data.remote.F12SyncStatus
import com.juan.habitos.domain.model.DeviceMode
import com.juan.habitos.domain.repository.DeviceIdentityLocalStore
import com.juan.habitos.domain.repository.DeviceSessionRepository
import com.juan.habitos.domain.repository.ProfileLocalStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

interface F12HistoricalWorkScheduler {
    fun ensureScheduled()
    fun cancel()
}

class WorkManagerF12HistoricalScheduler(context: Context) : F12HistoricalWorkScheduler {
    private val workManager = WorkManager.getInstance(context)
    override fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<F12HistoricalWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    override fun cancel() { workManager.cancelUniqueWork(WORK_NAME) }

    companion object { const val WORK_NAME = "modera_f12_historical_daily" }
}

class F12HistoricalWorkController(
    private val enabled: Boolean,
    private val sessionRepository: DeviceSessionRepository,
    private val profileStore: ProfileLocalStore,
    private val identityStore: DeviceIdentityLocalStore,
    private val hasUsageAccess: () -> Boolean,
    private val scheduler: F12HistoricalWorkScheduler,
    private val coordinator: F12HistoricalSyncCoordinator,
) {
    fun start(scope: CoroutineScope): Job = combine(
        sessionRepository.session,
        profileStore.profile,
        profileStore.onboardingComplete,
        identityStore.credentials,
        identityStore.identity,
    ) { session, profile, onboarding, credentials, identity ->
        enabled && session.mode == DeviceMode.CHILD && profile?.active == true && onboarding && profile.id != null &&
            identity.profileId == profile.id && identity.deviceId != null && credentials.accessToken != null && safeUsageAccess()
    }.distinctUntilChanged().onEach { if (it) scheduler.ensureScheduled() else scheduler.cancel() }.launchIn(scope)

    suspend fun reconcile() {
        if (eligible()) scheduler.ensureScheduled() else scheduler.cancel()
    }

    suspend fun resumeAfterMonitoringAuthorization(profileId: String) {
        coordinator.resumeAuthorization(profileId)
        reconcile()
    }

    suspend fun synchronizeFromWorker(): F12SyncStatus {
        if (!eligible()) { scheduler.cancel(); return F12SyncStatus.DISABLED }
        return coordinator.synchronize().status.also {
            if (it == F12SyncStatus.AUTHORIZATION_PAUSED) scheduler.cancel()
        }
    }

    private suspend fun eligible(): Boolean {
        if (!enabled || sessionRepository.session.first().mode != DeviceMode.CHILD || !profileStore.onboardingComplete.first()) return false
        val profile = profileStore.profile.first() ?: return false
        val identity = identityStore.identity.first()
        val credentials = identityStore.credentials.first()
        return profile.active && profile.id != null && identity.profileId == profile.id && identity.deviceId != null &&
            credentials.accessToken != null && safeUsageAccess()
    }

    private fun safeUsageAccess() = runCatching(hasUsageAccess).getOrDefault(false)
}

class F12HistoricalWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (
        (applicationContext as HabitosApplication).f12HistoricalWorkController.synchronizeFromWorker()
    ) {
        F12SyncStatus.RETRYABLE_FAILURE -> Result.retry()
        F12SyncStatus.PERMANENT_FAILURE -> Result.failure()
        else -> Result.success()
    }
}
