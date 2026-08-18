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
import com.juan.habitos.permission.AndroidPermissionManager
import com.juan.habitos.ui.adult.AdultDashboardScreen
import com.juan.habitos.ui.child.*
import com.juan.habitos.ui.onboarding.*
import com.juan.habitos.ui.state.*
import com.juan.habitos.ui.theme.AppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { AppTheme { HabitosApp() } } }
}

private inline fun <reified T : ViewModel> factory(crossinline create: () -> T) = object : ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V }

@Composable fun HabitosApp() {
    val app = LocalContext.current.applicationContext as HabitosApplication
    val scope = rememberCoroutineScope()
    var start by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { start = if (app.profileRepository.onboardingComplete.first()) "child" else "profile" }
    val startRoute = start ?: return
    val nav = rememberNavController()
    NavHost(nav, startRoute) {
        composable("profile") { val vm: ProfileViewModel = viewModel(factory = factory { ProfileViewModel(app.profileRepository) }); val state by vm.state.collectAsStateWithLifecycle(); ProfileScreen(state, vm::setName, vm::selectAvatar, vm::selectAge, vm::toggleSchoolSchedule, vm::selectBedtime) { vm.save { nav.navigate("createPin") } } }
        composable("createPin") { val vm: PinViewModel = viewModel(factory = factory { PinViewModel(app.pinStore) }); val state by vm.state.collectAsStateWithLifecycle(); CreatePinScreen(state, { vm.digit(it, true) { nav.navigate("permissions") } }, vm::delete) }
        composable("permissions") { var granted by rememberSaveable { mutableStateOf(emptySet<Int>()) }; val manager = remember { AndroidPermissionManager(app) }; PermissionsScreen(granted, { index -> manager.request(index); granted = granted + index }, { scope.launch { app.profileRepository.completeOnboarding(); nav.navigate("child") { popUpTo("profile") { inclusive = true } } } }) }
        composable("child") { val vm: ChildViewModel = viewModel(factory = factory { ChildViewModel(app.profileRepository, app.usageRepository) }); val state by vm.state.collectAsStateWithLifecycle(); ChildHomeScreen(state, { nav.navigate("adultPin") }, vm::showAnotherPauseIdea, vm::startPause, vm::cancelPause) }
        composable("adultPin") { val vm: PinViewModel = viewModel(factory = factory { PinViewModel(app.pinStore) }); val state by vm.state.collectAsStateWithLifecycle(); DisposableEffect(Unit) { vm.clear(); onDispose {} }; AdultPinScreen(state, { vm.digit(it, false) { nav.navigate("adult") { popUpTo("adultPin") { inclusive = true } } } }, vm::delete) { nav.popBackStack() } }
        composable("adult") { val vm: AdultViewModel = viewModel(factory = factory { AdultViewModel(app.profileRepository, app.usageRepository, app.alertRepository) }); val state by vm.state.collectAsStateWithLifecycle(); AdultDashboardScreen(state, vm::period) { nav.navigate("child") { popUpTo("child") { inclusive = true } } } }
    }
}
