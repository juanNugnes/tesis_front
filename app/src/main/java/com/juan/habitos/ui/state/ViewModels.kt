package com.juan.habitos.ui.state

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juan.habitos.data.remote.CreateProfileRequest
import com.juan.habitos.data.remote.HabitsApi
import com.juan.habitos.data.remote.ProfileSettingsRequest
import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ProfileUiState(val name: String = "", val avatar: String? = null, val age: Int? = null, val schoolSchedules: Set<SchoolSchedule> = emptySet(), val bedtime: BedtimeRange? = null) { val valid get() = name.trim().isNotEmpty() && avatar != null && age in 6..12 && schoolSchedules.isNotEmpty() }
class ProfileViewModel(private val repository: ProfileRepository, private val habitsApi: HabitsApi? = null) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState()); val state = _state.asStateFlow()
    fun setName(value: String) { _state.update { it.copy(name = value) } }
    fun selectAvatar(value: String) { _state.update { it.copy(avatar = value) } }
    fun selectAge(value: Int) { if (value in 6..12) _state.update { it.copy(age = value) } }
    fun toggleSchoolSchedule(value: SchoolSchedule) { _state.update { it.copy(schoolSchedules = if (value in it.schoolSchedules) it.schoolSchedules - value else it.schoolSchedules + value) } }
    fun selectBedtime(value: BedtimeRange) { _state.update { it.copy(bedtime = if (it.bedtime == value) null else value) } }
    fun save(onSaved: () -> Unit) { val s = state.value; if (s.valid) viewModelScope.launch { repository.save(Profile(s.name.trim(), s.avatar!!, s.age!!, s.schoolSchedules, s.bedtime)); onSaved() } }
    fun createTestProfile(onSuccess: () -> Unit) {
        val api = habitsApi ?: run { Log.e("PROFILE_API", "HabitsApi no está configurada"); return }
        viewModelScope.launch {
            val request = CreateProfileRequest(
                alias = "test android",
                age = 6,
                avatar = "fox",
                settings = ProfileSettingsRequest(
                    school_start = "08:00:00",
                    school_end = "12:30:00",
                    sleep_start = "22:00:00",
                    sleep_end = "07:00:00",
                ),
            )
            try {
                val response = api.createProfile(request)
                if (response.code() == 200 || response.code() == 201) {
                    Log.i("PROFILE_API", "Perfil creado correctamente. HTTP ${response.code()}")
                    onSuccess()
                } else {
                    Log.e("PROFILE_API", "Error al crear perfil. HTTP ${response.code()}")
                }
            } catch (exception: Exception) {
                Log.e("PROFILE_API", "No se pudo conectar con el backend", exception)
            }
        }
    }
}

data class ProfileScheduleUiState(val profile: Profile? = null, val saved: Boolean = false) {
    val valid: Boolean get() = profile?.let { listOf(it.schoolStart, it.schoolEnd, it.sleepStart, it.sleepEnd).all(::isValidScheduleTime) } == true
}
class ProfileScheduleViewModel(private val repository: ProfileRepository) : ViewModel() {
    private val _state = MutableStateFlow(ProfileScheduleUiState()); val state = _state.asStateFlow()
    init { viewModelScope.launch { repository.profile.first()?.let { profile -> _state.value = ProfileScheduleUiState(profile.copy(schoolStart = displayScheduleTime(profile.schoolStart), schoolEnd = displayScheduleTime(profile.schoolEnd), sleepStart = displayScheduleTime(profile.sleepStart), sleepEnd = displayScheduleTime(profile.sleepEnd))) } } }
    fun schoolStart(value: String) = update { copy(schoolStart = value.take(5)) }
    fun schoolEnd(value: String) = update { copy(schoolEnd = value.take(5)) }
    fun sleepStart(value: String) = update { copy(sleepStart = value.take(5)) }
    fun sleepEnd(value: String) = update { copy(sleepEnd = value.take(5)) }
    fun save() { val profile = state.value.profile ?: return; if (!state.value.valid) return; viewModelScope.launch { repository.save(profile); _state.update { it.copy(saved = true) } } }
    fun consumeSaved() { _state.update { it.copy(saved = false) } }
    private fun update(transform: Profile.() -> Profile) { _state.update { current -> current.copy(profile = current.profile?.transform(), saved = false) } }
}
private fun displayScheduleTime(value: String?): String? = value?.trim()?.split(":")?.takeIf { it.size >= 2 }?.let { "${it[0]}:${it[1]}" }
private fun isValidScheduleTime(value: String?): Boolean { val parts = value?.split(":") ?: return false; if (parts.size != 2 || parts.any { it.length != 2 }) return false; val hour = parts[0].toIntOrNull() ?: return false; val minute = parts[1].toIntOrNull() ?: return false; return hour in 0..23 && minute in 0..59 }

