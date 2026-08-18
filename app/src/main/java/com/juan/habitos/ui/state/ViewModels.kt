package com.juan.habitos.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ProfileUiState(val name: String = "", val avatar: String? = null, val age: Int? = null, val schoolSchedules: Set<SchoolSchedule> = emptySet(), val bedtime: BedtimeRange? = null) { val valid get() = name.trim().isNotEmpty() && avatar != null && age in 6..12 && schoolSchedules.isNotEmpty() }
class ProfileViewModel(private val repository: ProfileRepository) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState()); val state = _state.asStateFlow()
    fun setName(value: String) { _state.update { it.copy(name = value) } }
    fun selectAvatar(value: String) { _state.update { it.copy(avatar = value) } }
    fun selectAge(value: Int) { if (value in 6..12) _state.update { it.copy(age = value) } }
    fun toggleSchoolSchedule(value: SchoolSchedule) { _state.update { it.copy(schoolSchedules = if (value in it.schoolSchedules) it.schoolSchedules - value else it.schoolSchedules + value) } }
    fun selectBedtime(value: BedtimeRange) { _state.update { it.copy(bedtime = if (it.bedtime == value) null else value) } }
    fun save(onSaved: () -> Unit) { val s = state.value; if (s.valid) viewModelScope.launch { repository.save(Profile(s.name.trim(), s.avatar!!, s.age!!, s.schoolSchedules, s.bedtime)); onSaved() } }
}

data class PinUiState(val digits: String = "", val error: Boolean = false)
class PinViewModel(private val pinStore: PinStore) : ViewModel() {
    private val _state = MutableStateFlow(PinUiState()); val state = _state.asStateFlow()
    fun digit(value: Int, create: Boolean, onComplete: () -> Unit) { if (_state.value.digits.length < 4) { val pin = _state.value.digits + value; _state.value = PinUiState(pin); if (pin.length == 4) viewModelScope.launch { if (create) { pinStore.save(pin); onComplete() } else if (pinStore.verify(pin)) onComplete() else _state.value = PinUiState(error = true) } } }
    fun delete() { _state.update { it.copy(digits = it.digits.dropLast(1), error = false) } }
    fun clear() { _state.value = PinUiState() }
}

enum class DayPeriod { MORNING, AFTERNOON, NIGHT }
data class DayPeriodUsageUiModel(val period: DayPeriod, val minutes: Int)
data class PauseRecommendationUiModel(val emoji: String, val text: String, val durationMinutes: Int)
sealed interface PauseState {
    data class Suggested(val recommendation: PauseRecommendationUiModel) : PauseState
    data class Running(val recommendation: PauseRecommendationUiModel, val remainingSeconds: Int) : PauseState
    data class Completed(val recommendation: PauseRecommendationUiModel) : PauseState
}

private val pauseRecommendations = listOf(
    PauseRecommendationUiModel("🧘", "Estirá el cuerpo", 5),
    PauseRecommendationUiModel("🚶", "Caminá un poquito", 5),
    PauseRecommendationUiModel("💧", "Tomá agua y descansá la vista", 5),
    PauseRecommendationUiModel("🪟", "Mirá por la ventana y descansá los ojos", 5),
    PauseRecommendationUiModel("🙆", "Mové los brazos y hombros", 5),
)
data class ChildHomeUiState(
    val profile: Profile = Profile("", "🦊", 9),
    val usage: DailyUsage = DailyUsage(0, 180, 0),
    val habitualUsageMinutes: Int = 150,
    val periodUsage: List<DayPeriodUsageUiModel> = listOf(
        DayPeriodUsageUiModel(DayPeriod.MORNING, 35),
        DayPeriodUsageUiModel(DayPeriod.AFTERNOON, 100),
        DayPeriodUsageUiModel(DayPeriod.NIGHT, 30),
    ),
    val apps: List<AppUsage> = emptyList(),
    val recommendation: Recommendation? = null,
    val pauseState: PauseState = PauseState.Suggested(pauseRecommendations.first()),
    val completedBreaks: Int = 2,
    val suggestedBreaks: Int = 3,
)
class ChildViewModel(profileRepo: ProfileRepository, private val usageRepo: UsageRepository, private val timerTickMillis: Long = 1_000L) : ViewModel() {
    private val _state = MutableStateFlow(ChildHomeUiState()); val state = _state.asStateFlow()
    private var pauseJob: Job? = null
    init { viewModelScope.launch { profileRepo.profile.filterNotNull().collect { profile -> _state.update { it.copy(profile = profile) } } }; viewModelScope.launch { _state.update { it.copy(usage = usageRepo.daily(), apps = usageRepo.apps(), recommendation = usageRepo.recommendation()) } } }
    fun showAnotherPauseIdea() {
        val current = (state.value.pauseState as? PauseState.Suggested)?.recommendation ?: return
        val nextIndex = (pauseRecommendations.indexOf(current) + 1).mod(pauseRecommendations.size)
        _state.update { it.copy(pauseState = PauseState.Suggested(pauseRecommendations[nextIndex])) }
    }
    fun startPause() {
        val recommendation = (state.value.pauseState as? PauseState.Suggested)?.recommendation ?: return
        pauseJob?.cancel()
        pauseJob = viewModelScope.launch {
            var remaining = recommendation.durationMinutes * 60
            _state.update { it.copy(pauseState = PauseState.Running(recommendation, remaining)) }
            while (remaining > 0) {
                delay(timerTickMillis)
                remaining--
                _state.update { it.copy(pauseState = PauseState.Running(recommendation, remaining)) }
            }
            // TODO: validate a future pause with real Android device-usage signals when available.
            _state.update { it.copy(pauseState = PauseState.Completed(recommendation), completedBreaks = it.completedBreaks + 1) }
            delay(2_000L)
            _state.update { it.copy(pauseState = PauseState.Suggested(recommendation)) }
        }
    }
    fun cancelPause() {
        val recommendation = (state.value.pauseState as? PauseState.Running)?.recommendation ?: return
        pauseJob?.cancel()
        pauseJob = null
        _state.update { it.copy(pauseState = PauseState.Suggested(recommendation)) }
    }
}

data class AdultDashboardUiState(val profile: Profile = Profile("", "🦊", 9), val period: DashboardPeriod = DashboardPeriod.TODAY, val usage: DailyUsage = DailyUsage(0,180,0), val apps: List<AppUsage> = emptyList(), val alerts: List<Alert> = emptyList())
class AdultViewModel(profileRepo: ProfileRepository, private val usageRepo: UsageRepository, private val alertRepo: AlertRepository) : ViewModel() {
    private val _state = MutableStateFlow(AdultDashboardUiState()); val state = _state.asStateFlow()
    init { viewModelScope.launch { profileRepo.profile.filterNotNull().collect { p -> _state.update { it.copy(profile = p) } } }; load() }
    fun period(value: DashboardPeriod) { _state.update { it.copy(period = value) }; load() }
    private fun load() = viewModelScope.launch { _state.update { it.copy(usage = usageRepo.daily(it.period), apps = usageRepo.apps(), alerts = alertRepo.alerts()) } }
}
