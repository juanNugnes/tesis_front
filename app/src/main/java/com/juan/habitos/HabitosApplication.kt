package com.juan.habitos

import android.app.Application
import com.juan.habitos.data.local.*
import com.juan.habitos.data.mock.*
import com.juan.habitos.data.device.AndroidAggregatedUsageCollector
import com.juan.habitos.data.device.AndroidDeviceUsageDataSource
import com.juan.habitos.data.device.F12RawEvidenceCaptureCoordinator
import com.juan.habitos.data.device.F12RawEvidenceManualCoordinator
import com.juan.habitos.data.device.AutomaticOfferOpportunityCoordinator
import com.juan.habitos.data.device.OpenInteractiveIntervalEvaluator
import com.juan.habitos.data.device.AutomaticOfferNotificationPresenter
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
import com.juan.habitos.data.remote.ProfileFormDiagnostics
import com.juan.habitos.data.remote.UsageSyncCoordinator
import com.juan.habitos.data.remote.F12HistoricalSyncCoordinator
import com.juan.habitos.data.remote.F12EvidenceRepository
import com.juan.habitos.data.remote.F12EvidenceTransportCoordinator
import com.juan.habitos.data.remote.HistoricalSubmissionRepository
import com.juan.habitos.data.remote.AutomaticOfferSyncCoordinator
import com.juan.habitos.data.remote.RemoteAutomaticOfferRepository
import com.juan.habitos.data.remote.RemoteAutomaticOfferSettingsRepository
import com.juan.habitos.data.sync.UsageWorkController
import com.juan.habitos.data.sync.WorkManagerUsageWorkScheduler
import com.juan.habitos.data.sync.F12HistoricalWorkController
import com.juan.habitos.data.sync.WorkManagerF12HistoricalScheduler
import com.juan.habitos.data.sync.AutomaticOfferOpportunityWorkController
import com.juan.habitos.data.sync.WorkManagerAutomaticOfferOpportunityScheduler
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
    // Temporary debug-only metadata. No actual profile IDs, bodies, emails, tokens or headers.
    private val profileFormDiagnostics by lazy { ProfileFormDiagnostics { metadata ->
        if (BuildConfig.DEBUG) android.util.Log.i("ModeraProfileForm", metadata)
    } }
    val parentalScreenTimeRepository by lazy {
        com.juan.habitos.data.remote.RemoteParentalScreenTimeRepository(HabitsApiClient.parentalScreenTimeApi, adultRequestAuthorization, profileFormDiagnostics)
    }
    private val usageApi by lazy { HabitsApiClient.usageApi }
    val profileRepository by lazy { CachedProfileRepository(localProfileStore, habitsApi, deviceRequestAuthorization, localDeviceIdentityStore, profileFormDiagnostics) }
    val adultProfileRepository by lazy { CachedProfileRepository(localProfileStore, habitsApi, adultRequestAuthorization, formDiagnostics = profileFormDiagnostics) }
    fun preferencesForProfile(profileId: String, adultActor: Boolean) = CachedChildPreferencesRepository(
        localChildPreferencesStore, localProfileStore, habitsApi,
        if (adultActor) adultRequestAuthorization else deviceRequestAuthorization,
        expectedProfileId = profileId, diagnostics = profileFormDiagnostics,
    )
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
    val automaticOfferRepository by lazy {
        RemoteAutomaticOfferRepository(habitsApi, deviceRequestAuthorization, automaticOfferOpportunityStore, activitySuggestionRepository)
    }
    private val automaticOfferNotificationPresenter by lazy {
        AutomaticOfferNotificationPresenter(this, automaticOfferRepository)
    }
    val automaticOfferSettingsRepository by lazy {
        RemoteAutomaticOfferSettingsRepository(habitsApi, adultRequestAuthorization)
    }
    val deviceSessionRepository by lazy { DataStoreDeviceSessionRepository(this) }
    val deviceRepository by lazy { RemoteDeviceRepository(localDeviceIdentityStore, localProfileStore, habitsApi) }
    val authRepository by lazy { if (BuildConfig.DEMO_MODE) MockAuthRepository() else RemoteAdultAuthRepository(habitsApi, deviceSessionRepository) }
    val linkRepository by lazy { if (BuildConfig.DEMO_MODE) MockLinkRepository(profileRepository, deviceSessionRepository) else RemoteLinkRepository(habitsApi, deviceSessionRepository, localProfileStore) }
    val pinStore by lazy { HashedPinStore(this) }
    private val pendingUsageSnapshotStore by lazy { EncryptedUsageSnapshotStore(this) }
    private val historicalOutboxStore by lazy { EncryptedHistoricalOutboxStore(this) }
    private val f12RawEvidenceStore by lazy { EncryptedF12RawEvidenceStore(this) }
    private val androidUsageDataSource by lazy { AndroidDeviceUsageDataSource(this) }
    private val automaticOfferOpportunityStore by lazy { SharedPreferencesAutomaticOfferOpportunityStore(this) }
    private val usageCollector by lazy { AndroidAggregatedUsageCollector(androidUsageDataSource) }
    private val usageWorkScheduler by lazy { WorkManagerUsageWorkScheduler(this) }
    private val f12HistoricalWorkScheduler by lazy { WorkManagerF12HistoricalScheduler(this) }
    private val automaticOfferOpportunityWorkScheduler by lazy { WorkManagerAutomaticOfferOpportunityScheduler(this) }
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
    private val f12HistoricalSyncCoordinator by lazy {
        F12HistoricalSyncCoordinator(
            BuildConfig.F12_HISTORICAL_SUBMISSIONS_ENABLED,
            localProfileStore,
            localDeviceIdentityStore,
            com.juan.habitos.data.device.AndroidF12HistoricalRequestCollector(androidUsageDataSource),
            HistoricalSubmissionRepository(usageApi, deviceRequestAuthorization),
            historicalOutboxStore,
        )
    }
    val f12HistoricalWorkController by lazy {
        F12HistoricalWorkController(
            BuildConfig.F12_HISTORICAL_SUBMISSIONS_ENABLED,
            deviceSessionRepository,
            localProfileStore,
            localDeviceIdentityStore,
            androidUsageDataSource::hasUsageAccess,
            f12HistoricalWorkScheduler,
            f12HistoricalSyncCoordinator,
        )
    }
    val f12RawEvidenceCaptureCoordinator by lazy {
        F12RawEvidenceCaptureCoordinator(
            BuildConfig.F12_RAW_EVIDENCE_CAPTURE_ENABLED,
            localProfileStore,
            localDeviceIdentityStore,
            androidUsageDataSource,
            f12RawEvidenceStore,
        )
    }
    val f12RawEvidenceTransportCoordinator by lazy {
        F12EvidenceTransportCoordinator(
            BuildConfig.DEBUG && BuildConfig.F12_RAW_EVIDENCE_TRANSPORT_ENABLED,
            f12RawEvidenceStore,
            F12EvidenceRepository(HabitsApiClient.f12EvidenceApi, deviceRequestAuthorization),
            onAuthenticationRequired = localDeviceIdentityStore::clearDeviceAccessToken,
        )
    }
    val f12RawEvidenceManualCoordinator by lazy {
        F12RawEvidenceManualCoordinator(
            localProfileStore,
            f12RawEvidenceStore,
            f12RawEvidenceCaptureCoordinator,
            f12RawEvidenceTransportCoordinator,
        )
    }
    private val automaticOfferOpportunityCoordinator by lazy {
        AutomaticOfferOpportunityCoordinator(
            BuildConfig.AUTOMATIC_OFFER_OPPORTUNITIES_ENABLED,
            localProfileStore,
            localDeviceIdentityStore,
            OpenInteractiveIntervalEvaluator(androidUsageDataSource, gameStateDetector = androidUsageDataSource::detectGameActive),
            automaticOfferOpportunityStore,
        )
    }
    private val automaticOfferSyncCoordinator by lazy {
        AutomaticOfferSyncCoordinator(
            habitsApi, deviceRequestAuthorization, automaticOfferOpportunityStore,
            onOfferAvailable = {
                automaticOfferRepository.refreshPendingOffer()
                automaticOfferNotificationPresenter.presentIfPossible()
            },
        )
    }
    val automaticOfferOpportunityWorkController by lazy {
        AutomaticOfferOpportunityWorkController(
            BuildConfig.AUTOMATIC_OFFER_OPPORTUNITIES_ENABLED,
            deviceSessionRepository,
            localProfileStore,
            localDeviceIdentityStore,
            androidUsageDataSource::hasUsageAccess,
            automaticOfferOpportunityWorkScheduler,
            automaticOfferOpportunityCoordinator,
            automaticOfferSyncCoordinator::synchronize,
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
    val alertsDemoEnabled: Boolean get() = com.juan.habitos.data.mock.alertsDemoEnabled(BuildConfig.DEBUG, BuildConfig.ALERTS_DEMO_MODE)
    val dashboardDemoEnabled: Boolean get() = com.juan.habitos.data.mock.dashboardDemoEnabled(BuildConfig.DEBUG, BuildConfig.DASHBOARD_DEMO_MODE)
    fun dashboardDemoActiveFor(profile: Profile?): Boolean =
        dashboardDemoEnabled && profile?.let(dashboardDemoFixture::matches) == true
    val alertRepository by lazy { alertRepositoryForMode(BuildConfig.DEBUG, BuildConfig.ALERTS_DEMO_MODE) }
    val adultMessagesDemoEnabled: Boolean get() = BuildConfig.DEBUG && BuildConfig.ADULT_MESSAGES_DEMO_MODE
    val automaticActivitiesDemoEnabled: Boolean get() = com.juan.habitos.ui.child.automaticActivitiesDemoEnabled(
        BuildConfig.DEBUG, BuildConfig.AUTOMATIC_ACTIVITIES_DEMO_MODE,
    )
    val adultMessagesRepository by lazy {
        com.juan.habitos.data.remote.adultMessagesRepositoryForMode(
            BuildConfig.DEMO_MODE, HabitsApiClient.adultMessagesApi, adultRequestAuthorization,
            adultMessagesDemo = BuildConfig.ADULT_MESSAGES_DEMO_MODE,
            isDebug = BuildConfig.DEBUG,
            demoMessages = ::createAdultMessagesDemoRepository,
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.DEMO_MODE) {
            usageWorkController.start(applicationScope)
            f12HistoricalWorkController.start(applicationScope)
            automaticOfferOpportunityWorkController.start(applicationScope)
        }
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
