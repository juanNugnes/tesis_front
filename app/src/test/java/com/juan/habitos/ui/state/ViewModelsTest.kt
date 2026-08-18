package com.juan.habitos.ui.state

import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

private class TestProfileRepo : ProfileRepository {
    override val profile = MutableStateFlow<Profile?>(null)
    override val onboardingComplete = MutableStateFlow(false)
    override suspend fun save(profile: Profile) { this.profile.value = profile }
    override suspend fun completeOnboarding() { onboardingComplete.value = true }
}
private class TestPinStore : PinStore { var pin = ""; override suspend fun save(pin: String) { this.pin = pin }; override suspend fun verify(pin: String) = pin == this.pin }
private class TestUsage : UsageRepository { override suspend fun daily(period: DashboardPeriod) = DailyUsage(if(period == DashboardPeriod.TODAY) 165 else 900, 180, 8); override suspend fun apps() = emptyList<AppUsage>(); override suspend fun recommendation() = Recommendation("","","") }
private class TestAlerts : AlertRepository { override suspend fun alerts() = emptyList<Alert>() }

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
    @Test fun `child state provides comparison periods and breaks`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage()); advanceUntilIdle(); val state = vm.state.value; assertEquals(150, state.habitualUsageMinutes); assertEquals(listOf(35, 100, 30), state.periodUsage.map { it.minutes }); assertEquals(2, state.completedBreaks); assertEquals(3, state.suggestedBreaks) }
    @Test fun `another idea changes the pause recommendation`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage()); val first = (vm.state.value.pauseState as PauseState.Suggested).recommendation; vm.showAnotherPauseIdea(); val second = (vm.state.value.pauseState as PauseState.Suggested).recommendation; assertNotEquals(first, second) }
    @Test fun `cancelling a pause does not increment completed breaks`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage()); vm.startPause(); runCurrent(); vm.cancelPause(); assertTrue(vm.state.value.pauseState is PauseState.Suggested); assertEquals(2, vm.state.value.completedBreaks) }
    @Test fun `completed pause increments breaks only when timer reaches zero`() = runTest(dispatcher) { val vm = ChildViewModel(TestProfileRepo(), TestUsage()); vm.startPause(); runCurrent(); assertEquals(2, vm.state.value.completedBreaks); advanceTimeBy(300_000); runCurrent(); assertTrue(vm.state.value.pauseState is PauseState.Completed); assertEquals(3, vm.state.value.completedBreaks) }
}
