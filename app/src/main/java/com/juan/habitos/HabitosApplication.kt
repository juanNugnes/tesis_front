package com.juan.habitos

import android.app.Application
import com.juan.habitos.data.local.*
import com.juan.habitos.data.mock.*
import com.juan.habitos.data.device.AndroidAggregatedUsageCollector
import com.juan.habitos.data.device.AndroidDeviceUsageDataSource
import com.juan.habitos.data.remote.CachedProfileRepository
import com.juan.habitos.data.remote.CachedChildPreferencesRepository
import com.juan.habitos.data.remote.CachedProfileContextRepository
import com.juan.habitos.data.remote.HabitsApiClient
import com.juan.habitos.data.remote.RemoteDeviceRepository
import com.juan.habitos.data.remote.RemoteAdultAuthRepository
import com.juan.habitos.data.remote.RemoteLinkRepository
import com.juan.habitos.data.remote.AdultRequestAuthorization
import com.juan.habitos.data.remote.DeviceRequestAuthorization
import com.juan.habitos.data.remote.RemoteActivitySuggestionRepository
import com.juan.habitos.data.remote.RemoteMonitoringConsentRepository
import com.juan.habitos.data.remote.RemoteAdultDeviceRepository
import com.juan.habitos.data.remote.AdultUsageRepository
import com.juan.habitos.data.remote.DeviceUsageRepository
import com.juan.habitos.data.remote.UsageSyncCoordinator
import com.juan.habitos.data.sync.UsageWorkController
import com.juan.habitos.data.sync.WorkManagerUsageWorkScheduler
import com.juan.habitos.domain.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

class HabitosApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val localProfileStore by lazy { DataStoreProfileRepository(this) }
    private val localChildPreferencesStore by lazy { DataStoreChildPreferencesRepository(this) }
    private val localProfileContextStore by lazy { DataStoreProfileContextStore(this) }
    private val localDeviceIdentityStore by lazy { DataStoreDeviceIdentityStore(this) }
    private val adultRequestAuthorization by lazy { AdultRequestAuthorization(deviceSessionRepository) }
    private val deviceRequestAuthorization by lazy { DeviceRequestAuthorization(localDeviceIdentityStore) }
    val habitsApi by lazy { HabitsApiClient.api }
    private val usageApi by lazy { HabitsApiClient.usageApi }
    val profileRepository by lazy { CachedProfileRepository(localProfileStore, habitsApi, localDeviceIdentityStore) }
    val adultProfileRepository by lazy { CachedProfileRepository(localProfileStore, habitsApi, adultRequestAuthorization) }
    val childPreferencesRepository by lazy { CachedChildPreferencesRepository(localChildPreferencesStore, localProfileStore, habitsApi, localDeviceIdentityStore) }
    val adultPreferencesRepository by lazy { CachedChildPreferencesRepository(localChildPreferencesStore, localProfileStore, habitsApi, adultRequestAuthorization) }
    val profileContextRepository by lazy { CachedProfileContextRepository(localProfileContextStore, localProfileStore, habitsApi, remoteEnabled = !BuildConfig.DEMO_MODE, authorization = adultRequestAuthorization) }
    val monitoringConsentRepository by lazy { RemoteMonitoringConsentRepository(habitsApi, adultRequestAuthorization) }
    val adultDeviceRepository by lazy {
        if (BuildConfig.DEMO_MODE) PreviewAdultDeviceRepository()
        else RemoteAdultDeviceRepository(habitsApi, adultRequestAuthorization)
    }
    val activitySuggestionRepository by lazy {
        if (BuildConfig.DEMO_MODE) PreviewActivitySuggestionRepository()
        else RemoteActivitySuggestionRepository(localProfileStore, habitsApi, deviceRequestAuthorization)
    }
    val deviceSessionRepository by lazy { DataStoreDeviceSessionRepository(this) }
    val deviceRepository by lazy { RemoteDeviceRepository(localDeviceIdentityStore, localProfileStore, habitsApi) }
    val authRepository by lazy { if (BuildConfig.DEMO_MODE) MockAuthRepository() else RemoteAdultAuthRepository(habitsApi, deviceSessionRepository) }
    val linkRepository by lazy { if (BuildConfig.DEMO_MODE) MockLinkRepository(profileRepository, deviceSessionRepository) else RemoteLinkRepository(habitsApi, deviceSessionRepository, localProfileStore) }
    val pinStore by lazy { HashedPinStore(this) }
    private val pendingUsageSnapshotStore by lazy { EncryptedUsageSnapshotStore(this) }
    private val androidUsageDataSource by lazy { AndroidDeviceUsageDataSource(this) }
    private val usageCollector by lazy { AndroidAggregatedUsageCollector(androidUsageDataSource) }
    private val usageWorkScheduler by lazy { WorkManagerUsageWorkScheduler(this) }
    val usageSyncCoordinator by lazy {
        UsageSyncCoordinator(
            localProfileStore,
            localDeviceIdentityStore,
            usageCollector,
            androidUsageDataSource::hasUsageAccess,
            usageApi,
            deviceRequestAuthorization,
            pendingUsageSnapshotStore,
            onAutomaticPause = { usageWorkScheduler.cancel() },
        )
    }
    val usageWorkController by lazy {
        UsageWorkController(
            deviceSessionRepository,
            localProfileStore,
            localDeviceIdentityStore,
            androidUsageDataSource::hasUsageAccess,
            usageWorkScheduler,
            usageSyncCoordinator,
        )
    }
    val usageRepository by lazy {
        if (BuildConfig.DEMO_MODE) FakeUsageRepository()
        else DeviceUsageRepository(usageSyncCoordinator)
    }
    val adultUsageRepository by lazy {
        if (BuildConfig.DEMO_MODE) FakeUsageRepository()
        else AdultUsageRepository(localProfileStore, usageApi, adultRequestAuthorization)
    }
    val alertRepository by lazy { FakeAlertRepository() }

    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.DEMO_MODE) usageWorkController.start(applicationScope)
    }

    suspend fun initializeDemoIfNeeded() {
        if (!BuildConfig.DEMO_MODE) return
        val hasProfile = profileRepository.profile.first() != null
        val onboardingDone = profileRepository.onboardingComplete.first()
        if (hasProfile && onboardingDone) return
        profileRepository.save(
            Profile(
                name = "Juana",
                avatar = "🦊",
                age = 7,
                schoolSchedules = setOf(SchoolSchedule.MORNING),
                bedtime = BedtimeRange.FROM_22_TO_23,
                schoolStart = "08:00",
                schoolEnd = "12:30",
                sleepStart = "22:00",
                sleepEnd = "07:00",
            )
        )
        pinStore.save("1234")
        profileRepository.completeOnboarding()
    }
}
