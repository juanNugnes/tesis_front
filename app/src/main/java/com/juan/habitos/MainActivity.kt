package com.juan.habitos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.juan.habitos.domain.model.DeviceMode
import com.juan.habitos.domain.model.DeviceSession
import com.juan.habitos.diagnostics.UsageDiagnosticsLauncher
import com.juan.habitos.permission.AndroidPermissionManager
import com.juan.habitos.ui.adult.AdultDashboardScreen
import com.juan.habitos.ui.adult.*
import com.juan.habitos.ui.child.*
import com.juan.habitos.ui.onboarding.*
import com.juan.habitos.ui.state.*
import com.juan.habitos.ui.theme.AppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal const val EDIT_PROFILE_ROUTE = "editProfile"
internal const val CHILD_PROFILE_SETUP_ROUTE = "childProfileSetup"
internal const val DEVICE_PROFILE_LINK_ROUTE = "deviceProfileLink"

internal fun childEntryRoute(profileId: String?, onboardingComplete: Boolean): String = when {
    profileId == null -> CHILD_PROFILE_SETUP_ROUTE
    onboardingComplete -> "child"
    else -> "profile"
}
internal fun childSetupDestination(linkExistingProfile: Boolean): String =
    if (linkExistingProfile) DEVICE_PROFILE_LINK_ROUTE else "profile"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { AppTheme { HabitosApp() } } }
}

private inline fun <reified T : ViewModel> factory(crossinline create: () -> T) = object : ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V }

