package com.juan.habitos.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class ProfileUiState(
    val name: String = "",
    val avatar: String? = null,
    val age: Int? = null,
    val schoolSchedules: Set<SchoolSchedule> = emptySet(),
    val sleepStart: String = "22:00",
    val sleepEnd: String = "07:00",
    val submitting: Boolean = false,
    val profileCreatedDevicePending: Boolean = false,
    val errorMessage: String? = null,
) {
    val sleepScheduleValid get() = isValidScheduleTime(sleepStart) && isValidScheduleTime(sleepEnd) && sleepStart != sleepEnd
    val valid get() = name.trim().isNotEmpty() && avatar != null && age in 6..12 && schoolSchedules.isNotEmpty() && sleepScheduleValid
}
class ProfileViewModel(
    private val repository: ProfileRepository,
    private val deviceRepository: DeviceRepository? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState()); val state = _state.asStateFlow()
    init {
        if (deviceRepository != null) viewModelScope.launch {
            repository.profile.first()?.takeIf { it.id != null }?.let { profile ->
                _state.update {
                    it.copy(
                        name = profile.name,
                        avatar = profile.avatar,
                        age = profile.age,
                        schoolSchedules = profile.schoolSchedules,
                        sleepStart = profile.sleepStart ?: it.sleepStart,
                        sleepEnd = profile.sleepEnd ?: it.sleepEnd,
                        profileCreatedDevicePending = true,
                    )
                }
            }
        }
    }
    fun setName(value: String) { _state.update { it.copy(name = value, errorMessage = null) } }
    fun selectAvatar(value: String) { _state.update { it.copy(avatar = value, errorMessage = null) } }
    fun selectAge(value: Int) { if (value in 6..12) _state.update { it.copy(age = value, errorMessage = null) } }
    fun toggleSchoolSchedule(value: SchoolSchedule) { _state.update { it.copy(schoolSchedules = if (value in it.schoolSchedules) it.schoolSchedules - value else it.schoolSchedules + value, errorMessage = null) } }
    fun sleepStart(value: String) { _state.update { it.copy(sleepStart = value, errorMessage = null) } }
    fun sleepEnd(value: String) { _state.update { it.copy(sleepEnd = value, errorMessage = null) } }
    fun save(onSaved: () -> Unit) = persist(remote = false, onSaved)
    fun create(onCreated: () -> Unit) = persist(remote = true, onCreated)

    private fun persist(remote: Boolean, onSuccess: () -> Unit) {
        val current = state.value
        if (!current.valid || current.submitting) return
        val profile = Profile(
            name = current.name.trim(),
            avatar = current.avatar!!,
            age = current.age!!,
            schoolSchedules = current.schoolSchedules,
            sleepStart = current.sleepStart,
            sleepEnd = current.sleepEnd,
        )
        _state.update { it.copy(submitting = true, errorMessage = null) }
        viewModelScope.launch {
            var profileCreated = false
            try {
                if (remote) {
                    val pending = deviceRepository?.let { repository.profile.first()?.takeIf { cached -> cached.id != null } }
                    val created = pending ?: repository.create(profile)
                    profileCreated = true
                    val profileId = requireNotNull(created.id) { "Backend profile response did not include id" }
                    deviceRepository?.registerDevice(profileId)
                } else {
                    repository.save(profile)
                }
                _state.update { it.copy(submitting = false, profileCreatedDevicePending = false) }
                onSuccess()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val devicePending = remote && deviceRepository != null && (profileCreated || repository.profile.first()?.id != null)
                val message = when {
                    devicePending && exception is DeviceLinkException && exception.reason == DeviceLinkFailureReason.BOOTSTRAP_EXPIRED ->
                        "La autorización para registrar este dispositivo venció. El perfil quedó guardado."
                    devicePending && exception is DeviceLinkException && exception.reason == DeviceLinkFailureReason.INSTALLATION_CONFLICT ->
                        "No pudimos registrar este dispositivo para el perfil."
                    devicePending ->
                        "El perfil se creó, pero no pudimos registrar este dispositivo. Revisá la conexión e intentá nuevamente."
                    else -> "No pudimos guardar el perfil. Revisá la conexión e intentá nuevamente."
                }
                _state.update { it.copy(submitting = false, profileCreatedDevicePending = devicePending, errorMessage = message) }
            }
        }
    }
}

