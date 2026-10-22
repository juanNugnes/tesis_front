package com.juan.habitos.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ParentalScreenTimeUiState(
    val profileId: String, val age: Int,
    val selections: ParentalScreenTimeSelections = ParentalScreenTimeSelections(),
    val record: ParentalScreenTimeRecord? = null,
    val loaded: Boolean = false, val loading: Boolean = true, val saving: Boolean = false,
    val touched: Boolean = false, val needsReload: Boolean = false,
    val error: String? = null, val message: String? = null,
    val authenticationRequired: Boolean = false, val forbidden: Boolean = false,
) {
    val editable: Boolean get() = !loading && !saving
    val hasChanges: Boolean get() = if (record == null) touched else record.answers != selections.answers()
    val canSave: Boolean get() = editable && loaded && !needsReload && !authenticationRequired && !forbidden && hasChanges
}

/** Independent persistence: never invokes preferences, UsageStats, inference or navigation. */
class ParentalScreenTimeViewModel(
    private val repository: ParentalScreenTimeRepository,
    private val profileId: String,
    age: Int,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ParentalScreenTimeUiState(profileId, age))
    val state = mutableState.asStateFlow()
    private var inFlight = false
    private var unconfirmed: ParentalScreenTimeAnswers? = null
    init { refresh() }
    fun age(value: Int) { mutableState.update { it.copy(age = value) } }

    fun sex(value: ParentalSexChoice?) = edit { copy(sex = value) }
    fun weekday(value: ParentalTimeChoice?) = edit { copy(weekday = value) }
    fun weekend(value: ParentalTimeChoice?) = edit { copy(weekend = value) }
    private fun edit(change: ParentalScreenTimeSelections.() -> ParentalScreenTimeSelections) {
        if (!state.value.editable) return
        mutableState.update { it.copy(selections = it.selections.change(), touched = true, message = null) }
    }

    /** After any failed POST only a GET is retried first: the response may already be persisted. */
    fun refresh() {
        if (inFlight) return
        inFlight = true
        val before = state.value
        mutableState.update { it.copy(loading = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val latest = repository.latest(profileId)
                val confirmed = unconfirmed?.let { latest != null && latest.answers == it && latest.age == state.value.age } == true
                val keepEdits = before.touched && (!confirmed || before.selections.answers() != latest?.answers)
                mutableState.update { it.copy(
                    loading = false, loaded = true, needsReload = false, record = latest, authenticationRequired = false, forbidden = false,
                    selections = if (keepEdits) before.selections else latest?.answers?.let(ParentalScreenTimeSelections::from) ?: ParentalScreenTimeSelections(),
                    touched = if (keepEdits) before.touched else false,
                    message = if (confirmed) "Hábitos de pantallas guardados." else null,
                ) }
                unconfirmed = null
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                mutableState.update { it.copy(loading = false, loaded = false, error = failureMessage(exception, false),
                    authenticationRequired = exception is AdultAuthenticationRequiredException, forbidden = exception is AdultAuthorizationException) }
            } finally { inFlight = false }
        }
    }

    fun save() {
        if (inFlight || !state.value.canSave) return
        val answers = state.value.selections.answers()
        unconfirmed = answers
        inFlight = true
        mutableState.update { it.copy(saving = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val saved = repository.save(profileId, answers)
                mutableState.update { it.copy(
                    saving = false, record = saved,
                    selections = if (saved.answers == answers) it.selections else ParentalScreenTimeSelections.from(saved.answers),
                    touched = false, needsReload = false, message = "Hábitos de pantallas guardados.",
                ) }
                unconfirmed = null
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                mutableState.update { it.copy(saving = false, needsReload = true, error = failureMessage(exception, true),
                    authenticationRequired = exception is AdultAuthenticationRequiredException, forbidden = exception is AdultAuthorizationException) }
            } finally { inFlight = false }
        }
    }

    private fun failureMessage(exception: Exception, saving: Boolean): String = when (exception) {
        is AdultAuthenticationRequiredException -> "Tu sesión adulta venció. Volvé a iniciar sesión."
        is AdultAuthorizationException -> "Esta cuenta adulta no tiene permiso para acceder a los hábitos de este perfil."
        is ProfileFormException -> exception.message.orEmpty()
        is retrofit2.HttpException -> "No pudimos acceder a los hábitos de pantallas (HTTP ${exception.code()}). Intentá nuevamente."
        else -> if (saving) "No pudimos confirmar el guardado de hábitos. Comprobaremos lo guardado antes de reintentar."
            else "No pudimos consultar los hábitos de pantallas. Revisá tu conexión e intentá nuevamente."
    }
}
