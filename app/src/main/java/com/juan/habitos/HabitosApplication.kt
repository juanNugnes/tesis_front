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
import com.juan.habitos.data.remote.AdultUsageRepository
import com.juan.habitos.data.remote.DeviceUsageRepository
import com.juan.habitos.domain.model.*
import kotlinx.coroutines.flow.first

class HabitosApplication : Application() {
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
    private val usageCollector by lazy { AndroidAggregatedUsageCollector(AndroidDeviceUsageDataSource(this)) }
    val usageRepository by lazy {
        if (BuildConfig.DEMO_MODE) FakeUsageRepository()
        else DeviceUsageRepository(localProfileStore, usageCollector, usageApi, deviceRequestAuthorization, pendingUsageSnapshotStore)
    }
    val adultUsageRepository by lazy {
        if (BuildConfig.DEMO_MODE) FakeUsageRepository()
        else AdultUsageRepository(localProfileStore, usageApi, adultRequestAuthorization)
    }
    val alertRepository by lazy { FakeAlertRepository() }

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