data class ProfileScheduleUiState(
    val profile: Profile? = null,
    val saved: Boolean = false,
    val submitting: Boolean = false,
    val errorMessage: String? = null,
    val deviceAuthenticationRequired: Boolean = false,
    val adultAuthenticationRequired: Boolean = false,
) {
    val valid: Boolean get() = profile?.let {
        listOf(it.schoolStart, it.schoolEnd, it.sleepStart, it.sleepEnd).all(::isValidScheduleTime) &&
            it.schoolStart != it.schoolEnd && it.sleepStart != it.sleepEnd
    } == true
}
class ProfileScheduleViewModel(
    private val repository: ProfileRepository,
    private val demoMode: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileScheduleUiState()); val state = _state.asStateFlow()
    init {
        viewModelScope.launch {
            val cached = repository.profile.first()
            cached?.let(::showProfile)
            if (!demoMode && cached?.id != null) {
                try {
                    repository.refreshSettings()?.let(::showProfile)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    _state.update { it.copy(
                        errorMessage = when (exception) { is DeviceAuthenticationRequiredException -> "Este dispositivo necesita volver a vincularse."; is AdultAuthenticationRequiredException -> "La sesión adulta venció. Iniciá sesión nuevamente."; else -> "No pudimos actualizar los horarios. Podés seguir usando los datos guardados." },
                        deviceAuthenticationRequired = exception is DeviceAuthenticationRequiredException,
                        adultAuthenticationRequired = exception is AdultAuthenticationRequiredException,
                    ) }
                }
            }
        }
    }
    fun schoolStart(value: String) = update { copy(schoolStart = value.take(5)) }
    fun schoolEnd(value: String) = update { copy(schoolEnd = value.take(5)) }
    fun sleepStart(value: String) = update { copy(sleepStart = value.take(5)) }
    fun sleepEnd(value: String) = update { copy(sleepEnd = value.take(5)) }
    fun save() {
        val current = state.value
        val profile = current.profile ?: return
        if (!current.valid || current.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, saved = false, errorMessage = null) }
            try {
                val saved = if (demoMode) {
                    repository.save(profile)
                    profile
                } else {
                    repository.updateSettings(profile)
                }
                showProfile(saved, saved = true)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.update { it.copy(
                    submitting = false,
                    saved = false,
                    errorMessage = when (exception) { is DeviceAuthenticationRequiredException -> "Este dispositivo necesita volver a vincularse."; is AdultAuthenticationRequiredException -> "La sesión adulta venció. Iniciá sesión nuevamente."; else -> "No pudimos guardar los horarios. Intentá nuevamente." },
                    deviceAuthenticationRequired = exception is DeviceAuthenticationRequiredException,
                    adultAuthenticationRequired = exception is AdultAuthenticationRequiredException,
                ) }
            }
        }
    }
    fun consumeSaved() { _state.update { it.copy(saved = false) } }
    private fun showProfile(profile: Profile, saved: Boolean = false) {
        _state.value = ProfileScheduleUiState(
            profile = profile.copy(
                schoolStart = displayScheduleTime(profile.schoolStart),
                schoolEnd = displayScheduleTime(profile.schoolEnd),
                sleepStart = displayScheduleTime(profile.sleepStart),
                sleepEnd = displayScheduleTime(profile.sleepEnd),
            ),
            saved = saved,
        )
    }
    private fun update(transform: Profile.() -> Profile) { _state.update { current -> current.copy(profile = current.profile?.transform(), saved = false, errorMessage = null) } }
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

class DeviceModeViewModel(private val repository: DeviceSessionRepository) : ViewModel() {
    fun select(mode: DeviceMode, onSelected: () -> Unit) = viewModelScope.launch { repository.setMode(mode); onSelected() }
}