@Composable fun HabitosApp() {
    val app = LocalContext.current.applicationContext as HabitosApplication
    val scope = rememberCoroutineScope()
    var start by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        app.initializeDemoIfNeeded()
        val session = app.deviceSessionRepository.session.first()
        val childOnboardingDone = app.profileRepository.onboardingComplete.first()
        val storedProfileId = app.profileRepository.profile.first()?.id
        val adultSessionValid = if (!BuildConfig.DEMO_MODE && session.mode == DeviceMode.ADULT && session.adultAuthenticated) {
            runCatching { app.authRepository.validateSession() }.isSuccess
        } else session.adultAuthenticated
        start = when {
            BuildConfig.DEMO_MODE -> "child"
            session.mode == DeviceMode.ADULT && adultSessionValid -> "adultLink"
            session.mode == DeviceMode.ADULT -> "adultAuth"
            session.mode == DeviceMode.CHILD -> childEntryRoute(storedProfileId, childOnboardingDone)
            childOnboardingDone && storedProfileId != null -> { app.deviceSessionRepository.setMode(DeviceMode.CHILD); "child" }
            else -> "deviceMode"
        }
    }
    val startRoute = start ?: return
    val nav = rememberNavController()
    NavHost(nav, startRoute) {
        composable("deviceMode") { val vm: DeviceModeViewModel = viewModel(factory = factory { DeviceModeViewModel(app.deviceSessionRepository) }); DeviceModeSelectionScreen { mode -> vm.select(mode) { nav.navigate(if (mode == DeviceMode.CHILD) CHILD_PROFILE_SETUP_ROUTE else "adultAuth") } } }
        composable(CHILD_PROFILE_SETUP_ROUTE) { ChildProfileSetupScreen({ nav.navigate(childSetupDestination(false)) }, { nav.navigate(childSetupDestination(true)) }) }
        composable(DEVICE_PROFILE_LINK_ROUTE) { val vm: DeviceProfileLinkViewModel = viewModel(factory = factory { DeviceProfileLinkViewModel(app.deviceRepository, app.profileRepository, app.childPreferencesRepository) }); val state by vm.state.collectAsStateWithLifecycle(); DeviceProfileLinkScreen(state, vm::code, { vm.submit { nav.navigate("child") { popUpTo(CHILD_PROFILE_SETUP_ROUTE) { inclusive = true } } } }, { nav.popBackStack() }) }
        navigation(startDestination = "adultEmail", route = "adultAuth") {
            composable("adultEmail") { entry -> val parent = remember(entry) { nav.getBackStackEntry("adultAuth") }; val vm: AdultAuthViewModel = viewModel(parent, factory = factory { AdultAuthViewModel(app.authRepository, app.deviceSessionRepository, BuildConfig.DEBUG) }); val state by vm.state.collectAsStateWithLifecycle(); AdultEmailScreen(state, vm::email) { vm.requestCode { nav.navigate("adultVerification") } } }
            composable("adultVerification") { entry -> val parent = remember(entry) { nav.getBackStackEntry("adultAuth") }; val vm: AdultAuthViewModel = viewModel(parent, factory = factory { AdultAuthViewModel(app.authRepository, app.deviceSessionRepository, BuildConfig.DEBUG) }); val state by vm.state.collectAsStateWithLifecycle(); AdultVerificationScreen(state, vm::code, { vm.verify { nav.navigate("adultLink") { popUpTo("adultAuth") { inclusive = true } } } }, { nav.popBackStack() }) }
        }
        composable("adultLink") { val vm: LinkDeviceViewModel = viewModel(factory = factory { LinkDeviceViewModel(app.linkRepository) }); val state by vm.state.collectAsStateWithLifecycle(); LaunchedEffect(state.authenticationRequired) { if (state.authenticationRequired) nav.navigate("adultAuth") { popUpTo("adultLink") { inclusive = true } } }; val openPanel: (com.juan.habitos.domain.model.Profile) -> Unit = { profile -> vm.open(profile) { nav.navigate("adult") { popUpTo("adultLink") { inclusive = true } } }; Unit }; AdultLinkProfileScreen(state, vm::code, vm::find, { vm.confirm { nav.navigate("adult") { popUpTo("adultLink") { inclusive = true } } } }, openPanel) }
        composable("profile") { val vm: ProfileViewModel = viewModel(factory = factory { ProfileViewModel(app.profileRepository, app.deviceRepository) }); val state by vm.state.collectAsStateWithLifecycle(); ProfileScreen(state, vm::setName, vm::selectAvatar, vm::selectAge, vm::toggleSchoolSchedule, vm::sleepStart, vm::sleepEnd) { if (BuildConfig.DEMO_MODE) vm.save { nav.navigate("createPin") } else vm.create { nav.navigate("createPin") } } }
        composable("createPin") { val vm: PinViewModel = viewModel(factory = factory { PinViewModel(app.pinStore) }); val state by vm.state.collectAsStateWithLifecycle(); CreatePinScreen(state, { vm.digit(it, true) { nav.navigate("permissions") } }, vm::delete) }
        composable("permissions") { var granted by rememberSaveable { mutableStateOf(emptySet<Int>()) }; val manager = remember { AndroidPermissionManager(app) }; PermissionsScreen(granted, { index -> manager.request(index); granted = granted + index }, { nav.navigate("preferencesIntro") }) }
        composable("preferencesIntro") { val profile by app.profileRepository.profile.collectAsStateWithLifecycle(initialValue = null); PreferencesOnboardingIntro(profile?.name.orEmpty(), { nav.navigate("preferencesOnboarding") }, { scope.launch { app.profileRepository.completeOnboarding(); nav.navigate("child") { popUpTo("profile") { inclusive = true } } } }) }
        composable("preferencesOnboarding") { val vm: ChildPreferencesViewModel = viewModel(factory = factory { ChildPreferencesViewModel(app.childPreferencesRepository, app.profileRepository, BuildConfig.DEMO_MODE) }); val state by vm.state.collectAsStateWithLifecycle(); ChildPreferencesScreen(state, vm::toggleInterest, vm::adaptationType, vm::toggleConsideration, vm::toggleRestriction, vm::notes, vm::validUntil, { vm.save { scope.launch { app.profileRepository.completeOnboarding(); nav.navigate("child") { popUpTo("profile") { inclusive = true } } } } }, { nav.popBackStack() }) }
        composable("child") { val vm: ChildViewModel = viewModel(factory = factory { ChildViewModel(app.profileRepository, app.usageRepository) }); val state by vm.state.collectAsStateWithLifecycle(); ChildHomeScreen(state, { nav.navigate("adultPin") }, vm::showAnotherActivity, vm::startActivity, vm::finishActivity) }
        composable("adultPin") { val vm: PinViewModel = viewModel(factory = factory { PinViewModel(app.pinStore) }); val state by vm.state.collectAsStateWithLifecycle(); DisposableEffect(Unit) { vm.clear(); onDispose {} }; AdultPinScreen(state, { vm.digit(it, false) { nav.navigate("adult") { popUpTo("adultPin") { inclusive = true } } } }, vm::delete) { nav.popBackStack() } }
        composable("adult") {
            val session by app.deviceSessionRepository.session.collectAsStateWithLifecycle(initialValue = DeviceSession())
            val mode = session.mode ?: return@composable
            val profileRepository = if (mode == DeviceMode.ADULT) app.adultProfileRepository else app.profileRepository
            val vm: AdultViewModel = viewModel(key = "adult-dashboard-$mode", factory = factory { AdultViewModel(profileRepository, app.usageRepository, app.alertRepository) })
            val state by vm.state.collectAsStateWithLifecycle()
            AdultDashboardScreen(state, vm::period, { nav.navigate("adultProfile") }) { if (session.mode == DeviceMode.CHILD) nav.navigate("child") { popUpTo("child") { inclusive = true } } }
        }
        composable("adultProfile") {
            val session by app.deviceSessionRepository.session.collectAsStateWithLifecycle(initialValue = DeviceSession())
            val mode = session.mode ?: return@composable
            val profileRepository = if (mode == DeviceMode.ADULT) app.adultProfileRepository else app.profileRepository
            val profile by profileRepository.profile.collectAsStateWithLifecycle(initialValue = null)
            profile?.let { AdultProfileScreen(it, { nav.navigate(EDIT_PROFILE_ROUTE) }, { nav.navigate("profileSchedules") }, { nav.navigate("preferencesAdult") }, if (session.mode == DeviceMode.ADULT) ({ nav.navigate("profileContexts") }) else null, if (session.mode == DeviceMode.ADULT) ({ nav.navigate("monitoringConsent") }) else null, if (session.mode == DeviceMode.CHILD) ({ nav.navigate("adultProfileLinkCode") }) else null, if (BuildConfig.DEBUG && UsageDiagnosticsLauncher.isAvailable) ({ UsageDiagnosticsLauncher.open(app) }) else null, { nav.popBackStack() }) }
        }
        composable("adultProfileLinkCode") { val vm: AdultProfileLinkCodeViewModel = viewModel(factory = factory { AdultProfileLinkCodeViewModel(app.deviceRepository) }); val state by vm.state.collectAsStateWithLifecycle(); AdultProfileLinkCodeScreen(state, vm::generate) { nav.popBackStack() } }
        composable(EDIT_PROFILE_ROUTE) {
            val session by app.deviceSessionRepository.session.collectAsStateWithLifecycle(initialValue = DeviceSession())
            val mode = session.mode ?: return@composable
            val adultActor = mode == DeviceMode.ADULT
            val repository = if (adultActor) app.adultProfileRepository else app.profileRepository
            val vm: EditProfileViewModel = viewModel(key = "edit-profile-$mode", factory = factory { EditProfileViewModel(repository, BuildConfig.DEMO_MODE) })
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.adultAuthenticationRequired) { if (state.adultAuthenticationRequired) nav.navigate("adultAuth") { popUpTo("adult") { inclusive = true } } }
            EditProfileScreen(state, vm::name, vm::age, vm::avatar, { vm.save { nav.popBackStack() } }) { nav.popBackStack() }
        }
        composable("profileSchedules") {
            val session by app.deviceSessionRepository.session.collectAsStateWithLifecycle(initialValue = DeviceSession())
            val mode = session.mode ?: return@composable
            val adultActor = mode == DeviceMode.ADULT
            val repository = if (adultActor) app.adultProfileRepository else app.profileRepository
            val vm: ProfileScheduleViewModel = viewModel(key = "profile-schedules-$mode", factory = factory { ProfileScheduleViewModel(repository, BuildConfig.DEMO_MODE) })
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.adultAuthenticationRequired) { if (state.adultAuthenticationRequired) nav.navigate("adultAuth") { popUpTo("adult") { inclusive = true } } }
            ProfileScheduleScreen(state, vm::schoolStart, vm::schoolEnd, vm::sleepStart, vm::sleepEnd, vm::save) { nav.popBackStack() }
        }
        composable("preferencesAdult") {
            val session by app.deviceSessionRepository.session.collectAsStateWithLifecycle(initialValue = DeviceSession())
            val mode = session.mode ?: return@composable
            val adultActor = mode == DeviceMode.ADULT
            val preferencesRepository = if (adultActor) app.adultPreferencesRepository else app.childPreferencesRepository
            val profileRepository = if (adultActor) app.adultProfileRepository else app.profileRepository
            val vm: ChildPreferencesViewModel = viewModel(key = "profile-preferences-$mode", factory = factory { ChildPreferencesViewModel(preferencesRepository, profileRepository, BuildConfig.DEMO_MODE) })
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.adultAuthenticationRequired) { if (state.adultAuthenticationRequired) nav.navigate("adultAuth") { popUpTo("adult") { inclusive = true } } }
            ChildPreferencesScreen(state, vm::toggleInterest, vm::adaptationType, vm::toggleConsideration, vm::toggleRestriction, vm::notes, vm::validUntil, { vm.save() }, { nav.popBackStack() })
        }
        composable("profileContexts") {
            val session by app.deviceSessionRepository.session.collectAsStateWithLifecycle(initialValue = DeviceSession())
            val mode = session.mode ?: return@composable
            if (mode == DeviceMode.ADULT) {
                val vm: ProfileContextViewModel = viewModel(factory = factory { ProfileContextViewModel(app.profileContextRepository, BuildConfig.DEMO_MODE) })
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.adultAuthenticationRequired) { if (state.adultAuthenticationRequired) nav.navigate("adultAuth") { popUpTo("adult") { inclusive = true } } }
                ProfileContextScreen(state, vm::beginCreate, vm::beginEdit, vm::delete, vm::type, vm::startDate, vm::endDate, vm::description, vm::save, vm::dismissForm) { nav.popBackStack() }
            }
        }
        composable("monitoringConsent") {
            val session by app.deviceSessionRepository.session.collectAsStateWithLifecycle(initialValue = DeviceSession())
            if (session.mode == DeviceMode.ADULT) {
                val profile by app.adultProfileRepository.profile.collectAsStateWithLifecycle(initialValue = null)
                val profileId = profile?.id ?: return@composable
                val vm: MonitoringConsentViewModel = viewModel(
                    key = "monitoring-consent-$profileId",
                    factory = factory { MonitoringConsentViewModel(app.monitoringConsentRepository, profileId, BuildConfig.DEMO_MODE) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.adultAuthenticationRequired) {
                    if (state.adultAuthenticationRequired) nav.navigate("adultAuth") { popUpTo("adult") { inclusive = true } }
                }
                MonitoringConsentScreen(state, vm::grant, vm::requestRevoke, vm::confirmRevoke, vm::cancelRevoke) { nav.popBackStack() }
            }
        }
    }
}
