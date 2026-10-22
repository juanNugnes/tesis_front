package com.juan.habitos.ui.state

import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.juan.habitos.ui.adult.profileScheduleSummary
import com.juan.habitos.data.remote.*
import java.lang.reflect.Proxy

private class TestProfileRepo : ProfileRepository {
    override val profile = MutableStateFlow<Profile?>(null)
    override val onboardingComplete = MutableStateFlow(false)
    var createdProfile: Profile? = null
    var createCalls = 0
    var createFailure: Exception? = null
    var settingsFailure: Exception? = null
    var refreshedSettings: Profile? = null
    var updatedSettings: Profile? = null
    var refreshSettingsCalls = 0
    var updateSettingsCalls = 0
    override suspend fun save(profile: Profile) { this.profile.value = profile }
    override suspend fun completeOnboarding() { onboardingComplete.value = true }
    override suspend fun create(profile: Profile): Profile {
        createCalls++
        createFailure?.let { throw it }
        createdProfile = profile
        return profile.copy(id = "remote-id").also { this.profile.value = it }
    }
    override suspend fun refresh(): Profile? = profile.value
    override suspend fun update(profile: Profile): Profile = profile.also { this.profile.value = it }
    override suspend fun refreshSettings(): Profile? {
        refreshSettingsCalls++
        settingsFailure?.let { throw it }
        return (refreshedSettings ?: profile.value)?.also { profile.value = it }
    }
    override suspend fun updateSettings(profile: Profile): Profile {
        updateSettingsCalls++
        settingsFailure?.let { throw it }
        return (updatedSettings ?: profile).also { this.profile.value = it }
    }
}
private class TestDeviceRepo : DeviceRepository {
    override suspend fun createAdultProfileLinkCode() = AdultProfileLinkCode("728341", "2099-01-01T00:10:00Z", "ADULT_PROFILE_LINK")
    override val identity = MutableStateFlow(DeviceIdentity())
    var registerCalls = 0
    var registrationFailure: Exception? = null
    override suspend fun getOrCreateInstallationId() = "installation-1"
    override suspend fun getStoredDeviceId(): String? = identity.value.deviceId
    override suspend fun registerDevice(profileId: String): RegisteredDevice {
        registerCalls++
        registrationFailure?.let { throw it }
        return RegisteredDevice("device-1", profileId, "installation-1", "ANDROID", true).also {
            identity.value = DeviceIdentity(it.installationId, it.id, it.profileId)
        }
    }
    override suspend fun claimProfile(code: String): DeviceProfileClaim = error("Not used")
}
private class TestPinStore : PinStore { var pin = ""; override suspend fun save(pin: String) { this.pin = pin }; override suspend fun verify(pin: String) = pin == this.pin }
private class TestUsage : UsageRepository { override suspend fun daily(period: DashboardPeriod) = DailyUsage(if(period == DashboardPeriod.TODAY) 165 else 900, 180, 8); override suspend fun apps() = emptyList<AppUsage>(); override suspend fun recommendation() = Recommendation("","","") }
private class MutableOverviewUsage(var value: UsageOverview) : UsageRepository {
    override suspend fun overview(period: DashboardPeriod) = value
    override suspend fun daily(period: DashboardPeriod) = value.usage
    override suspend fun apps() = value.applications.orEmpty()
    override suspend fun recommendation() = Recommendation("", "", "")
}
private class TestAlerts : AlertRepository { override suspend fun alerts() = emptyList<Alert>() }
private class TestActivitySuggestions(
    var values: List<ActivitySuggestion> = listOf(
        ActivitySuggestion("activity-1", "Primera idea", "Descripción 1", "CREATIVE", "LOW", false, false, false),
        ActivitySuggestion("activity-2", "Segunda idea", "Descripción 2", "MOVEMENT", "MEDIUM", false, false, false),
        ActivitySuggestion("activity-3", "Tercera idea", "Descripción 3", "STORIES", "LOW", true, false, false),
    ),
) : ActivitySuggestionRepository {
    var offerId: String? = "offer-1"
    var reason: String? = null
    var failure: Exception? = null
    var registerFailure: Exception? = null
    var countFailure: Exception? = null
    var selectionCountValue = 2
    var suggestionCalls = 0
    val selections = mutableListOf<ActivitySelection>()
    val countRequests = mutableListOf<Pair<Long, Long>>()
    override suspend fun suggestions(): ActivitySuggestionOffer {
        suggestionCalls++
        failure?.let { throw it }
        return ActivitySuggestionOffer(offerId, values, reason)
    }
    override suspend fun registerSelection(selection: ActivitySelection) {
        selections += selection
        registerFailure?.let { throw it }
        selectionCountValue++
    }
    override suspend fun selectionCount(startEpochMillis: Long, endEpochMillis: Long): Int {
        countRequests += startEpochMillis to endEpochMillis
        countFailure?.let { throw it }
        return selectionCountValue
    }
}
private class TestPreferencesRepo(initial: ChildPreferences = ChildPreferences()) : ChildPreferencesRepository {
    override val preferences = MutableStateFlow(initial)
    var refreshedPreferences: ChildPreferences? = null
    var updatedPreferences: ChildPreferences? = null
    var failure: Exception? = null
    var refreshCalls = 0
    var updateCalls = 0
    var lastUpdateRequest: ChildPreferences? = null
    override suspend fun save(preferences: ChildPreferences) { this.preferences.value = preferences }
    override suspend fun refresh(): ChildPreferences {
        refreshCalls++
        failure?.let { throw it }
        return (refreshedPreferences ?: preferences.value).also { preferences.value = it }
    }
    override suspend fun update(preferences: ChildPreferences): ChildPreferences {
        updateCalls++
        lastUpdateRequest = preferences
        failure?.let { throw it }
        return (updatedPreferences ?: preferences).also { this.preferences.value = it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelsTest {
    @Test fun `onboarding and panel send only real preferences and never call experimental PMUM`() = runTest(dispatcher) {
        for (onboarding in listOf(true, false)) {
            val calls = mutableListOf<String>()
            var captured: ActivityPreferencesRequest? = null
            val api = Proxy.newProxyInstance(HabitsApi::class.java.classLoader, arrayOf(HabitsApi::class.java)) { _, method, args ->
                calls += method.name
                when (method.name) {
                    "getActivityPreferences" -> {
                        assertEquals("child", args[0])
                        assertEquals(if (onboarding) "Bearer device-token" else "Bearer adult-token", args[1])
                        ActivityPreferencesDto(profileId = "child")
                    }
                    "updateActivityPreferences" -> {
                        assertEquals("child", args[0])
                        assertEquals(if (onboarding) "Bearer device-token" else "Bearer adult-token", args[1])
                        captured = args[2] as ActivityPreferencesRequest
                        ActivityPreferencesDto(profileId = "child", interests = captured!!.interests)
                    }
                    else -> error("Unexpected API operation: ${method.name}")
                }
            } as HabitsApi
            val profiles = TestProfileRepo().apply { profile.value = Profile("Niño", "🌿", 9, id = "child") }
            val authorization = if (onboarding) DeviceRequestAuthorization(TestDeviceCredentialStore())
                else AdultRequestAuthorization(TestAdultCredentialStore())
            val repo = CachedChildPreferencesRepository(TestPreferencesRepo(), profiles, api, authorization, expectedProfileId = "child")
            val vm = ChildPreferencesViewModel(repo, profiles, false)
            val panel = if (onboarding) null else PreferencesFormViewModel(vm, null)
            advanceUntilIdle()
            assertEquals(9, vm.state.value.age)
            for (sex in ParentalSexChoice.entries) {
                vm.simulateScreenHabits(vm.state.value.simulatedScreenHabits.copy(sex = sex))
                assertEquals(sex, vm.state.value.simulatedScreenHabits.sex)
            }
            for (time in ParentalTimeChoice.entries) {
                vm.simulateScreenHabits(vm.state.value.simulatedScreenHabits.copy(weekday = time, weekend = time))
                assertEquals(time, vm.state.value.simulatedScreenHabits.weekday)
                assertEquals(time, vm.state.value.simulatedScreenHabits.weekend)
            }
            advanceUntilIdle()
            assertFalse(vm.state.value.hasChanges)
            assertFalse(vm.state.value.dirty)
            assertFalse(vm.state.value.saved)
            assertEquals(listOf("getActivityPreferences"), calls)
            panel?.let { assertFalse(it.state.value.canSave); assertFalse(it.state.value.authenticationRequired) }
            vm.toggleInterest(ChildInterest.MUSIC)
            advanceUntilIdle()
            var continued = false
            if (onboarding) vm.save { continued = true } else panel!!.save { continued = true }
            advanceUntilIdle()
            assertTrue(continued)
            assertEquals(listOf("getActivityPreferences", "updateActivityPreferences"), calls)
            assertEquals(listOf("MUSIC"), captured!!.interests)
            val json = com.google.gson.Gson().toJsonTree(captured).asJsonObject
            assertEquals(setOf("interests", "considerations_answered", "considerations", "activities_to_avoid"), json.keySet())
            assertTrue(vm.state.value.saved)
            assertFalse(vm.state.value.hasChanges)
            assertEquals(ParentalSexChoice.UNAVAILABLE, vm.state.value.simulatedScreenHabits.sex)
            panel?.let { assertEquals("Preferencias de actividades guardadas.", it.state.value.message) }
        }
    }

    @Test fun `simulation is optional does not persist and is independent of real preference validation`() = runTest(dispatcher) {
        val repo = TestPreferencesRepo()
        val profiles = TestProfileRepo().apply { profile.value = Profile("Niño", "🌿", 9, id = "child") }
        val vm = ChildPreferencesViewModel(repo, profiles, false)
        advanceUntilIdle()
        assertEquals(ParentalScreenTimeSelections(), vm.state.value.simulatedScreenHabits)
        vm.toggleInterest(ChildInterest.STORIES)
        vm.save(); advanceUntilIdle()
        assertTrue(vm.state.value.saved) // No simulated question was required.
        vm.simulateScreenHabits(ParentalScreenTimeSelections(ParentalSexChoice.FEMALE, ParentalTimeChoice.ONE_TWO, ParentalTimeChoice.SEVEN_PLUS))
        assertTrue(vm.state.value.saved)
        assertFalse(vm.state.value.hasChanges)
        val reopened = ChildPreferencesViewModel(repo, profiles, false)
        advanceUntilIdle()
        assertEquals(ParentalScreenTimeSelections(), reopened.state.value.simulatedScreenHabits)
        assertEquals(setOf(ChildInterest.STORIES), reopened.state.value.preferences.interests)
        profiles.profile.value = Profile("Otro", "🌿", 8, id = "other")
        advanceUntilIdle()
        assertEquals(ParentalScreenTimeSelections(), vm.state.value.simulatedScreenHabits)
        assertEquals(1, repo.updateCalls)
    }

    @Test fun `simulated options remain editable without an adult session and do not trigger authentication`() = runTest(dispatcher) {
        val repo = TestPreferencesRepo()
        val vm = ChildPreferencesViewModel(repo, TestProfileRepo(), false)
        val panel = PreferencesFormViewModel(vm, null)
        advanceUntilIdle()
        vm.simulateScreenHabits(ParentalScreenTimeSelections(ParentalSexChoice.MALE, ParentalTimeChoice.UNDER_ONE, null))
        advanceUntilIdle()
        assertFalse(panel.state.value.authenticationRequired)
        assertFalse(panel.state.value.canSave)
        assertNull(vm.state.value.errorMessage)
        assertEquals(0, repo.updateCalls)
        assertEquals(1, repo.refreshCalls)
        panel.save(); advanceUntilIdle()
        assertEquals(0, repo.updateCalls)
    }
    @Test fun `PMUM routing failure keeps pending answers without repeating successful preferences`() = runTest(dispatcher) {
        val preferencesRepo = TestPreferencesRepo()
        val parentalRepo = TestParentalScreenTimeRepository().apply { readFailure = ProfileFormException(ProfileFormFailure.ENDPOINT_UNAVAILABLE) }
        val vm = ChildPreferencesViewModel(preferencesRepo, TestProfileRepo(), false)
        val habits = ParentalScreenTimeViewModel(parentalRepo, "child", 9)
        val form = PreferencesFormViewModel(vm, habits)
        advanceUntilIdle()
        vm.toggleInterest(ChildInterest.MUSIC); habits.sex(ParentalSexChoice.FEMALE)
        advanceUntilIdle(); form.save(); advanceUntilIdle()
        assertEquals("Preferencias guardadas. Hábitos de pantallas pendientes.", form.state.value.message)
        assertEquals(1, preferencesRepo.updateCalls)
        assertTrue(parentalRepo.writes.isEmpty())
        form.save(); advanceUntilIdle()
        assertEquals(1, preferencesRepo.updateCalls)
        assertTrue(parentalRepo.writes.isEmpty())
        assertEquals(ParentalSexChoice.FEMALE, habits.state.value.selections.sex)
        parentalRepo.readFailure = null
        form.save(); advanceUntilIdle()
        assertEquals(1, preferencesRepo.updateCalls)
        assertEquals(1, parentalRepo.writes.size)
    }
    @Test fun `unified form only saves modified domains and ignores reverted preferences`() = runTest(dispatcher) {
        val preferencesRepo = TestPreferencesRepo()
        val parentalRepo = TestParentalScreenTimeRepository()
        val vm = ChildPreferencesViewModel(preferencesRepo, TestProfileRepo(), demoMode = false)
        val habits = ParentalScreenTimeViewModel(parentalRepo, "child", 9)
        val form = PreferencesFormViewModel(vm, habits)
        advanceUntilIdle()
        form.save(); advanceUntilIdle()
        assertFalse(form.state.value.canSave)
        vm.toggleInterest(ChildInterest.MUSIC); vm.toggleInterest(ChildInterest.MUSIC)
        advanceUntilIdle(); form.save(); advanceUntilIdle()
        assertEquals(0, preferencesRepo.updateCalls)
        assertTrue(parentalRepo.writes.isEmpty())
        vm.toggleInterest(ChildInterest.STORIES)
        advanceUntilIdle(); form.save(); advanceUntilIdle()
        assertEquals(1, preferencesRepo.updateCalls)
        assertTrue(parentalRepo.writes.isEmpty())
        habits.weekday(ParentalTimeChoice.ONE_TWO)
        advanceUntilIdle(); form.save(); form.save(); advanceUntilIdle()
        assertEquals(1, preferencesRepo.updateCalls)
        assertEquals(1, parentalRepo.writes.size)
        assertEquals("Cambios guardados.", form.state.value.message)
        form.save(); advanceUntilIdle()
        assertEquals(1, parentalRepo.writes.size)
    }

    @Test fun `unified form retains successful preferences and retries only failed habits`() = runTest(dispatcher) {
        val preferencesRepo = TestPreferencesRepo()
        val parentalRepo = TestParentalScreenTimeRepository().apply { saveFailure = java.io.IOException("offline") }
        val vm = ChildPreferencesViewModel(preferencesRepo, TestProfileRepo(), false)
        val habits = ParentalScreenTimeViewModel(parentalRepo, "child", 9)
        val form = PreferencesFormViewModel(vm, habits)
        advanceUntilIdle()
        vm.toggleInterest(ChildInterest.MUSIC); habits.sex(ParentalSexChoice.FEMALE)
        advanceUntilIdle(); form.save(); form.save(); advanceUntilIdle()
        assertEquals("Preferencias guardadas. Hábitos de pantallas pendientes.", form.state.value.message)
        assertEquals(1, preferencesRepo.updateCalls)
        assertEquals(1, parentalRepo.writes.size)
        parentalRepo.saveFailure = null
        form.save(); advanceUntilIdle()
        assertEquals(1, preferencesRepo.updateCalls)
        assertEquals(2, parentalRepo.writes.size)
        assertEquals(1, parentalRepo.records["child"]!!.revision)
        assertEquals("Cambios guardados.", form.state.value.message)
    }

    @Test fun `unified form reconciles lost response without duplicating persisted PMUM revision`() = runTest(dispatcher) {
        val preferencesRepo = TestPreferencesRepo()
        val parentalRepo = TestParentalScreenTimeRepository().apply { saveFailure = java.io.IOException("response lost"); persistBeforeFailure = true }
        val vm = ChildPreferencesViewModel(preferencesRepo, TestProfileRepo(), false)
        val habits = ParentalScreenTimeViewModel(parentalRepo, "child", 9)
        val form = PreferencesFormViewModel(vm, habits)
        advanceUntilIdle()
        habits.weekend(ParentalTimeChoice.SEVEN_PLUS)
        advanceUntilIdle(); form.save(); advanceUntilIdle()
        form.save(); advanceUntilIdle()
        assertEquals(1, parentalRepo.writes.size)
        assertEquals(2, parentalRepo.reads.size)
        assertEquals(0, preferencesRepo.updateCalls)
        assertFalse(form.state.value.canSave)
        assertEquals("Cambios guardados.", form.state.value.message)
    }

    @Test fun `unified form retains successful habits and retries only failed preferences`() = runTest(dispatcher) {
        val preferencesRepo = TestPreferencesRepo()
        val parentalRepo = TestParentalScreenTimeRepository()
        val vm = ChildPreferencesViewModel(preferencesRepo, TestProfileRepo(), false)
        val habits = ParentalScreenTimeViewModel(parentalRepo, "child", 9)
        val form = PreferencesFormViewModel(vm, habits)
        advanceUntilIdle()
        preferencesRepo.failure = java.io.IOException("offline")
        vm.toggleInterest(ChildInterest.MUSIC); habits.weekday(ParentalTimeChoice.THREE_FOUR)
        advanceUntilIdle(); form.save(); advanceUntilIdle()
        assertEquals("Hábitos de pantallas guardados. Preferencias pendientes.", form.state.value.message)
        assertEquals(1, parentalRepo.writes.size)
        preferencesRepo.failure = null
        form.save(); advanceUntilIdle()
        assertEquals(1, parentalRepo.writes.size)
        assertEquals(2, preferencesRepo.updateCalls)
        assertFalse(form.state.value.canSave)
    }

    @Test fun `expired adult session blocks both writes and reauthentication reload preserves unsaved answers`() = runTest(dispatcher) {
        val preferencesRepo = TestPreferencesRepo()
        val parentalRepo = TestParentalScreenTimeRepository().apply { readFailure = AdultAuthenticationRequiredException() }
        val profiles = TestProfileRepo().apply { profile.value = Profile("Niño", "🌿", 9, id = "child") }
        val vm = ChildPreferencesViewModel(preferencesRepo, profiles, false)
        val habits = ParentalScreenTimeViewModel(parentalRepo, "child", 9)
        val form = PreferencesFormViewModel(vm, habits)
        advanceUntilIdle()
        vm.toggleInterest(ChildInterest.MUSIC); habits.sex(ParentalSexChoice.FEMALE)
        habits.weekend(ParentalTimeChoice.SEVEN_PLUS)
        advanceUntilIdle(); form.save(); advanceUntilIdle()
        assertTrue(form.state.value.authenticationRequired)
        assertFalse(form.state.value.canSave)
        assertEquals(0, preferencesRepo.updateCalls)
        assertTrue(parentalRepo.writes.isEmpty())
        parentalRepo.readFailure = null
        parentalRepo.records["child"] = ParentalScreenTimeRecord("old", "child", 1, 9, ParentalScreenTimeAnswers(1, 1, 1))
        preferencesRepo.refreshedPreferences = ChildPreferences(interests = setOf(ChildInterest.STORIES))
        form.refresh(); advanceUntilIdle()
        assertFalse(form.state.value.authenticationRequired)
        assertEquals("child", habits.state.value.profileId)
        assertEquals(setOf(ChildInterest.MUSIC), vm.state.value.preferences.interests)
        assertEquals(ParentalSexChoice.FEMALE, habits.state.value.selections.sex)
        assertEquals(ParentalTimeChoice.SEVEN_PLUS, habits.state.value.selections.weekend)
        assertTrue(form.state.value.canSave)
        form.save(); advanceUntilIdle()
        assertEquals(1, preferencesRepo.updateCalls)
        assertEquals(1, parentalRepo.writes.size)
    }

    @Test fun `401 from preferences stops subsequent POST and retained changes save after login`() = runTest(dispatcher) {
        val preferencesRepo = TestPreferencesRepo()
        val parentalRepo = TestParentalScreenTimeRepository()
        val vm = ChildPreferencesViewModel(preferencesRepo, TestProfileRepo(), false)
        val habits = ParentalScreenTimeViewModel(parentalRepo, "child", 9)
        val form = PreferencesFormViewModel(vm, habits)
        advanceUntilIdle()
        vm.toggleInterest(ChildInterest.MUSIC); habits.sex(ParentalSexChoice.MALE)
        preferencesRepo.failure = AdultAuthenticationRequiredException()
        advanceUntilIdle(); form.save(); advanceUntilIdle()
        assertTrue(form.state.value.authenticationRequired)
        assertTrue(parentalRepo.writes.isEmpty())
        form.save(); advanceUntilIdle()
        assertEquals(1, preferencesRepo.updateCalls)
        preferencesRepo.failure = null
        form.refresh(); advanceUntilIdle(); form.save(); advanceUntilIdle()
        assertEquals(1, parentalRepo.writes.size)
        assertFalse(vm.state.value.hasChanges)
    }

    @Test fun `unified demo preferences save locally and never create parental responses`() = runTest(dispatcher) {
        val repo = TestPreferencesRepo()
        val vm = ChildPreferencesViewModel(repo, TestProfileRepo(), true)
        val form = PreferencesFormViewModel(vm, null)
        advanceUntilIdle()
        vm.toggleInterest(ChildInterest.MUSIC)
        advanceUntilIdle(); form.save(); advanceUntilIdle()
        assertEquals(0, repo.updateCalls)
        assertEquals(0, repo.refreshCalls)
        assertEquals(setOf(ChildInterest.MUSIC), repo.preferences.value.interests)
        assertFalse(form.state.value.canSave)
    }
    @Test fun `preferences and parental answers save and retry independently without losing successful data`() = runTest(dispatcher) {
        val profiles = TestProfileRepo().apply { profile.value = Profile("Niño", "🦊", 9, id = "child") }
        val preferencesRepo = TestPreferencesRepo()
        val parentalRepo = TestParentalScreenTimeRepository().apply { readFailure = java.io.IOException("PMUM offline") }
        val preferences = ChildPreferencesViewModel(preferencesRepo, profiles, false)
        val parental = ParentalScreenTimeViewModel(parentalRepo, "child", 9)
        advanceUntilIdle()
        preferences.toggleInterest(ChildInterest.DRAW_CREATE); preferences.save(); advanceUntilIdle()
        assertTrue(preferences.state.value.saved)
        assertEquals(1, preferencesRepo.updateCalls)
        assertTrue(parentalRepo.writes.isEmpty())
        parentalRepo.readFailure = null
        parental.refresh(); advanceUntilIdle()
        parentalRepo.saveFailure = java.io.IOException("lost response")
        parental.sex(ParentalSexChoice.MALE); parental.save(); advanceUntilIdle()
        assertTrue(preferences.state.value.saved)
        assertEquals(setOf(ChildInterest.DRAW_CREATE), preferencesRepo.preferences.value.interests)
        parentalRepo.saveFailure = null
        parental.refresh(); advanceUntilIdle(); parental.save(); advanceUntilIdle()
        val savedParental = parental.state.value.record
        assertNotNull(savedParental)
        assertEquals(1, preferencesRepo.updateCalls)
        val parentalPosts = parentalRepo.writes.size
        preferencesRepo.failure = java.io.IOException("preferences offline")
        preferences.toggleInterest(ChildInterest.MUSIC); preferences.save(); advanceUntilIdle()
        assertFalse(preferences.state.value.saved)
        assertEquals(savedParental, parental.state.value.record)
        preferencesRepo.failure = null
        preferences.save(); advanceUntilIdle()
        assertTrue(preferences.state.value.saved)
        assertEquals(parentalPosts, parentalRepo.writes.size)
    }
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `profile requires valid name avatar age and school schedule`() { val vm = ProfileViewModel(TestProfileRepo()); assertFalse(vm.state.value.valid); vm.setName("Sofía"); vm.selectAvatar("🦊"); vm.selectAge(5); assertFalse(vm.state.value.valid); vm.selectAge(9); assertFalse(vm.state.value.valid); vm.toggleSchoolSchedule(SchoolSchedule.MORNING); assertTrue(vm.state.value.valid) }
    @Test fun `school schedules support multiple selections`() { val vm = ProfileViewModel(TestProfileRepo()); vm.toggleSchoolSchedule(SchoolSchedule.MORNING); vm.toggleSchoolSchedule(SchoolSchedule.AFTERNOON); assertEquals(setOf(SchoolSchedule.MORNING, SchoolSchedule.AFTERNOON), vm.state.value.schoolSchedules); vm.toggleSchoolSchedule(SchoolSchedule.MORNING); assertEquals(setOf(SchoolSchedule.AFTERNOON), vm.state.value.schoolSchedules) }
    @Test fun `profile including complete sleep schedule is persisted`() = runTest(dispatcher) { val repo = TestProfileRepo(); val vm = ProfileViewModel(repo); vm.setName(" Sofía "); vm.selectAvatar("🦊"); vm.selectAge(9); vm.toggleSchoolSchedule(SchoolSchedule.MORNING); vm.sleepStart("21:30"); vm.sleepEnd("06:45"); vm.save {}; advanceUntilIdle(); assertEquals(Profile("Sofía", "🦊", 9, schoolSchedules = setOf(SchoolSchedule.MORNING), sleepStart = "21:30", sleepEnd = "06:45"), repo.profile.value) }
    @Test fun `cross midnight sleep schedule is valid`() { val state = ProfileUiState(name = "Sofía", avatar = "🦊", age = 9, schoolSchedules = setOf(SchoolSchedule.MORNING), sleepStart = "22:00", sleepEnd = "07:00"); assertTrue(state.sleepScheduleValid); assertTrue(state.valid) }
    @Test fun `equal sleep times are invalid`() { val state = ProfileUiState(name = "Sofía", avatar = "🦊", age = 9, schoolSchedules = setOf(SchoolSchedule.MORNING), sleepStart = "22:00", sleepEnd = "22:00"); assertFalse(state.sleepScheduleValid); assertFalse(state.valid) }
    @Test fun `demo save remains local with both sleep times and never invokes remote create`() = runTest(dispatcher) { val repo = TestProfileRepo(); val vm = ProfileViewModel(repo); vm.setName("Demo"); vm.selectAvatar("🦊"); vm.selectAge(9); vm.toggleSchoolSchedule(SchoolSchedule.MORNING); vm.save {}; advanceUntilIdle(); assertEquals("22:00", repo.profile.value?.sleepStart); assertEquals("07:00", repo.profile.value?.sleepEnd); assertNull(repo.createdProfile) }
    @Test fun `remote profile creation uses selected sleep start and end`() = runTest(dispatcher) { val repo = TestProfileRepo(); val vm = ProfileViewModel(repo); vm.setName(" Juana "); vm.selectAvatar("🐸"); vm.selectAge(10); vm.toggleSchoolSchedule(SchoolSchedule.AFTERNOON); vm.sleepStart("23:15"); vm.sleepEnd("08:10"); var completed = false; vm.create { completed = true }; advanceUntilIdle(); assertEquals(Profile("Juana", "🐸", 10, schoolSchedules = setOf(SchoolSchedule.AFTERNOON), sleepStart = "23:15", sleepEnd = "08:10"), repo.createdProfile); assertEquals("remote-id", repo.profile.value?.id); assertTrue(completed) }
    @Test fun `remote profile error keeps cached profile and exposes a friendly message`() = runTest(dispatcher) { val repo = TestProfileRepo().apply { profile.value = Profile("Anterior", "🦊", 9, id = "existing"); createFailure = java.io.IOException("offline") }; val vm = ProfileViewModel(repo); vm.setName("Juana"); vm.selectAvatar("🐸"); vm.selectAge(10); vm.toggleSchoolSchedule(SchoolSchedule.MORNING); vm.create {}; advanceUntilIdle(); assertEquals("existing", repo.profile.value?.id); assertNotNull(vm.state.value.errorMessage); assertFalse(vm.state.value.submitting) }
    @Test fun `device registration retry never creates a second profile`() = runTest(dispatcher) {
        val profileRepo = TestProfileRepo()
        val deviceRepo = TestDeviceRepo().apply { registrationFailure = java.io.IOException("offline") }
        val vm = ProfileViewModel(profileRepo, deviceRepo)
        advanceUntilIdle()
        vm.setName("Juana")
        vm.selectAvatar("fox")
        vm.selectAge(10)
        vm.toggleSchoolSchedule(SchoolSchedule.MORNING)

        vm.create {}
        advanceUntilIdle()

        assertEquals(1, profileRepo.createCalls)
        assertEquals(1, deviceRepo.registerCalls)
        assertTrue(vm.state.value.profileCreatedDevicePending)
        assertTrue(vm.state.value.errorMessage!!.startsWith("El perfil se creó"))

        var completed = 0
        deviceRepo.registrationFailure = null
        vm.create { completed++ }
        advanceUntilIdle()

        assertEquals(1, profileRepo.createCalls)
        assertEquals(2, deviceRepo.registerCalls)
        assertEquals(1, completed)
        assertFalse(vm.state.value.profileCreatedDevicePending)
    }
    @Test fun `pin creation accepts four digits and supports delete`() = runTest(dispatcher) { val store = TestPinStore(); val vm = PinViewModel(store); vm.digit(1,true) {}; vm.digit(2,true) {}; vm.delete(); assertEquals("1", vm.state.value.digits); vm.digit(2,true) {}; vm.digit(3,true) {}; vm.digit(4,true) {}; advanceUntilIdle(); assertEquals("1234", store.pin) }
    @Test fun `incorrect pin clears and shows error`() = runTest(dispatcher) { val store = TestPinStore().apply { pin = "1234" }; val vm = PinViewModel(store); repeat(4) { vm.digit(9,false) {} }; advanceUntilIdle(); assertEquals("", vm.state.value.digits); assertTrue(vm.state.value.error) }
    @Test fun `correct pin invokes completion`() = runTest(dispatcher) { val store = TestPinStore().apply { pin = "1234" }; val vm = PinViewModel(store); var done = false; (1..4).forEach { vm.digit(it,false) { done = true } }; advanceUntilIdle(); assertTrue(done) }
    @Test fun `dashboard changes period`() = runTest(dispatcher) { val vm = AdultViewModel(TestProfileRepo(), TestUsage(), TestAlerts()); vm.period(DashboardPeriod.WEEK); advanceUntilIdle(); assertEquals(DashboardPeriod.WEEK, vm.state.value.period); assertEquals(900, vm.state.value.usage.currentMinutes) }
    @Test fun `adult dashboard keeps every application and derives readable remote names`() = runTest(dispatcher) {
        val profileRepo = TestProfileRepo()
        val apps = (1..12).map { index -> AppUsage("com.example.application$index", "com.example.application$index", 100 - index) }
        val usage = MutableOverviewUsage(UsageOverview(DailyUsage(120, null, 6), apps, UsageMetricStatus.MEASURED))
        val vm = AdultViewModel(profileRepo, usage, TestAlerts())

        profileRepo.profile.value = Profile("Niña", "🦊", 9, id = "profile-1")
        advanceUntilIdle()

        assertEquals(12, vm.state.value.appsTab.applications.size)
        assertEquals("Example Application 1", vm.state.value.appsTab.applications.first().name)
        assertEquals("com.example.application1", vm.state.value.appsTab.applications.first().packageName)
    }
    @Test fun `adult dashboard reloads applications when selected child profile changes`() = runTest(dispatcher) {
        val profileRepo = TestProfileRepo()
        val usage = MutableOverviewUsage(UsageOverview(DailyUsage(30, null, 2), listOf(AppUsage("YouTube", "com.google.android.youtube", 30)), UsageMetricStatus.MEASURED))
        val vm = AdultViewModel(profileRepo, usage, TestAlerts())

        profileRepo.profile.value = Profile("Primera", "🦊", 9, id = "profile-1")
        advanceUntilIdle()
        assertEquals("com.google.android.youtube", vm.state.value.appsTab.applications.single().packageName)

        usage.value = UsageOverview(DailyUsage(20, null, 1), listOf(AppUsage("com.whatsapp.w4b", "com.whatsapp.w4b", 20)), UsageMetricStatus.MEASURED)
        profileRepo.profile.value = Profile("Segunda", "🐼", 10, id = "profile-2")
        advanceUntilIdle()

        assertEquals("Whatsapp W4b", vm.state.value.appsTab.applications.single().name)
        assertEquals("com.whatsapp.w4b", vm.state.value.appsTab.applications.single().packageName)
    }
    @Test fun `adult dashboard preserves unavailable application state`() = runTest(dispatcher) {
        val profileRepo = TestProfileRepo()
        val usage = MutableOverviewUsage(UsageOverview(DailyUsage(null, null, null), null, UsageMetricStatus.PERMISSION_DENIED))
        val vm = AdultViewModel(profileRepo, usage, TestAlerts())

        profileRepo.profile.value = Profile("Niña", "🦊", 9, id = "profile-1")
        advanceUntilIdle()

        assertTrue(vm.state.value.appsTab.applications.isEmpty())
        assertEquals("El dispositivo infantil no otorgó permiso de acceso al uso.", vm.state.value.appsTab.informationMessage)
    }
    @Test fun `adult night card formats measured activity in child timezone`() = runTest(dispatcher) {
        val profileRepo = TestProfileRepo()
        val baseUsage = DailyUsage(currentMinutes = 60, sessionCount = 2, nightUsageMinutes = 20)
        val measuredUsage = baseUsage.copy(
            metricStatuses = baseUsage.metricStatuses.copy(lastNightActivityAt = UsageMetricStatus.MEASURED),
        )
        val usage = MutableOverviewUsage(
            UsageOverview(
                usage = measuredUsage,
                applications = emptyList(),
                applicationsStatus = UsageMetricStatus.MEASURED,
                lastNightActivityAt = Instant.parse("2026-10-17T02:42:00Z"),
                lastNightActivityTimezone = "America/Argentina/Buenos_Aires",
            ),
        )
        val vm = AdultViewModel(profileRepo, usage, TestAlerts())

        profileRepo.profile.value = Profile("Child", "fox", 9, id = "profile-1")
        advanceUntilIdle()

        assertEquals("23:42", vm.state.value.nightTab.lastActivity)
    }
    @Test fun `night activity formatter rejects absent or invalid source timezone`() {
        val timestamp = Instant.parse("2026-10-17T02:42:00Z")

        assertNull(formatLastNightActivity(null, "America/Argentina/Buenos_Aires"))
        assertNull(formatLastNightActivity(timestamp, null))
        assertNull(formatLastNightActivity(timestamp, "Invalid/Timezone"))
    }
    @Test fun `child state loads confirmed count for exact local day boundaries`() = runTest(dispatcher) {
        val repo = TestActivitySuggestions().apply { selectionCountValue = 4 }
        val date = LocalDate.of(2026, 10, 10)
        val zone = ZoneId.of("America/Argentina/Buenos_Aires")
        val vm = ChildViewModel(TestProfileRepo(), TestUsage(), repo, currentDate = { date }, zoneId = { zone })

        vm.refreshActivitySelectionCount()
        advanceUntilIdle()

        assertEquals(4, vm.state.value.chosenActivities)
        assertEquals(
            date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            repo.countRequests.single(),
        )
    }
    @Test fun `count failure keeps neutral state when no value was confirmed`() = runTest(dispatcher) {
        val repo = TestActivitySuggestions().apply { countFailure = java.io.IOException("offline") }
        val vm = ChildViewModel(TestProfileRepo(), TestUsage(), repo)

        vm.refreshActivitySelectionCount()
        advanceUntilIdle()

        assertNull(vm.state.value.chosenActivities)
    }
    @Test fun `count refresh failure keeps last confirmed value`() = runTest(dispatcher) {
        val repo = TestActivitySuggestions().apply { selectionCountValue = 5 }
        val vm = ChildViewModel(TestProfileRepo(), TestUsage(), repo)
        vm.refreshActivitySelectionCount()
        advanceUntilIdle()
        repo.countFailure = java.io.IOException("offline")

        vm.refreshActivitySelectionCount()
        advanceUntilIdle()

        assertEquals(5, vm.state.value.chosenActivities)
    }
    @Test fun `another idea rotates through backend activities and wraps to first`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage(), TestActivitySuggestions()); advanceUntilIdle(); val first = (vm.state.value.activityState as ActivityState.Suggested).activity; vm.showAnotherActivity(); assertEquals("activity-2", (vm.state.value.activityState as ActivityState.Suggested).activity.activityId); vm.showAnotherActivity(); assertEquals("activity-3", (vm.state.value.activityState as ActivityState.Suggested).activity.activityId); vm.showAnotherActivity(); assertEquals(first, (vm.state.value.activityState as ActivityState.Suggested).activity) }
    @Test fun `complete instructions remain accessible after start and repeated reading never duplicates selection`() = runTest(dispatcher) {
        val description = "Materiales:\n- Papel.\n- Lápices.\n\nPasos:\n" +
            (1..50).joinToString("\n") { "$it. Explorá una idea con calma y respetá esta consigna completa." }
        val repo = TestActivitySuggestions().apply {
            values = listOf(values.first().copy(id = "social_create_together_01", title = "Crear en compañía", description = description))
        }
        val vm = ChildViewModel(TestProfileRepo(), TestUsage(), repo)
        vm.refreshActivitySelectionCount()
        advanceUntilIdle()
        val suggested = (vm.state.value.activityState as ActivityState.Suggested).activity
        assertEquals(description, suggested.description)
        repeat(10) { assertEquals(description, (vm.state.value.activityState as ActivityState.Suggested).activity.description) }
        assertTrue(repo.selections.isEmpty())
        assertEquals(2, vm.state.value.chosenActivities)
        vm.startActivity()
        advanceUntilIdle()
        val running = vm.state.value
        repeat(20) {
            assertEquals(suggested, (vm.state.value.activityState as ActivityState.Running).activity)
            vm.startActivity() // A repeated tap while already running is not a new selection.
            vm.showAnotherActivity() // Cannot replace the running activity.
        }
        advanceUntilIdle()
        assertEquals(running, vm.state.value)
        assertEquals(1, repo.suggestionCalls)
        assertEquals(1, repo.selections.size)
        assertEquals("social_create_together_01", repo.selections.single().activityId)
        assertEquals(3, vm.state.value.chosenActivities)
        assertFalse(vm.state.value.showCompletionCelebration)
    }

    @Test fun `old plain descriptions and unchanged IDs remain available throughout activity lifecycle`() = runTest(dispatcher) {
        val repo = TestActivitySuggestions().apply {
            values = listOf(values.first().copy(id = "quiet_prepare_01", description = "Una consigna anterior sin materiales ni pasos separados."))
        }
        val vm = ChildViewModel(TestProfileRepo(), TestUsage(), repo)
        advanceUntilIdle()
        val before = (vm.state.value.activityState as ActivityState.Suggested).activity
        vm.startActivity()
        advanceUntilIdle()
        assertEquals(before, (vm.state.value.activityState as ActivityState.Running).activity)
        vm.finishActivity()
        advanceUntilIdle()
        assertEquals(before, (vm.state.value.activityState as ActivityState.Suggested).activity)
        assertEquals("quiet_prepare_01", repo.selections.single().activityId)
        assertEquals(1, repo.selections.size)
    }
    @Test fun `single backend activity remains selected when asking for another idea`() = runTest(dispatcher) { val repo = TestActivitySuggestions().apply { values = values.take(1) }; val vm = ChildViewModel(TestProfileRepo(), TestUsage(), repo); advanceUntilIdle(); val first = (vm.state.value.activityState as ActivityState.Suggested).activity; vm.showAnotherActivity(); assertEquals(first, (vm.state.value.activityState as ActivityState.Suggested).activity) }
    @Test fun `eligibility unknown maps to preferences required`() = runTest(dispatcher) { val repo = TestActivitySuggestions(emptyList()).apply { reason = "eligibility_unknown" }; val vm = ChildViewModel(TestProfileRepo(), TestUsage(), repo); advanceUntilIdle(); assertEquals(ActivityState.PreferencesRequired, vm.state.value.activityState) }
    @Test fun `no eligible activities maps to neutral unavailable state`() = runTest(dispatcher) { val repo = TestActivitySuggestions(emptyList()).apply { reason = "no_eligible_activities" }; val vm = ChildViewModel(TestProfileRepo(), TestUsage(), repo); advanceUntilIdle(); assertEquals(ActivityState.NoEligibleActivities, vm.state.value.activityState) }
    @Test fun `empty response without explicit reason is technical error`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage(), TestActivitySuggestions(emptyList())); advanceUntilIdle(); val error = vm.state.value.activityState as ActivityState.Error; assertEquals("No pudimos cargar una actividad en este momento. Intentá nuevamente.", error.message) }
    @Test fun `suggestion error shows retry state and retry can recover`() = runTest(dispatcher) { val repo = TestActivitySuggestions().apply { failure = java.io.IOException("offline") }; val vm = ChildViewModel(TestProfileRepo(), TestUsage(), repo); advanceUntilIdle(); val error = vm.state.value.activityState as ActivityState.Error; assertEquals("No pudimos cargar una actividad en este momento. Intentá nuevamente.", error.message); repo.failure = null; vm.loadActivitySuggestions(); advanceUntilIdle(); assertTrue(vm.state.value.activityState is ActivityState.Suggested) }
    @Test fun `successful preferences save event requests suggestions again`() = runTest(dispatcher) {
        val activityRepo = TestActivitySuggestions(emptyList()).apply { reason = "eligibility_unknown" }
        val childVm = ChildViewModel(TestProfileRepo(), TestUsage(), activityRepo)
        val preferencesVm = ChildPreferencesViewModel(TestPreferencesRepo(), TestProfileRepo(), demoMode = false)
        advanceUntilIdle()
        assertEquals(ActivityState.PreferencesRequired, childVm.state.value.activityState)

        var returnedToChild = false
        preferencesVm.save {
            returnedToChild = true
            childVm.onPreferencesUpdated()
        }
        advanceUntilIdle()

        assertTrue(returnedToChild)
        assertEquals(2, activityRepo.suggestionCalls)
    }
    @Test fun `preferences update replaces eligibility unknown with new backend activity`() = runTest(dispatcher) {
        val activityRepo = TestActivitySuggestions(emptyList()).apply { reason = "eligibility_unknown" }
        val childVm = ChildViewModel(TestProfileRepo(), TestUsage(), activityRepo)
        advanceUntilIdle()
        activityRepo.values = listOf(ActivitySuggestion("new-activity", "Nueva actividad", "Descripción", "CREATIVE", "LOW", false, false, false))

        childVm.onPreferencesUpdated()
        advanceUntilIdle()

        val suggested = childVm.state.value.activityState as ActivityState.Suggested
        assertEquals("new-activity", suggested.activity.activityId)
        assertEquals(2, activityRepo.suggestionCalls)
    }
    @Test fun `failed preferences save does not emit refresh event`() = runTest(dispatcher) {
        val activityRepo = TestActivitySuggestions(emptyList()).apply { reason = "eligibility_unknown" }
        val childVm = ChildViewModel(TestProfileRepo(), TestUsage(), activityRepo)
        val preferencesRepo = TestPreferencesRepo().apply { failure = java.io.IOException("offline") }
        val preferencesVm = ChildPreferencesViewModel(preferencesRepo, TestProfileRepo(), demoMode = false)
        advanceUntilIdle()

        var returnedToChild = false
        preferencesVm.save {
            returnedToChild = true
            childVm.onPreferencesUpdated()
        }
        advanceUntilIdle()

        assertFalse(returnedToChild)
        assertEquals(1, activityRepo.suggestionCalls)
        assertEquals(ActivityState.PreferencesRequired, childVm.state.value.activityState)
    }
    @Test fun `normal state observation does not duplicate suggestion requests`() = runTest(dispatcher) {
        val repo = TestActivitySuggestions()
        val vm = ChildViewModel(TestProfileRepo(), TestUsage(), repo)
        advanceUntilIdle()

        repeat(5) { vm.state.value }
        advanceUntilIdle()

        assertEquals(1, repo.suggestionCalls)
        assertTrue(vm.state.value.activityState is ActivityState.Suggested)
    }
    @Test fun `another idea does not register and start records currently visible activity with UUID and tap timestamp`() = runTest(dispatcher) {
        val repo = TestActivitySuggestions()
        val vm = ChildViewModel(TestProfileRepo(), TestUsage(), repo, nowEpochMillis = { 1_234_567_890L })
        advanceUntilIdle()

        vm.showAnotherActivity()
        assertTrue(repo.selections.isEmpty())
        vm.startActivity()
        advanceUntilIdle()

        val selection = repo.selections.single()
        java.util.UUID.fromString(selection.selectionId)
        assertEquals("offer-1", selection.offerId)
        assertEquals("activity-2", selection.activityId)
        assertEquals(1_234_567_890L, selection.selectedAtEpochMillis)
        assertEquals("activity-2", (vm.state.value.activityState as ActivityState.Running).activity.activityId)
    }
    @Test fun `selection retry reuses exact same id and payload`() = runTest(dispatcher) {
        val repo = TestActivitySuggestions().apply { registerFailure = java.io.IOException("offline") }
        var generatedIds = 0
        val vm = ChildViewModel(TestProfileRepo(), TestUsage(), repo, selectionId = { generatedIds++; "selection-fixed" }, nowEpochMillis = { 77L })
        vm.refreshActivitySelectionCount()
        advanceUntilIdle()

        vm.startActivity()
        advanceUntilIdle()
        assertTrue(vm.state.value.selectionRegistrationFailed)
        assertEquals(2, vm.state.value.chosenActivities)
        val original = repo.selections.single()
        repo.registerFailure = null
        vm.retryActivitySelection()
        advanceUntilIdle()

        assertEquals(1, generatedIds)
        assertEquals(listOf(original, original), repo.selections)
        assertFalse(vm.state.value.selectionRegistrationFailed)
    }
    @Test fun `null offer starts locally without generating or sending selection`() = runTest(dispatcher) {
        val repo = TestActivitySuggestions().apply { offerId = null }
        var generatedIds = 0
        val vm = ChildViewModel(TestProfileRepo(), TestUsage(), repo, selectionId = { generatedIds++; "unexpected" })
        advanceUntilIdle()

        vm.startActivity()
        advanceUntilIdle()

        assertTrue(vm.state.value.activityState is ActivityState.Running)
        assertEquals(0, generatedIds)
        assertTrue(repo.selections.isEmpty())
    }
    @Test fun `successful selection refreshes confirmed count from backend`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage(), TestActivitySuggestions()); vm.refreshActivitySelectionCount(); advanceUntilIdle(); val selected = (vm.state.value.activityState as ActivityState.Suggested).activity; vm.startActivity(); assertEquals(selected, (vm.state.value.activityState as ActivityState.Running).activity); assertEquals(2, vm.state.value.chosenActivities); advanceUntilIdle(); assertEquals(3, vm.state.value.chosenActivities) }
    @Test fun `unconfirmed selection does not increment count`() = runTest(dispatcher) { val repo = TestActivitySuggestions().apply { registerFailure = java.io.IOException("offline") }; val vm = ChildViewModel(TestProfileRepo(), TestUsage(), repo); vm.refreshActivitySelectionCount(); advanceUntilIdle(); assertEquals(2, vm.state.value.chosenActivities); vm.startActivity(); advanceUntilIdle(); assertEquals(2, vm.state.value.chosenActivities); assertTrue(vm.state.value.selectionRegistrationFailed); assertEquals(1, repo.countRequests.size) }
    @Test fun `finishing activity returns to suggestions without duplicating history`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage(), TestActivitySuggestions()); vm.refreshActivitySelectionCount(); advanceUntilIdle(); vm.startActivity(); advanceUntilIdle(); vm.finishActivity(); assertTrue(vm.state.value.activityState is ActivityState.Suggested); assertEquals(3, vm.state.value.chosenActivities) }
    @Test fun `real finish emits one celebration and repeated state reads do not retrigger it`() = runTest(dispatcher) {
        val vm = ChildViewModel(TestProfileRepo(), TestUsage(), TestActivitySuggestions())
        advanceUntilIdle()
        vm.startActivity()
        advanceUntilIdle()

        vm.finishActivity()
        assertTrue(vm.state.value.showCompletionCelebration)
        assertEquals(1L, vm.state.value.completionCelebrationId)
        repeat(5) { vm.state.value }
        vm.finishActivity()
        assertEquals(1L, vm.state.value.completionCelebrationId)

        advanceTimeBy(COMPLETION_CELEBRATION_MILLIS - 1)
        runCurrent()
        assertTrue(vm.state.value.showCompletionCelebration)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(vm.state.value.showCompletionCelebration)
    }
    @Test fun `consecutive completed activities restart celebration timeout safely`() = runTest(dispatcher) {
        val vm = ChildViewModel(TestProfileRepo(), TestUsage(), TestActivitySuggestions())
        advanceUntilIdle()
        vm.startActivity()
        advanceUntilIdle()
        vm.finishActivity()
        advanceTimeBy(1_000)
        runCurrent()

        vm.startActivity()
        vm.finishActivity()
        assertEquals(2L, vm.state.value.completionCelebrationId)
        advanceTimeBy(COMPLETION_CELEBRATION_MILLIS - 1_000)
        runCurrent()
        assertTrue(vm.state.value.showCompletionCelebration)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(vm.state.value.showCompletionCelebration)
    }
    @Test fun `preferences support multiple interests and demo persists locally`() = runTest(dispatcher) { val profile = TestProfileRepo().apply { this.profile.value = Profile("Sofía", "🦋", 8) }; val repo = TestPreferencesRepo(); val vm = ChildPreferencesViewModel(repo, profile, demoMode = true); advanceUntilIdle(); vm.toggleInterest(ChildInterest.MUSIC); vm.toggleInterest(ChildInterest.STORIES); vm.save(); advanceUntilIdle(); assertEquals(setOf(ChildInterest.MUSIC, ChildInterest.STORIES), repo.preferences.value.interests); assertEquals(0, repo.refreshCalls); assertEquals(0, repo.updateCalls); assertTrue(vm.state.value.saved) }
    @Test fun `explicit none clears selected considerations and remains answered`() = runTest(dispatcher) { val vm = ChildPreferencesViewModel(TestPreferencesRepo(), TestProfileRepo(), demoMode = true); advanceUntilIdle(); vm.toggleConsideration(ChildConsideration.VISUAL); vm.selectNoConsiderations(); assertTrue(vm.state.value.preferences.considerationsAnswered); assertTrue(vm.state.value.preferences.considerations.isEmpty()) }
    @Test fun `marking a consideration after none replaces none`() = runTest(dispatcher) { val vm = ChildPreferencesViewModel(TestPreferencesRepo(), TestProfileRepo(), demoMode = true); advanceUntilIdle(); vm.selectNoConsiderations(); vm.toggleConsideration(ChildConsideration.AUDITORY); assertTrue(vm.state.value.preferences.considerationsAnswered); assertEquals(setOf(ChildConsideration.AUDITORY), vm.state.value.preferences.considerations.keys) }
    @Test fun `omitting considerations differs from explicit none`() = runTest(dispatcher) { val vm = ChildPreferencesViewModel(TestPreferencesRepo(), TestProfileRepo(), demoMode = true); advanceUntilIdle(); vm.selectNoConsiderations(); vm.omitConsiderations(); assertFalse(vm.state.value.preferences.considerationsAnswered); assertTrue(vm.state.value.preferences.considerations.isEmpty()) }
    @Test fun `temporary consideration without date cannot save`() = runTest(dispatcher) { val repo = TestPreferencesRepo(); val vm = ChildPreferencesViewModel(repo, TestProfileRepo(), demoMode = false, currentDate = { LocalDate.of(2026, 10, 11) }); advanceUntilIdle(); vm.toggleConsideration(ChildConsideration.VISUAL); vm.considerationDuration(ChildConsideration.VISUAL, ConsiderationDuration.TEMPORARY); assertFalse(vm.state.value.valid); vm.save(); advanceUntilIdle(); assertEquals(0, repo.updateCalls) }
    @Test fun `temporary consideration with expired date cannot save`() = runTest(dispatcher) { val repo = TestPreferencesRepo(); val vm = ChildPreferencesViewModel(repo, TestProfileRepo(), demoMode = false, currentDate = { LocalDate.of(2026, 10, 11) }); advanceUntilIdle(); vm.toggleConsideration(ChildConsideration.VISUAL); vm.considerationDuration(ChildConsideration.VISUAL, ConsiderationDuration.TEMPORARY); vm.considerationValidUntil(ChildConsideration.VISUAL, LocalDate.of(2026, 10, 10)); assertFalse(vm.state.value.valid); vm.save(); advanceUntilIdle(); assertEquals(0, repo.updateCalls) }
    @Test fun `changing temporary consideration to permanent clears its date`() = runTest(dispatcher) { val vm = ChildPreferencesViewModel(TestPreferencesRepo(), TestProfileRepo(), demoMode = true); advanceUntilIdle(); vm.toggleConsideration(ChildConsideration.LOWER_BODY_MOBILITY); vm.considerationDuration(ChildConsideration.LOWER_BODY_MOBILITY, ConsiderationDuration.TEMPORARY); vm.considerationValidUntil(ChildConsideration.LOWER_BODY_MOBILITY, LocalDate.of(2026, 11, 20)); vm.considerationDuration(ChildConsideration.LOWER_BODY_MOBILITY, ConsiderationDuration.PERMANENT); assertNull(vm.state.value.preferences.considerations.getValue(ChildConsideration.LOWER_BODY_MOBILITY).validUntil) }
    @Test fun `unselecting consideration removes its duration and date state`() = runTest(dispatcher) { val vm = ChildPreferencesViewModel(TestPreferencesRepo(), TestProfileRepo(), demoMode = true); advanceUntilIdle(); vm.toggleConsideration(ChildConsideration.LOWER_BODY_MOBILITY); vm.considerationDuration(ChildConsideration.LOWER_BODY_MOBILITY, ConsiderationDuration.TEMPORARY); vm.considerationValidUntil(ChildConsideration.LOWER_BODY_MOBILITY, LocalDate.of(2026, 11, 20)); vm.toggleConsideration(ChildConsideration.LOWER_BODY_MOBILITY); assertFalse(ChildConsideration.LOWER_BODY_MOBILITY in vm.state.value.preferences.considerations) }
    @Test fun `upper and lower body considerations can be selected together`() = runTest(dispatcher) { val vm = ChildPreferencesViewModel(TestPreferencesRepo(), TestProfileRepo(), demoMode = true); advanceUntilIdle(); vm.toggleConsideration(ChildConsideration.UPPER_BODY_MOBILITY); vm.toggleConsideration(ChildConsideration.LOWER_BODY_MOBILITY); assertEquals(setOf(ChildConsideration.UPPER_BODY_MOBILITY, ChildConsideration.LOWER_BODY_MOBILITY), vm.state.value.preferences.considerations.keys) }
    @Test fun `restriction none never coexists with concrete restrictions`() = runTest(dispatcher) { val vm = ChildPreferencesViewModel(TestPreferencesRepo(ChildPreferences(activitiesToAvoid = setOf(ActivityRestriction.NONE))), TestProfileRepo(), demoMode = true); advanceUntilIdle(); vm.toggleRestriction(ActivityRestriction.SOUNDS); assertEquals(setOf(ActivityRestriction.SOUNDS), vm.state.value.preferences.activitiesToAvoid); vm.toggleRestriction(ActivityRestriction.SOUNDS); assertEquals(setOf(ActivityRestriction.NONE), vm.state.value.preferences.activitiesToAvoid); vm.toggleRestriction(ActivityRestriction.RUN_JUMP); vm.toggleRestriction(ActivityRestriction.NONE); assertEquals(setOf(ActivityRestriction.NONE), vm.state.value.preferences.activitiesToAvoid) }
    @Test fun `remote preferences load cache then refresh once`() = runTest(dispatcher) { val cached = ChildPreferences(interests = setOf(ChildInterest.STORIES)); val remote = ChildPreferences(interests = setOf(ChildInterest.MUSIC), activitiesToAvoid = setOf(ActivityRestriction.NONE)); val repo = TestPreferencesRepo(cached).apply { refreshedPreferences = remote }; val vm = ChildPreferencesViewModel(repo, TestProfileRepo(), demoMode = false); advanceUntilIdle(); assertEquals(1, repo.refreshCalls); assertEquals(remote, vm.state.value.preferences); assertTrue(vm.state.value.loaded); assertFalse(vm.state.value.refreshing) }
    @Test fun `remote considerations restore exact durations and dates`() = runTest(dispatcher) { val date = LocalDate.of(2026, 11, 20); val remote = ChildPreferences(considerationsAnswered = true, considerations = mapOf(ChildConsideration.VISUAL to ConsiderationPreference(), ChildConsideration.LOWER_BODY_MOBILITY to ConsiderationPreference(ConsiderationDuration.TEMPORARY, date))); val repo = TestPreferencesRepo().apply { refreshedPreferences = remote }; val vm = ChildPreferencesViewModel(repo, TestProfileRepo(), demoMode = false); advanceUntilIdle(); assertEquals(remote.considerations, vm.state.value.preferences.considerations); assertTrue(vm.state.value.preferences.considerationsAnswered) }
    @Test fun `edited considerations are sent with exact structured state`() = runTest(dispatcher) { val repo = TestPreferencesRepo(ChildPreferences(interests = setOf(ChildInterest.MUSIC))); val vm = ChildPreferencesViewModel(repo, TestProfileRepo(), demoMode = false, currentDate = { LocalDate.of(2026, 10, 11) }); advanceUntilIdle(); vm.toggleConsideration(ChildConsideration.UPPER_BODY_MOBILITY); vm.toggleConsideration(ChildConsideration.SOUND_SENSITIVITY); vm.considerationDuration(ChildConsideration.SOUND_SENSITIVITY, ConsiderationDuration.TEMPORARY); vm.considerationValidUntil(ChildConsideration.SOUND_SENSITIVITY, LocalDate.of(2026, 11, 20)); vm.save(); advanceUntilIdle(); val sent = repo.lastUpdateRequest!!; assertEquals(setOf(ChildInterest.MUSIC), sent.interests); assertEquals(ConsiderationPreference(), sent.considerations[ChildConsideration.UPPER_BODY_MOBILITY]); assertEquals(ConsiderationPreference(ConsiderationDuration.TEMPORARY, LocalDate.of(2026, 11, 20)), sent.considerations[ChildConsideration.SOUND_SENSITIVITY]) }
    @Test fun `remote preferences refresh failure keeps cache and exposes error`() = runTest(dispatcher) { val cached = ChildPreferences(interests = setOf(ChildInterest.STORIES)); val repo = TestPreferencesRepo(cached).apply { failure = java.io.IOException("offline") }; val vm = ChildPreferencesViewModel(repo, TestProfileRepo(), demoMode = false); advanceUntilIdle(); assertEquals(cached, vm.state.value.preferences); assertNotNull(vm.state.value.errorMessage); assertFalse(vm.state.value.refreshing) }
    @Test fun `remote preferences save succeeds through update`() = runTest(dispatcher) { val cached = ChildPreferences(interests = setOf(ChildInterest.STORIES)); val repo = TestPreferencesRepo(cached); val vm = ChildPreferencesViewModel(repo, TestProfileRepo(), demoMode = false); advanceUntilIdle(); vm.toggleInterest(ChildInterest.MUSIC); vm.save(); advanceUntilIdle(); assertEquals(1, repo.updateCalls); assertTrue(ChildInterest.MUSIC in repo.preferences.value.interests); assertTrue(vm.state.value.saved); assertFalse(vm.state.value.submitting); assertNull(vm.state.value.errorMessage) }
    @Test fun `remote preferences save failure keeps cache and edits for retry`() = runTest(dispatcher) { val cached = ChildPreferences(interests = setOf(ChildInterest.STORIES)); val repo = TestPreferencesRepo(cached); val vm = ChildPreferencesViewModel(repo, TestProfileRepo(), demoMode = false); advanceUntilIdle(); repo.failure = java.io.IOException("offline"); vm.toggleInterest(ChildInterest.MUSIC); vm.save(); advanceUntilIdle(); assertEquals(cached, repo.preferences.value); assertTrue(ChildInterest.MUSIC in vm.state.value.preferences.interests); assertFalse(vm.state.value.saved); assertFalse(vm.state.value.submitting); assertEquals("No pudimos guardar las preferencias. Intentá nuevamente.", vm.state.value.errorMessage) }
    @Test fun `preferences double submit is blocked`() = runTest(dispatcher) { val repo = TestPreferencesRepo(); val vm = ChildPreferencesViewModel(repo, TestProfileRepo(), demoMode = false); advanceUntilIdle(); vm.save(); vm.save(); advanceUntilIdle(); assertEquals(1, repo.updateCalls) }
    @Test fun `remote note over local limit is editable without truncation`() = runTest(dispatcher) { val note = "x".repeat(300); val repo = TestPreferencesRepo(ChildPreferences(additionalNotes = note)); val vm = ChildPreferencesViewModel(repo, TestProfileRepo(), demoMode = true); advanceUntilIdle(); vm.notes(note + "y"); assertEquals(301, vm.state.value.preferences.additionalNotes?.length) }
    @Test fun `profile schedule summary formats values without seconds`() { val profile = Profile("Sofía", "🦋", 8, schoolStart = "08:00:00", schoolEnd = "12:30:00", sleepStart = "22:00:00", sleepEnd = "07:00:00"); assertEquals("Escuela 08:00–12:30 · Sueño 22:00–07:00", profileScheduleSummary(profile)) }
    @Test fun `profile schedule summary handles missing and placeholder values`() { assertEquals("Sin configurar", profileScheduleSummary(Profile("Sofía", "🦋", 8))); assertEquals("Sin configurar", profileScheduleSummary(Profile("Sofía", "🦋", 8, schoolStart = "00:00", schoolEnd = "00:00", sleepStart = "00:00", sleepEnd = "00:00"))) }
    @Test fun `schedule editor loads profile and demo saves locally`() = runTest(dispatcher) { val repo = TestProfileRepo().apply { profile.value = Profile("Sofía", "🦋", 8, schoolStart = "08:00:00", schoolEnd = "12:30:00", sleepStart = "22:00:00", sleepEnd = "07:00:00") }; val vm = ProfileScheduleViewModel(repo, demoMode = true); advanceUntilIdle(); assertEquals("08:00", vm.state.value.profile?.schoolStart); vm.schoolStart("09:15"); vm.save(); advanceUntilIdle(); assertEquals("09:15", repo.profile.value?.schoolStart); assertEquals(0, repo.updateSettingsCalls); assertTrue(vm.state.value.saved) }
    @Test fun `schedule editor rejects invalid times`() = runTest(dispatcher) { val repo = TestProfileRepo().apply { profile.value = Profile("Sofía", "🦋", 8, schoolStart = "08:00", schoolEnd = "12:30", sleepStart = "22:00", sleepEnd = "07:00") }; val vm = ProfileScheduleViewModel(repo, demoMode = true); advanceUntilIdle(); vm.sleepStart("29:99"); assertFalse(vm.state.value.valid); vm.save(); advanceUntilIdle(); assertEquals("22:00", repo.profile.value?.sleepStart) }
    @Test fun `remote schedule editor refreshes settings once and normalizes display`() = runTest(dispatcher) { val repo = TestProfileRepo().apply { profile.value = Profile("Sofía", "🦋", 8, schoolStart = "08:00", schoolEnd = "12:30", sleepStart = "22:00", sleepEnd = "07:00", id = "profile-123"); refreshedSettings = profile.value!!.copy(schoolStart = "09:00:00", schoolEnd = "13:00:00", sleepStart = "23:00:00", sleepEnd = "08:00:00") }; val vm = ProfileScheduleViewModel(repo, demoMode = false); advanceUntilIdle(); assertEquals(1, repo.refreshSettingsCalls); assertEquals("09:00", vm.state.value.profile?.schoolStart); assertEquals("08:00", vm.state.value.profile?.sleepEnd) }
    @Test fun `remote schedule save uses settings operation and exposes success`() = runTest(dispatcher) { val initial = Profile("Sofía", "🦋", 8, schoolStart = "08:00", schoolEnd = "12:30", sleepStart = "22:00", sleepEnd = "07:00", id = "profile-123"); val repo = TestProfileRepo().apply { profile.value = initial }; val vm = ProfileScheduleViewModel(repo, demoMode = false); advanceUntilIdle(); vm.schoolStart("09:15"); vm.schoolEnd("13:45"); vm.save(); advanceUntilIdle(); assertEquals(1, repo.updateSettingsCalls); assertEquals("09:15", repo.profile.value?.schoolStart); assertEquals("profile-123", repo.profile.value?.id); assertTrue(vm.state.value.saved); assertFalse(vm.state.value.submitting) }
    @Test fun `remote schedule failure keeps cache and shows friendly error`() = runTest(dispatcher) { val initial = Profile("Sofía", "🦋", 8, schoolStart = "08:00", schoolEnd = "12:30", sleepStart = "22:00", sleepEnd = "07:00", id = "profile-123"); val repo = TestProfileRepo().apply { profile.value = initial; settingsFailure = java.io.IOException("offline") }; val vm = ProfileScheduleViewModel(repo, demoMode = false); advanceUntilIdle(); assertEquals(initial, repo.profile.value); vm.schoolStart("09:15"); vm.save(); advanceUntilIdle(); assertEquals(initial, repo.profile.value); assertFalse(vm.state.value.saved); assertFalse(vm.state.value.submitting); assertEquals("No pudimos guardar los horarios. Intentá nuevamente.", vm.state.value.errorMessage) }
    @Test fun `remote settings refresh failure keeps cached schedule`() = runTest(dispatcher) { val initial = Profile("Sofía", "🦋", 8, schoolStart = "08:00", schoolEnd = "12:30", sleepStart = "22:00", sleepEnd = "07:00", id = "profile-123"); val repo = TestProfileRepo().apply { profile.value = initial; settingsFailure = java.io.IOException("offline") }; val vm = ProfileScheduleViewModel(repo, demoMode = false); advanceUntilIdle(); assertEquals(initial, repo.profile.value); assertEquals("08:00", vm.state.value.profile?.schoolStart); assertNotNull(vm.state.value.errorMessage) }
    @Test fun `missing or rejected device JWT exposes relink state without deleting cache`() = runTest(dispatcher) { val initial = Profile("Sofía", "🦋", 8, schoolStart = "08:00", schoolEnd = "12:30", sleepStart = "22:00", sleepEnd = "07:00", id = "profile-123"); val repo = TestProfileRepo().apply { profile.value = initial; settingsFailure = DeviceAuthenticationRequiredException() }; val vm = ProfileScheduleViewModel(repo, demoMode = false); advanceUntilIdle(); assertEquals(initial, repo.profile.value); assertTrue(vm.state.value.deviceAuthenticationRequired); assertEquals("Este dispositivo necesita volver a vincularse.", vm.state.value.errorMessage) }
    @Test fun `schedule validation permits cross midnight and rejects equal bounds`() { val initial = Profile("Sofía", "🦋", 8, schoolStart = "08:00", schoolEnd = "12:30", sleepStart = "22:00", sleepEnd = "07:00"); assertTrue(ProfileScheduleUiState(initial).valid); assertFalse(ProfileScheduleUiState(initial.copy(schoolEnd = "08:00")).valid); assertFalse(ProfileScheduleUiState(initial.copy(sleepEnd = "22:00")).valid) }
}
