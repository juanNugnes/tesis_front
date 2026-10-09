package com.juan.habitos.ui.state

import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import com.juan.habitos.ui.adult.profileScheduleSummary

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
private class TestAlerts : AlertRepository { override suspend fun alerts() = emptyList<Alert>() }
private class TestActivitySuggestions(
    var values: List<ActivitySuggestion> = listOf(
        ActivitySuggestion("activity-1", "Primera idea", "Descripción 1", "CREATIVE", "LOW", false, false, false),
        ActivitySuggestion("activity-2", "Segunda idea", "Descripción 2", "MOVEMENT", "MEDIUM", false, false, false),
        ActivitySuggestion("activity-3", "Tercera idea", "Descripción 3", "STORIES", "LOW", true, false, false),
    ),
) : ActivitySuggestionRepository {
    var offerId: String? = "offer-1"
    var failure: Exception? = null
    var registerFailure: Exception? = null
    val selections = mutableListOf<ActivitySelection>()
    override suspend fun suggestions(): ActivitySuggestionOffer {
        failure?.let { throw it }
        return ActivitySuggestionOffer(offerId, values)
    }
    override suspend fun registerSelection(selection: ActivitySelection) {
        selections += selection
        registerFailure?.let { throw it }
    }
}
private class TestPreferencesRepo(initial: ChildPreferences = ChildPreferences()) : ChildPreferencesRepository {
    override val preferences = MutableStateFlow(initial)
    var refreshedPreferences: ChildPreferences? = null
    var updatedPreferences: ChildPreferences? = null
    var failure: Exception? = null
    var refreshCalls = 0
    var updateCalls = 0
    override suspend fun save(preferences: ChildPreferences) { this.preferences.value = preferences }
    override suspend fun refresh(): ChildPreferences {
        refreshCalls++
        failure?.let { throw it }
        return (refreshedPreferences ?: preferences.value).also { preferences.value = it }
    }
    override suspend fun update(preferences: ChildPreferences): ChildPreferences {
        updateCalls++
        failure?.let { throw it }
        return (updatedPreferences ?: preferences).also { this.preferences.value = it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelsTest {
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
    @Test fun `child state provides comparison periods and activity history`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage(), TestActivitySuggestions()); advanceUntilIdle(); val state = vm.state.value; assertEquals(150, state.habitualUsageMinutes); assertEquals(listOf(35, 100, 30), state.periodUsage.map { it.minutes }); assertEquals(2, state.chosenActivities) }
    @Test fun `another idea rotates through backend activities and wraps to first`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage(), TestActivitySuggestions()); advanceUntilIdle(); val first = (vm.state.value.activityState as ActivityState.Suggested).activity; vm.showAnotherActivity(); assertEquals("activity-2", (vm.state.value.activityState as ActivityState.Suggested).activity.activityId); vm.showAnotherActivity(); assertEquals("activity-3", (vm.state.value.activityState as ActivityState.Suggested).activity.activityId); vm.showAnotherActivity(); assertEquals(first, (vm.state.value.activityState as ActivityState.Suggested).activity) }
    @Test fun `single backend activity remains selected when asking for another idea`() = runTest(dispatcher) { val repo = TestActivitySuggestions().apply { values = values.take(1) }; val vm = ChildViewModel(TestProfileRepo(), TestUsage(), repo); advanceUntilIdle(); val first = (vm.state.value.activityState as ActivityState.Suggested).activity; vm.showAnotherActivity(); assertEquals(first, (vm.state.value.activityState as ActivityState.Suggested).activity) }
    @Test fun `empty backend response shows neutral empty state`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage(), TestActivitySuggestions(emptyList())); advanceUntilIdle(); assertEquals(ActivityState.Empty, vm.state.value.activityState) }
    @Test fun `suggestion error shows retry state and retry can recover`() = runTest(dispatcher) { val repo = TestActivitySuggestions().apply { failure = java.io.IOException("offline") }; val vm = ChildViewModel(TestProfileRepo(), TestUsage(), repo); advanceUntilIdle(); assertTrue(vm.state.value.activityState is ActivityState.Error); repo.failure = null; vm.loadActivitySuggestions(); advanceUntilIdle(); assertTrue(vm.state.value.activityState is ActivityState.Suggested) }
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
        advanceUntilIdle()

        vm.startActivity()
        advanceUntilIdle()
        assertTrue(vm.state.value.selectionRegistrationFailed)
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
    @Test fun `starting keeps selected activity and updates history`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage(), TestActivitySuggestions()); advanceUntilIdle(); val selected = (vm.state.value.activityState as ActivityState.Suggested).activity; vm.startActivity(); assertEquals(selected, (vm.state.value.activityState as ActivityState.Running).activity); assertEquals(3, vm.state.value.chosenActivities) }
    @Test fun `finishing activity returns to suggestions without duplicating history`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage(), TestActivitySuggestions()); advanceUntilIdle(); vm.startActivity(); vm.finishActivity(); assertTrue(vm.state.value.activityState is ActivityState.Suggested); assertEquals(3, vm.state.value.chosenActivities) }
    @Test fun `preferences support multiple interests and demo persists locally`() = runTest(dispatcher) { val profile = TestProfileRepo().apply { this.profile.value = Profile("Sofía", "🦋", 8) }; val repo = TestPreferencesRepo(); val vm = ChildPreferencesViewModel(repo, profile, demoMode = true); advanceUntilIdle(); vm.toggleInterest(ChildInterest.MUSIC); vm.toggleInterest(ChildInterest.STORIES); vm.save(); advanceUntilIdle(); assertEquals(setOf(ChildInterest.MUSIC, ChildInterest.STORIES), repo.preferences.value.interests); assertEquals(0, repo.refreshCalls); assertEquals(0, repo.updateCalls); assertTrue(vm.state.value.saved) }
    @Test fun `none preserves contextual fields and clears only temporary end date`() = runTest(dispatcher) { val profile = TestProfileRepo(); val vm = ChildPreferencesViewModel(TestPreferencesRepo(), profile, demoMode = true); advanceUntilIdle(); vm.adaptationType(AdaptationType.TEMPORARY); vm.toggleConsideration(ChildConsideration.MOVEMENT); vm.toggleRestriction(ActivityRestriction.RUN_JUMP); vm.notes("Molestia temporal"); vm.validUntil(LocalDate.now().plusWeeks(1)); vm.adaptationType(AdaptationType.NONE); val value = vm.state.value.preferences; assertEquals(setOf(ChildConsideration.MOVEMENT), value.considerations); assertEquals(setOf(ActivityRestriction.RUN_JUMP), value.activitiesToAvoid); assertEquals("Molestia temporal", value.additionalNotes); assertNull(value.validUntil) }
    @Test fun `permanent preference removes temporary end date`() = runTest(dispatcher) { val vm = ChildPreferencesViewModel(TestPreferencesRepo(), TestProfileRepo(), demoMode = true); advanceUntilIdle(); vm.adaptationType(AdaptationType.TEMPORARY); vm.validUntil(LocalDate.now().plusWeeks(1)); vm.adaptationType(AdaptationType.PERMANENT); assertNull(vm.state.value.preferences.validUntil) }
    @Test fun `restriction none never coexists with concrete restrictions`() = runTest(dispatcher) { val vm = ChildPreferencesViewModel(TestPreferencesRepo(ChildPreferences(activitiesToAvoid = setOf(ActivityRestriction.NONE))), TestProfileRepo(), demoMode = true); advanceUntilIdle(); vm.toggleRestriction(ActivityRestriction.SOUNDS); assertEquals(setOf(ActivityRestriction.SOUNDS), vm.state.value.preferences.activitiesToAvoid); vm.toggleRestriction(ActivityRestriction.SOUNDS); assertEquals(setOf(ActivityRestriction.NONE), vm.state.value.preferences.activitiesToAvoid); vm.toggleRestriction(ActivityRestriction.RUN_JUMP); vm.toggleRestriction(ActivityRestriction.NONE); assertEquals(setOf(ActivityRestriction.NONE), vm.state.value.preferences.activitiesToAvoid) }
    @Test fun `remote preferences load cache then refresh once`() = runTest(dispatcher) { val cached = ChildPreferences(interests = setOf(ChildInterest.STORIES)); val remote = ChildPreferences(interests = setOf(ChildInterest.MUSIC), activitiesToAvoid = setOf(ActivityRestriction.NONE)); val repo = TestPreferencesRepo(cached).apply { refreshedPreferences = remote }; val vm = ChildPreferencesViewModel(repo, TestProfileRepo(), demoMode = false); advanceUntilIdle(); assertEquals(1, repo.refreshCalls); assertEquals(remote, vm.state.value.preferences); assertTrue(vm.state.value.loaded); assertFalse(vm.state.value.refreshing) }
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