data class PinUiState(val digits: String = "", val error: Boolean = false)
class PinViewModel(private val pinStore: PinStore) : ViewModel() {
    private val _state = MutableStateFlow(PinUiState()); val state = _state.asStateFlow()
    fun digit(value: Int, create: Boolean, onComplete: () -> Unit) { if (_state.value.digits.length < 4) { val pin = _state.value.digits + value; _state.value = PinUiState(pin); if (pin.length == 4) viewModelScope.launch { if (create) { pinStore.save(pin); onComplete() } else if (pinStore.verify(pin)) onComplete() else _state.value = PinUiState(error = true) } } }
    fun delete() { _state.update { it.copy(digits = it.digits.dropLast(1), error = false) } }
    fun clear() { _state.value = PinUiState() }
}

data class ChildPreferencesUiState(
    val childName: String = "",
    val preferences: ChildPreferences = ChildPreferences(),
    val loaded: Boolean = false,
    val saved: Boolean = false,
)
class ChildPreferencesViewModel(private val repository: ChildPreferencesRepository, profileRepository: ProfileRepository) : ViewModel() {
    private val _state = MutableStateFlow(ChildPreferencesUiState()); val state = _state.asStateFlow()
    init {
        viewModelScope.launch { profileRepository.profile.filterNotNull().collect { profile -> _state.update { it.copy(childName = profile.name) } } }
        viewModelScope.launch { repository.preferences.first().let { preferences -> _state.update { it.copy(preferences = preferences, loaded = true) } } }
    }
    fun toggleInterest(value: ChildInterest) = update { copy(interests = interests.toggle(value)) }
    fun adaptationType(value: AdaptationType) = update { copy(adaptationType = value, considerations = if (value == AdaptationType.NONE) emptySet() else considerations, activitiesToAvoid = if (value == AdaptationType.NONE) emptySet() else activitiesToAvoid, additionalNotes = if (value == AdaptationType.NONE) null else additionalNotes, validUntil = if (value == AdaptationType.TEMPORARY) validUntil else null) }
    fun toggleConsideration(value: ChildConsideration) = update { copy(considerations = considerations.toggle(value)) }
    fun toggleRestriction(value: ActivityRestriction) = update { copy(activitiesToAvoid = if (value == ActivityRestriction.NONE) setOf(value) else activitiesToAvoid.minus(ActivityRestriction.NONE).toggle(value)) }
    fun notes(value: String) = update { copy(additionalNotes = value.take(240)) }
    fun validUntil(date: LocalDate?) = update { copy(validUntil = date) }
    fun save(onSaved: () -> Unit = {}) = viewModelScope.launch { repository.save(state.value.preferences); _state.update { it.copy(saved = true) }; onSaved() }
    fun consumeSaved() { _state.update { it.copy(saved = false) } }
    private fun update(transform: ChildPreferences.() -> ChildPreferences) { _state.update { it.copy(preferences = it.preferences.transform(), saved = false) } }
    private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
}

enum class DayPeriod { MORNING, AFTERNOON, NIGHT }
data class DayPeriodUsageUiModel(val period: DayPeriod, val minutes: Int)
data class SuggestedActivityUiModel(val emoji: String, val text: String, val suggestedDurationMinutes: Int? = null)
sealed interface ActivityState {
    data class Suggested(val activity: SuggestedActivityUiModel) : ActivityState
    data class Running(val activity: SuggestedActivityUiModel) : ActivityState
}

private val suggestedActivities = listOf(
    SuggestedActivityUiModel("🎵", "Bailá una canción"),
    SuggestedActivityUiModel("🤸", "Armá una secuencia de movimientos"),
    SuggestedActivityUiModel("⚽", "Jugá con una pelota"),
    SuggestedActivityUiModel("🎭", "Inventá un personaje"),
    SuggestedActivityUiModel("🦄", "Creá un animal imaginario"),
    SuggestedActivityUiModel("🎨", "Dibujá algo que te guste"),
    SuggestedActivityUiModel("✍️", "Inventá una historia"),
    SuggestedActivityUiModel("📖", "Elegí algo para leer"),
    SuggestedActivityUiModel("📚", "Volvé a una historia que te guste"),
    SuggestedActivityUiModel("🧩", "Jugá con algo que tengas cerca"),
    SuggestedActivityUiModel("🧱", "Construí algo"),
    SuggestedActivityUiModel("🧸", "Creá una historia con tus juguetes"),
    SuggestedActivityUiModel("👫", "Jugá con alguien"),
    SuggestedActivityUiModel("💬", "Contá algo de tu día"),
    SuggestedActivityUiModel("🤝", "Ayudá con algo"),
    SuggestedActivityUiModel("🎧", "Escuchá una historia"),
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
    val activityState: ActivityState = ActivityState.Suggested(suggestedActivities[5]),
    val chosenActivities: Int = 2,
)
class ChildViewModel(profileRepo: ProfileRepository, private val usageRepo: UsageRepository) : ViewModel() {
    private val _state = MutableStateFlow(ChildHomeUiState()); val state = _state.asStateFlow()
    init { viewModelScope.launch { profileRepo.profile.filterNotNull().collect { profile -> _state.update { it.copy(profile = profile) } } }; viewModelScope.launch { _state.update { it.copy(usage = usageRepo.daily(), apps = usageRepo.apps()) } } }
    fun showAnotherActivity() {
        val current = (state.value.activityState as? ActivityState.Suggested)?.activity ?: return
        val nextIndex = (suggestedActivities.indexOf(current) + 1).mod(suggestedActivities.size)
        _state.update { it.copy(activityState = ActivityState.Suggested(suggestedActivities[nextIndex])) }
    }
    fun startActivity() {
        val activity = (state.value.activityState as? ActivityState.Suggested)?.activity ?: return
        _state.update { it.copy(activityState = ActivityState.Running(activity), chosenActivities = it.chosenActivities + 1) }
    }
    fun finishActivity() {
        val activity = (state.value.activityState as? ActivityState.Running)?.activity ?: return
        _state.update { it.copy(activityState = ActivityState.Suggested(activity)) }
    }
}

