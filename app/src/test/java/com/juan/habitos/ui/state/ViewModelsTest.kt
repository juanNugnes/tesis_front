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
    override suspend fun save(profile: Profile) { this.profile.value = profile }
    override suspend fun completeOnboarding() { onboardingComplete.value = true }
}
private class TestPinStore : PinStore { var pin = ""; override suspend fun save(pin: String) { this.pin = pin }; override suspend fun verify(pin: String) = pin == this.pin }
private class TestUsage : UsageRepository { override suspend fun daily(period: DashboardPeriod) = DailyUsage(if(period == DashboardPeriod.TODAY) 165 else 900, 180, 8); override suspend fun apps() = emptyList<AppUsage>(); override suspend fun recommendation() = Recommendation("","","") }
private class TestAlerts : AlertRepository { override suspend fun alerts() = emptyList<Alert>() }
private class TestPreferencesRepo : ChildPreferencesRepository { override val preferences = MutableStateFlow(ChildPreferences()); override suspend fun save(preferences: ChildPreferences) { this.preferences.value = preferences } }

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `profile requires valid name avatar age and school schedule`() { val vm = ProfileViewModel(TestProfileRepo()); assertFalse(vm.state.value.valid); vm.setName("Sofía"); vm.selectAvatar("🦊"); vm.selectAge(5); assertFalse(vm.state.value.valid); vm.selectAge(9); assertFalse(vm.state.value.valid); vm.toggleSchoolSchedule(SchoolSchedule.MORNING); assertTrue(vm.state.value.valid) }
    @Test fun `school schedules support multiple selections`() { val vm = ProfileViewModel(TestProfileRepo()); vm.toggleSchoolSchedule(SchoolSchedule.MORNING); vm.toggleSchoolSchedule(SchoolSchedule.AFTERNOON); assertEquals(setOf(SchoolSchedule.MORNING, SchoolSchedule.AFTERNOON), vm.state.value.schoolSchedules); vm.toggleSchoolSchedule(SchoolSchedule.MORNING); assertEquals(setOf(SchoolSchedule.AFTERNOON), vm.state.value.schoolSchedules) }
    @Test fun `profile including bedtime is persisted`() = runTest(dispatcher) { val repo = TestProfileRepo(); val vm = ProfileViewModel(repo); vm.setName(" Sofía "); vm.selectAvatar("🦊"); vm.selectAge(9); vm.toggleSchoolSchedule(SchoolSchedule.MORNING); vm.selectBedtime(BedtimeRange.FROM_21_TO_22); vm.save {}; advanceUntilIdle(); assertEquals(Profile("Sofía", "🦊", 9, setOf(SchoolSchedule.MORNING), BedtimeRange.FROM_21_TO_22), repo.profile.value) }
    @Test fun `pin creation accepts four digits and supports delete`() = runTest(dispatcher) { val store = TestPinStore(); val vm = PinViewModel(store); vm.digit(1,true) {}; vm.digit(2,true) {}; vm.delete(); assertEquals("1", vm.state.value.digits); vm.digit(2,true) {}; vm.digit(3,true) {}; vm.digit(4,true) {}; advanceUntilIdle(); assertEquals("1234", store.pin) }
    @Test fun `incorrect pin clears and shows error`() = runTest(dispatcher) { val store = TestPinStore().apply { pin = "1234" }; val vm = PinViewModel(store); repeat(4) { vm.digit(9,false) {} }; advanceUntilIdle(); assertEquals("", vm.state.value.digits); assertTrue(vm.state.value.error) }
    @Test fun `correct pin invokes completion`() = runTest(dispatcher) { val store = TestPinStore().apply { pin = "1234" }; val vm = PinViewModel(store); var done = false; (1..4).forEach { vm.digit(it,false) { done = true } }; advanceUntilIdle(); assertTrue(done) }
    @Test fun `dashboard changes period`() = runTest(dispatcher) { val vm = AdultViewModel(TestProfileRepo(), TestUsage(), TestAlerts()); vm.period(DashboardPeriod.WEEK); advanceUntilIdle(); assertEquals(DashboardPeriod.WEEK, vm.state.value.period); assertEquals(900, vm.state.value.usage.currentMinutes) }
    @Test fun `child state provides comparison periods and activity history`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage()); advanceUntilIdle(); val state = vm.state.value; assertEquals(150, state.habitualUsageMinutes); assertEquals(listOf(35, 100, 30), state.periodUsage.map { it.minutes }); assertEquals(2, state.chosenActivities) }
    @Test fun `another idea changes the selected activity`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage()); val first = (vm.state.value.activityState as ActivityState.Suggested).activity; vm.showAnotherActivity(); val second = (vm.state.value.activityState as ActivityState.Suggested).activity; assertNotEquals(first, second) }
    @Test fun `starting keeps selected activity and updates history`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage()); val selected = (vm.state.value.activityState as ActivityState.Suggested).activity; vm.startActivity(); assertEquals(selected, (vm.state.value.activityState as ActivityState.Running).activity); assertEquals(3, vm.state.value.chosenActivities) }
    @Test fun `finishing activity returns to suggestions without duplicating history`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage()); vm.startActivity(); vm.finishActivity(); assertTrue(vm.state.value.activityState is ActivityState.Suggested); assertEquals(3, vm.state.value.chosenActivities) }
    @Test fun `preferences support multiple interests and persist`() = runTest(dispatcher) { val profile = TestProfileRepo().apply { this.profile.value = Profile("Sofía", "🦋", 8) }; val repo = TestPreferencesRepo(); val vm = ChildPreferencesViewModel(repo, profile); advanceUntilIdle(); vm.toggleInterest(ChildInterest.MUSIC); vm.toggleInterest(ChildInterest.STORIES); vm.save(); advanceUntilIdle(); assertEquals(setOf(ChildInterest.MUSIC, ChildInterest.STORIES), repo.preferences.value.interests) }
    @Test fun `none clears contextual preference fields`() = runTest(dispatcher) { val profile = TestProfileRepo(); val vm = ChildPreferencesViewModel(TestPreferencesRepo(), profile); advanceUntilIdle(); vm.adaptationType(AdaptationType.TEMPORARY); vm.toggleConsideration(ChildConsideration.MOVEMENT); vm.toggleRestriction(ActivityRestriction.RUN_JUMP); vm.notes("Molestia temporal"); vm.validUntil(LocalDate.now().plusWeeks(1)); vm.adaptationType(AdaptationType.NONE); val value = vm.state.value.preferences; assertTrue(value.considerations.isEmpty()); assertTrue(value.activitiesToAvoid.isEmpty()); assertNull(value.additionalNotes); assertNull(value.validUntil) }
    @Test fun `permanent preference removes temporary end date`() = runTest(dispatcher) { val vm = ChildPreferencesViewModel(TestPreferencesRepo(), TestProfileRepo()); advanceUntilIdle(); vm.adaptationType(AdaptationType.TEMPORARY); vm.validUntil(LocalDate.now().plusWeeks(1)); vm.adaptationType(AdaptationType.PERMANENT); assertNull(vm.state.value.preferences.validUntil) }
    @Test fun `profile schedule summary formats values without seconds`() { val profile = Profile("Sofía", "🦋", 8, schoolStart = "08:00:00", schoolEnd = "12:30:00", sleepStart = "22:00:00", sleepEnd = "07:00:00"); assertEquals("Escuela 08:00–12:30 · Sueño 22:00–07:00", profileScheduleSummary(profile)) }
    @Test fun `profile schedule summary handles missing and placeholder values`() { assertEquals("Sin configurar", profileScheduleSummary(Profile("Sofía", "🦋", 8))); assertEquals("Sin configurar", profileScheduleSummary(Profile("Sofía", "🦋", 8, schoolStart = "00:00", schoolEnd = "00:00", sleepStart = "00:00", sleepEnd = "00:00"))) }
    @Test fun `schedule editor loads profile and saves through repository`() = runTest(dispatcher) { val repo = TestProfileRepo().apply { profile.value = Profile("Sofía", "🦋", 8, schoolStart = "08:00:00", schoolEnd = "12:30:00", sleepStart = "22:00:00", sleepEnd = "07:00:00") }; val vm = ProfileScheduleViewModel(repo); advanceUntilIdle(); assertEquals("08:00", vm.state.value.profile?.schoolStart); vm.schoolStart("09:15"); vm.save(); advanceUntilIdle(); assertEquals("09:15", repo.profile.value?.schoolStart); assertTrue(vm.state.value.saved) }
    @Test fun `schedule editor rejects invalid times`() = runTest(dispatcher) { val repo = TestProfileRepo().apply { profile.value = Profile("Sofía", "🦋", 8, schoolStart = "08:00", schoolEnd = "12:30", sleepStart = "22:00", sleepEnd = "07:00") }; val vm = ProfileScheduleViewModel(repo); advanceUntilIdle(); vm.sleepStart("29:99"); assertFalse(vm.state.value.valid); vm.save(); advanceUntilIdle(); assertEquals("22:00", repo.profile.value?.sleepStart) }
}
