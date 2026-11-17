package com.juan.habitos.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.juan.habitos.HabitosApplication
import com.juan.habitos.data.device.AutomaticOfferOpportunityCoordinator
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

interface AutomaticOfferOpportunityWorkScheduler {
    fun ensureScheduled()
    fun cancel()
}

class WorkManagerAutomaticOfferOpportunityScheduler(context: Context) : AutomaticOfferOpportunityWorkScheduler {
    private val workManager = WorkManager.getInstance(context)

    override fun ensureScheduled() {
        // Fifteen minutes is WorkManager's minimum periodic interval. Execution is best effort,
        // so this detects the first opportunity after 45 minutes rather than exactly at minute 45.
        val request = PeriodicWorkRequestBuilder<AutomaticOfferOpportunityWorker>(15, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun cancel() { workManager.cancelUniqueWork(WORK_NAME) }

    companion object { const val WORK_NAME = "modera_automatic_offer_opportunity" }
}

class AutomaticOfferOpportunityWorkController(
    private val enabled: Boolean,
    private val sessionRepository: DeviceSessionRepository,
    private val profileStore: ProfileLocalStore,
    private val identityStore: DeviceIdentityLocalStore,
    private val hasUsageAccess: () -> Boolean,
    private val scheduler: AutomaticOfferOpportunityWorkScheduler,
    private val coordinator: AutomaticOfferOpportunityCoordinator,
    private val synchronize: suspend () -> Unit = {},
) {
    fun start(scope: CoroutineScope): Job = combine(
        sessionRepository.session,
        profileStore.profile,
        profileStore.onboardingComplete,
        identityStore.identity,
    ) { session, profile, onboarding, identity ->
        enabled && session.mode == DeviceMode.CHILD && onboarding && profile?.active == true && profile.id != null &&
            identity.profileId == profile.id && identity.deviceId != null && safeUsageAccess()
    }.distinctUntilChanged().onEach { if (it) scheduler.ensureScheduled() else scheduler.cancel() }.launchIn(scope)

    suspend fun evaluateFromWorker() {
        if (!eligible()) {
            scheduler.cancel()
            return
        }
        coordinator.evaluate()
        synchronize()
    }

    suspend fun reconcile() {
        if (eligible()) scheduler.ensureScheduled() else scheduler.cancel()
    }

    private suspend fun eligible(): Boolean {
        if (!enabled || sessionRepository.session.first().mode != DeviceMode.CHILD || !profileStore.onboardingComplete.first()) return false
        val profile = profileStore.profile.first() ?: return false
        val identity = identityStore.identity.first()
        return profile.active && profile.id != null && identity.profileId == profile.id && identity.deviceId != null && safeUsageAccess()
    }

    private fun safeUsageAccess() = runCatching(hasUsageAccess).getOrDefault(false)
}

class AutomaticOfferOpportunityWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        (applicationContext as HabitosApplication).automaticOfferOpportunityWorkController.evaluateFromWorker()
    }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
}