data class AdultAppUsageUiModel(val name: String, val icon: String, val minutes: Int)
data class CategoryUsageUiModel(val name: String, val percentage: Int)
data class AdultAppsTabUiState(
    val applications: List<AdultAppUsageUiModel> = listOf(
        AdultAppUsageUiModel("YouTube", "▶️", 80),
        AdultAppUsageUiModel("Roblox", "🎮", 46),
        AdultAppUsageUiModel("Khan Academy", "📚", 31),
        AdultAppUsageUiModel("WhatsApp", "💬", 22),
    ),
    val categories: List<CategoryUsageUiModel> = listOf(
        CategoryUsageUiModel("Entretenimiento", 42),
        CategoryUsageUiModel("Juegos", 28),
        CategoryUsageUiModel("Social", 17),
        CategoryUsageUiModel("Educación", 13),
    ),
    val comparisonApp: String = "YouTube",
    val comparisonTodayMinutes: Int = 80,
    val comparisonHabitualMinutes: Int = 45,
)
data class NightUsageDayUiModel(val label: String, val minutes: Int)
data class AdultNightTabUiState(
    val bedtime: String = "22:00",
    val usageAfterBedtimeMinutes: Int = 38,
    val lastActivity: String = "23:42",
    val activeNightsThisWeek: Int = 3,
    val recentDays: List<NightUsageDayUiModel> = listOf(
        NightUsageDayUiModel("Hoy", 38),
        NightUsageDayUiModel("Ayer", 12),
        NightUsageDayUiModel("Lunes", 0),
        NightUsageDayUiModel("Domingo", 44),
    ),
)
data class AnalysisFactorUiModel(val icon: String, val title: String, val description: String)
data class BehaviorComparisonUiModel(val label: String, val value: String)
data class AdultAttentionTabUiState(
    val factors: List<AnalysisFactorUiModel> = listOf(
        AnalysisFactorUiModel("📈", "Tiempo de uso", "Se observó un aumento respecto de períodos anteriores."),
        AnalysisFactorUiModel("📱", "Frecuencia", "Se registraron más sesiones respecto de su comportamiento habitual."),
        AnalysisFactorUiModel("🌙", "Horario", "Se registró actividad posterior al horario habitual de acostarse."),
        AnalysisFactorUiModel("🎮", "Categorías", "La distribución entre categorías se mantuvo similar a períodos anteriores."),
    ),
    val comparisons: List<BehaviorComparisonUiModel> = listOf(
        BehaviorComparisonUiModel("Tiempo de uso", "+28 %"),
        BehaviorComparisonUiModel("Sesiones", "+18 %"),
        BehaviorComparisonUiModel("Uso nocturno", "Mayor"),
        BehaviorComparisonUiModel("Entretenimiento", "Similar"),
    ),
)
data class AdultDashboardUiState(
    val profile: Profile = Profile("", "🦊", 9),
    val period: DashboardPeriod = DashboardPeriod.TODAY,
    val usage: DailyUsage = DailyUsage(0,180,0),
    val apps: List<AppUsage> = emptyList(),
    val alerts: List<Alert> = emptyList(),
    val appsTab: AdultAppsTabUiState = AdultAppsTabUiState(),
    val nightTab: AdultNightTabUiState = AdultNightTabUiState(),
    val attentionTab: AdultAttentionTabUiState = AdultAttentionTabUiState(),
)
class AdultViewModel(profileRepo: ProfileRepository, private val usageRepo: UsageRepository, private val alertRepo: AlertRepository) : ViewModel() {
    private val _state = MutableStateFlow(AdultDashboardUiState()); val state = _state.asStateFlow()
    init { viewModelScope.launch { profileRepo.profile.filterNotNull().collect { p -> _state.update { it.copy(profile = p) } } }; load() }
    fun period(value: DashboardPeriod) { _state.update { it.copy(period = value) }; load() }
    private fun load() = viewModelScope.launch { _state.update { it.copy(usage = usageRepo.daily(it.period), apps = usageRepo.apps(), alerts = alertRepo.alerts()) } }
}