data class AdultAuthUiState(val email: String = "", val code: String = "", val emailError: Boolean = false, val codeError: Boolean = false, val loading: Boolean = false, val devCode: String? = null, val errorMessage: String? = null)
class AdultAuthViewModel(private val authRepository: AuthRepository, private val sessionRepository: DeviceSessionRepository, private val showDevCode: Boolean = false) : ViewModel() {
    private val _state = MutableStateFlow(AdultAuthUiState()); val state = _state.asStateFlow()
    fun email(value: String) { _state.update { it.copy(email = value.trim(), emailError = false, errorMessage = null) } }
    fun code(value: String) { _state.update { it.copy(code = value.filter(Char::isDigit).take(6), codeError = false, errorMessage = null) } }
    fun requestCode(onSent: () -> Unit) { val email = state.value.email; if (!isValidEmail(email)) { _state.update { it.copy(emailError = true) }; return }; viewModelScope.launch {
        _state.update { it.copy(loading = true, errorMessage = null) }
        try {
            val challenge = authRepository.requestVerificationCode(email)
            _state.update { it.copy(loading = false, devCode = challenge.devCode.takeIf { showDevCode }) }
            onSent()
        } catch (exception: CancellationException) { throw exception }
        catch (_: Exception) { _state.update { it.copy(loading = false, errorMessage = "No pudimos enviar el código. Intentá nuevamente.") } }
    } }
    fun verify(onVerified: () -> Unit) = viewModelScope.launch {
        val current = state.value
        if (!current.code.matches(Regex("\\d{6}"))) { _state.update { it.copy(codeError = true) }; return@launch }
        _state.update { it.copy(loading = true, codeError = false, errorMessage = null) }
        try {
            if (authRepository.verifyCode(current.email, current.code)) {
                sessionRepository.authenticateAdult(current.email)
                _state.update { it.copy(loading = false) }
                onVerified()
            } else _state.update { it.copy(loading = false, codeError = true) }
        } catch (exception: CancellationException) { throw exception }
        catch (_: Exception) { _state.update { it.copy(loading = false, errorMessage = "No pudimos verificar el código. Intentá nuevamente.") } }
    }
    companion object { fun isValidEmail(value: String) = value.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) }
}

data class LinkDeviceUiState(val code: String = "", val profiles: List<Profile> = emptyList(), val foundProfile: Profile? = null, val errorMessage: String? = null, val loading: Boolean = false, val loaded: Boolean = false, val authenticationRequired: Boolean = false)
class LinkDeviceViewModel(private val repository: LinkRepository) : ViewModel() {
    private val _state = MutableStateFlow(LinkDeviceUiState()); val state = _state.asStateFlow()
    init { refreshProfiles() }
    fun code(value: String) { _state.update { it.copy(code = value.filter(Char::isDigit).take(6), errorMessage = null, foundProfile = null) } }
    fun refreshProfiles() = viewModelScope.launch {
        _state.update { it.copy(loading = true, errorMessage = null, authenticationRequired = false) }
        try { _state.update { it.copy(profiles = repository.profiles(), loading = false, loaded = true) } }
        catch (exception: CancellationException) { throw exception }
        catch (exception: AdultAuthenticationRequiredException) { _state.update { it.copy(loading = false, loaded = true, authenticationRequired = true, errorMessage = "La sesión adulta venció. Iniciá sesión nuevamente.") } }
        catch (exception: AdultAuthorizationException) { _state.update { it.copy(loading = false, loaded = true, errorMessage = "No tenés permiso para consultar estos perfiles.") } }
        catch (_: Exception) { _state.update { it.copy(loading = false, loaded = true, errorMessage = "No pudimos cargar los perfiles vinculados.") } }
    }
    fun find() = viewModelScope.launch {
        if (!state.value.code.matches(Regex("\\d{6}"))) return@launch
        _state.update { it.copy(loading = true, errorMessage = null) }
        try {
            val profile = repository.findProfile(state.value.code)
            _state.update { it.copy(foundProfile = profile, errorMessage = if (profile == null) "El código no es válido o venció. Generá uno nuevo e intentá nuevamente." else null, loading = false) }
        } catch (exception: CancellationException) { throw exception }
        catch (exception: AdultAuthenticationRequiredException) { _state.update { it.copy(loading = false, authenticationRequired = true, errorMessage = "La sesión adulta venció. Iniciá sesión nuevamente.") } }
        catch (exception: AdultLinkException) { _state.update { it.copy(loading = false, errorMessage = if (exception.reason == AdultLinkFailureReason.REVOKED_LINK) "No pudimos volver a vincular este perfil automáticamente." else "El código no es válido o venció. Generá uno nuevo e intentá nuevamente.") } }
        catch (exception: AdultAuthorizationException) { _state.update { it.copy(loading = false, errorMessage = "No tenés permiso para vincular este perfil.") } }
        catch (_: Exception) { _state.update { it.copy(loading = false, errorMessage = "No pudimos vincular el perfil. Intentá nuevamente.") } }
    }
    fun confirm(onLinked: () -> Unit) { state.value.foundProfile?.let { open(it, onLinked) } }
    fun open(profile: Profile, onLinked: () -> Unit) = viewModelScope.launch {
        try { repository.link(profile); onLinked() }
        catch (exception: CancellationException) { throw exception }
        catch (_: Exception) { _state.update { it.copy(errorMessage = "No pudimos abrir el perfil.") } }
    }
}

