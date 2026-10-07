package com.juan.habitos.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juan.habitos.domain.model.ProfileContext
import com.juan.habitos.domain.model.ProfileContextType
import com.juan.habitos.domain.repository.ProfileContextRepository
import com.juan.habitos.domain.repository.AdultAuthenticationRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ProfileContextFormState(
    val editingId: String? = null,
    val type: ProfileContextType = ProfileContextType.VACATION,
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate? = null,
    val description: String = "",
) {
    val valid: Boolean get() = endDate == null || !endDate.isBefore(startDate)
}

data class ProfileContextUiState(
    val contexts: List<ProfileContext> = emptyList(),
    val activeContextIds: Set<String> = emptySet(),
    val form: ProfileContextFormState? = null,
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val submitting: Boolean = false,
    val deletingId: String? = null,
    val saved: Boolean = false,
    val errorMessage: String? = null,
    val adultAuthenticationRequired: Boolean = false,
)

class ProfileContextViewModel(
    private val repository: ProfileContextRepository,
    private val demoMode: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileContextUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val cached = repository.contexts.first()
            _state.update { it.copy(contexts = cached, loaded = true) }
            if (!demoMode) refresh()
        }
    }

    fun beginCreate() {
        if (state.value.refreshing || state.value.submitting || state.value.deletingId != null) return
        _state.update { it.copy(form = ProfileContextFormState(), saved = false, errorMessage = null) }
    }

    fun beginEdit(context: ProfileContext) {
        if (context.id == null || state.value.refreshing || state.value.submitting || state.value.deletingId != null) return
        _state.update {
            it.copy(
                form = ProfileContextFormState(
                    editingId = context.id,
                    type = context.type,
                    startDate = context.startDate,
                    endDate = context.endDate,
                    description = context.description.orEmpty(),
                ),
                saved = false,
                errorMessage = null,
            )
        }
    }

    fun dismissForm() {
        if (!state.value.submitting) _state.update { it.copy(form = null) }
    }

    fun type(value: ProfileContextType) = updateForm { copy(type = value) }
    fun startDate(value: LocalDate) = updateForm { copy(startDate = value) }
    fun endDate(value: LocalDate?) = updateForm { copy(endDate = value) }
    fun description(value: String) = updateForm { copy(description = value) }

    fun save() {
        val current = state.value
        val form = current.form ?: return
        if (!current.loaded || !form.valid || current.submitting || current.refreshing || current.deletingId != null) return
        _state.update { it.copy(submitting = true, saved = false, errorMessage = null) }
        viewModelScope.launch {
            val candidate = ProfileContext(
                id = form.editingId,
                type = form.type,
                startDate = form.startDate,
                endDate = form.endDate,
                description = form.description.trim().takeIf(String::isNotEmpty),
            )
            try {
                val saved = if (form.editingId == null) repository.create(candidate) else repository.update(candidate)
                val contexts = if (form.editingId == null) {
                    (state.value.contexts + saved).distinctBy(ProfileContext::id).sortedByDescending(ProfileContext::startDate)
                } else {
                    state.value.contexts.map { if (it.id == form.editingId) saved else it }.sortedByDescending(ProfileContext::startDate)
                }
                _state.update { it.copy(contexts = contexts, form = null, submitting = false, saved = true) }
                refreshActiveSilently()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.update { it.copy(submitting = false, errorMessage = if (exception is AdultAuthenticationRequiredException) "La sesión adulta venció. Iniciá sesión nuevamente." else "No pudimos guardar el contexto. Intentá nuevamente.", adultAuthenticationRequired = exception is AdultAuthenticationRequiredException) }
            }
        }
    }

    fun delete(contextId: String) {
        val current = state.value
        if (current.submitting || current.refreshing || current.deletingId != null) return
        _state.update { it.copy(deletingId = contextId, saved = false, errorMessage = null) }
        viewModelScope.launch {
            try {
                repository.delete(contextId)
                _state.update {
                    it.copy(
                        contexts = it.contexts.filterNot { context -> context.id == contextId },
                        activeContextIds = it.activeContextIds - contextId,
                        deletingId = null,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.update { it.copy(deletingId = null, errorMessage = if (exception is AdultAuthenticationRequiredException) "La sesión adulta venció. Iniciá sesión nuevamente." else "No pudimos eliminar el contexto. Intentá nuevamente.", adultAuthenticationRequired = exception is AdultAuthenticationRequiredException) }
            }
        }
    }

    fun consumeSaved() = _state.update { it.copy(saved = false) }

    private suspend fun refresh() {
        _state.update { it.copy(refreshing = true, errorMessage = null) }
        try {
            val contexts = repository.refresh()
            _state.update { it.copy(contexts = contexts) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            _state.update { it.copy(refreshing = false, errorMessage = if (exception is AdultAuthenticationRequiredException) "La sesión adulta venció. Iniciá sesión nuevamente." else "No pudimos actualizar los contextos.", adultAuthenticationRequired = exception is AdultAuthenticationRequiredException) }
            return
        }
        try {
            val active = repository.getActiveContexts()
            _state.update { it.copy(activeContextIds = active.mapNotNull(ProfileContext::id).toSet(), refreshing = false) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            _state.update { it.copy(refreshing = false, errorMessage = if (exception is AdultAuthenticationRequiredException) "La sesión adulta venció. Iniciá sesión nuevamente." else "No pudimos actualizar los contextos activos. El listado sigue disponible.", adultAuthenticationRequired = exception is AdultAuthenticationRequiredException) }
        }
    }

    private suspend fun refreshActiveSilently() {
        if (demoMode) return
        try {
            val active = repository.getActiveContexts()
            _state.update { it.copy(activeContextIds = active.mapNotNull(ProfileContext::id).toSet()) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (exception is AdultAuthenticationRequiredException) _state.update { it.copy(adultAuthenticationRequired = true, errorMessage = "La sesión adulta venció. Iniciá sesión nuevamente.") }
        }
    }

    private fun updateForm(transform: ProfileContextFormState.() -> ProfileContextFormState) {
        _state.update { current -> current.copy(form = current.form?.transform(), saved = false, errorMessage = null) }
    }
}