data class AdultProfileLinkCodeUiState(val loading: Boolean = true, val code: String? = null, val validForMinutes: Long? = null, val errorMessage: String? = null)
class AdultProfileLinkCodeViewModel(
    private val repository: DeviceRepository,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {
    private val _state = MutableStateFlow(AdultProfileLinkCodeUiState()); val state = _state.asStateFlow()
    init { generate() }
    fun generate() = viewModelScope.launch {
        _state.value = AdultProfileLinkCodeUiState(loading = true)
        try {
            val link = repository.createAdultProfileLinkCode()
            val minutes = runCatching { Duration.between(now(), Instant.parse(link.expiresAt)).toMinutes().coerceAtLeast(1) }.getOrNull()
            _state.value = AdultProfileLinkCodeUiState(loading = false, code = link.code, validForMinutes = minutes)
        } catch (exception: CancellationException) { throw exception }
        catch (exception: DeviceAuthenticationRequiredException) { _state.value = AdultProfileLinkCodeUiState(loading = false, errorMessage = "Este dispositivo necesita volver a vincularse.") }
        catch (_: Exception) { _state.value = AdultProfileLinkCodeUiState(loading = false, errorMessage = "No pudimos generar el código. Intentá nuevamente.") }
    }
}

data class ChildPreferencesUiState(
    val childName: String = "",
    val preferences: ChildPreferences = ChildPreferences(),
    val today: LocalDate = LocalDate.now(),
    val loaded: Boolean = false,
    val saved: Boolean = false,
    val refreshing: Boolean = false,
    val submitting: Boolean = false,
    val errorMessage: String? = null,
    val dirty: Boolean = false,
    val deviceAuthenticationRequired: Boolean = false,
    val adultAuthenticationRequired: Boolean = false,
) {
    val valid: Boolean get() = preferences.considerations.values.all { preference ->
        preference.durationType != ConsiderationDuration.TEMPORARY ||
            preference.validUntil?.let { !it.isBefore(today) } == true
    }
}
class ChildPreferencesViewModel(
    private val repository: ChildPreferencesRepository,
    profileRepository: ProfileRepository,
    private val demoMode: Boolean,
    private val currentDate: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    private val _state = MutableStateFlow(ChildPreferencesUiState(today = currentDate())); val state = _state.asStateFlow()
    init {
        viewModelScope.launch { profileRepository.profile.filterNotNull().collect { profile -> _state.update { it.copy(childName = profile.name) } } }
        viewModelScope.launch {
            val cached = repository.preferences.first()
            _state.update { it.copy(preferences = cached, loaded = true, dirty = false) }
            if (!demoMode) {
                _state.update { it.copy(refreshing = true, errorMessage = null) }
                try {
                    val remote = repository.refresh()
                    _state.update { current -> current.copy(preferences = if (current.dirty) current.preferences else remote, refreshing = false) }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    _state.update { it.copy(
                        refreshing = false,
                        errorMessage = when (exception) { is DeviceAuthenticationRequiredException -> "Este dispositivo necesita volver a vincularse."; is AdultAuthenticationRequiredException -> "La sesión adulta venció. Iniciá sesión nuevamente."; else -> "No pudimos actualizar las preferencias. Podés seguir usando los datos guardados." },
                        deviceAuthenticationRequired = exception is DeviceAuthenticationRequiredException,
                        adultAuthenticationRequired = exception is AdultAuthenticationRequiredException,
                    ) }
                }
            }
        }
    }
    fun toggleInterest(value: ChildInterest) = update { copy(interests = interests.toggle(value)) }
    fun omitConsiderations() = update { copy(considerationsAnswered = false, considerations = emptyMap()) }
    fun selectNoConsiderations() = update { copy(considerationsAnswered = true, considerations = emptyMap()) }
    fun toggleConsideration(value: ChildConsideration) = update {
        copy(
            considerationsAnswered = true,
            considerations = if (value in considerations) considerations - value
            else considerations + (value to ConsiderationPreference()),
        )
    }
    fun considerationDuration(value: ChildConsideration, duration: ConsiderationDuration) = update {
        val current = considerations[value] ?: return@update this
        copy(considerations = considerations + (value to current.copy(
            durationType = duration,
            validUntil = current.validUntil.takeIf { duration == ConsiderationDuration.TEMPORARY },
        )))
    }
    fun considerationValidUntil(value: ChildConsideration, date: LocalDate) = update {
        val current = considerations[value] ?: return@update this
        copy(considerations = considerations + (value to current.copy(validUntil = date)))
    }
    fun toggleRestriction(value: ActivityRestriction) = update {
        val selected = if (value == ActivityRestriction.NONE) {
            setOf(value)
        } else {
            activitiesToAvoid.minus(ActivityRestriction.NONE).toggle(value).ifEmpty { setOf(ActivityRestriction.NONE) }
        }
        copy(activitiesToAvoid = selected)
    }
    fun notes(value: String) = update {
        val limit = if (additionalNotes.orEmpty().length > 240) 500 else 240
        copy(additionalNotes = value.take(limit))
    }
    fun save(onSaved: () -> Unit = {}) {
        val current = state.value
        if (!current.loaded || !current.valid || current.submitting || current.refreshing) return
        _state.update { it.copy(submitting = true, saved = false, errorMessage = null) }
        viewModelScope.launch {
            try {
                val saved = if (demoMode) {
                    repository.save(current.preferences)
                    current.preferences
                } else {
                    repository.update(current.preferences)
                }
                _state.update { it.copy(preferences = saved, submitting = false, saved = true, dirty = false) }
                onSaved()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.update { it.copy(
                    submitting = false,
                    saved = false,
                    errorMessage = when (exception) { is DeviceAuthenticationRequiredException -> "Este dispositivo necesita volver a vincularse."; is AdultAuthenticationRequiredException -> "La sesión adulta venció. Iniciá sesión nuevamente."; else -> "No pudimos guardar las preferencias. Intentá nuevamente." },
                    deviceAuthenticationRequired = exception is DeviceAuthenticationRequiredException,
                    adultAuthenticationRequired = exception is AdultAuthenticationRequiredException,
                ) }
            }
        }
    }
    fun consumeSaved() { _state.update { it.copy(saved = false) } }
    private fun update(transform: ChildPreferences.() -> ChildPreferences) { _state.update { it.copy(preferences = it.preferences.transform(), saved = false, errorMessage = null, dirty = true) } }
    private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
}

enum class DayPeriod { MORNING, AFTERNOON, NIGHT }
data class DayPeriodUsageUiModel(val period: DayPeriod, val minutes: Int)
data class SuggestedActivityUiModel(
    val activityId: String,
    val emoji: String,
    val title: String,
    val description: String,
)
sealed interface ActivityState {
    data object Loading : ActivityState
    data object PreferencesRequired : ActivityState
    data object NoEligibleActivities : ActivityState
    data class Error(val message: String) : ActivityState
    data class Suggested(val activity: SuggestedActivityUiModel) : ActivityState
    data class Running(val activity: SuggestedActivityUiModel) : ActivityState
}
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
    val activityState: ActivityState = ActivityState.Loading,
    val chosenActivities: Int? = null,
    val selectionRegistrationFailed: Boolean = false,
)
class ChildViewModel(
    profileRepo: ProfileRepository,
    private val usageRepo: UsageRepository,
    private val activitySuggestionRepository: ActivitySuggestionRepository,
    private val selectionId: () -> String = { UUID.randomUUID().toString() },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val currentDate: () -> LocalDate = LocalDate::now,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    private val _state = MutableStateFlow(ChildHomeUiState()); val state = _state.asStateFlow()
    private var suggestedActivities: List<SuggestedActivityUiModel> = emptyList()
    private var currentOfferId: String? = null
    private var pendingSelection: ActivitySelection? = null
    private var selectionRegistrationInFlight = false
    private var selectionCountRequestId = 0
    init {
        viewModelScope.launch { profileRepo.profile.filterNotNull().collect { profile -> _state.update { it.copy(profile = profile) } } }
        viewModelScope.launch { _state.update { it.copy(usage = usageRepo.daily(), apps = usageRepo.apps()) } }
        loadActivitySuggestions()
    }
    fun loadActivitySuggestions() = viewModelScope.launch {
        _state.update { it.copy(activityState = ActivityState.Loading) }
        try {
            val offer = activitySuggestionRepository.suggestions()
            currentOfferId = offer.offerId
            suggestedActivities = offer.activities.map { it.toUiModel() }
            _state.update {
                it.copy(
                    activityState = suggestedActivities.firstOrNull()?.let(ActivityState::Suggested)
                        ?: when (offer.reason) {
                            ELIGIBILITY_UNKNOWN -> ActivityState.PreferencesRequired
                            NO_ELIGIBLE_ACTIVITIES -> ActivityState.NoEligibleActivities
                            else -> ActivityState.Error(TECHNICAL_ACTIVITY_MESSAGE)
                        }
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            currentOfferId = null
            suggestedActivities = emptyList()
            _state.update {
                it.copy(activityState = ActivityState.Error(TECHNICAL_ACTIVITY_MESSAGE))
            }
        }
    }
    fun showAnotherActivity() {
        val current = (state.value.activityState as? ActivityState.Suggested)?.activity ?: return
        if (suggestedActivities.isEmpty()) return
        val nextIndex = (suggestedActivities.indexOf(current) + 1).mod(suggestedActivities.size)
        _state.update { it.copy(activityState = ActivityState.Suggested(suggestedActivities[nextIndex])) }
    }
    fun startActivity() {
        val activity = (state.value.activityState as? ActivityState.Suggested)?.activity ?: return
        pendingSelection = currentOfferId?.let { offerId ->
            ActivitySelection(
                selectionId = selectionId(),
                offerId = offerId,
                activityId = activity.activityId,
                selectedAtEpochMillis = nowEpochMillis(),
            )
        }
        _state.update {
            it.copy(
                activityState = ActivityState.Running(activity),
                selectionRegistrationFailed = false,
            )
        }
        sendPendingSelection()
    }
    fun finishActivity() {
        val activity = (state.value.activityState as? ActivityState.Running)?.activity ?: return
        _state.update { it.copy(activityState = ActivityState.Suggested(activity)) }
    }
    fun retryActivitySelection() = sendPendingSelection()

    private fun sendPendingSelection() {
        val selection = pendingSelection ?: return
        if (selectionRegistrationInFlight) return
        selectionRegistrationInFlight = true
        viewModelScope.launch {
            try {
                activitySuggestionRepository.registerSelection(selection)
                if (pendingSelection == selection) {
                    pendingSelection = null
                    _state.update { it.copy(selectionRegistrationFailed = false) }
                    refreshActivitySelectionCount()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (pendingSelection == selection) {
                    _state.update { it.copy(selectionRegistrationFailed = true) }
                }
            } finally {
                selectionRegistrationInFlight = false
            }
        }
    }

    fun refreshActivitySelectionCount() = viewModelScope.launch {
        val requestId = ++selectionCountRequestId
        val today = currentDate()
        val zone = zoneId()
        val dayStartEpochMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEndEpochMillis = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        try {
            val count = activitySuggestionRepository.selectionCount(dayStartEpochMillis, dayEndEpochMillis)
            if (requestId == selectionCountRequestId) {
                _state.update { it.copy(chosenActivities = count) }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // Keep the last backend-confirmed value, or null when none has been loaded yet.
        }
    }

    private fun ActivitySuggestion.toUiModel() = SuggestedActivityUiModel(
        activityId = id,
        emoji = "✨",
        title = title,
        description = description,
    )

    private companion object {
        const val ELIGIBILITY_UNKNOWN = "eligibility_unknown"
        const val NO_ELIGIBLE_ACTIVITIES = "no_eligible_activities"
        const val TECHNICAL_ACTIVITY_MESSAGE = "No pudimos cargar una actividad en este momento. Intentá nuevamente."
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
