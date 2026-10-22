package com.juan.habitos.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class PreferencesFormUiState(
    val saving: Boolean = false,
    val canSave: Boolean = false,
    val authenticationRequired: Boolean = false,
    val message: String? = null,
)

/** Coordinates independent endpoints, without pretending that they form a transaction. */
class PreferencesFormViewModel(
    private val preferences: ChildPreferencesViewModel,
    private val habits: ParentalScreenTimeViewModel?,
) : ViewModel() {
    private val operation = MutableStateFlow(PreferencesFormUiState())
    private val habitsState = habits?.state ?: MutableStateFlow<ParentalScreenTimeUiState?>(null)
    val state = combine(preferences.state, habitsState, operation) { p, h, op ->
        val auth = p.adultAuthenticationRequired || h?.authenticationRequired == true
        val idle = !op.saving && !p.refreshing && !p.submitting && h?.loading != true && h?.saving != true
        op.copy(authenticationRequired = auth, canSave = idle && !auth && !p.deviceAuthenticationRequired &&
            ((p.loaded && p.valid && p.hasChanges) || (h?.hasChanges == true && !h.forbidden)))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PreferencesFormUiState())

    fun refresh() {
        if (operation.value.saving) return
        operation.value = PreferencesFormUiState()
        preferences.refreshPreservingEdits()
        habits?.refresh()
    }

    fun save(onSaved: () -> Unit = {}) {
        // Check the operation flag synchronously: two taps cannot schedule two POSTs.
        if (operation.value.saving || !state.value.canSave) return
        operation.value = PreferencesFormUiState(saving = true)
        viewModelScope.launch {
            try {
                val savePreferences = preferences.state.value.hasChanges
                val saveHabits = habits?.state?.value?.hasChanges == true
                var preferencesSaved = !savePreferences
                var habitsSaved = !saveHabits
                if (savePreferences && preferences.state.value.valid) {
                    preferences.save()
                    preferencesSaved = preferences.state.first { !it.submitting }.let { it.saved && !it.hasChanges }
                }
                if (saveHabits && !preferences.state.value.adultAuthenticationRequired) {
                    val vm = requireNotNull(habits)
                    if (!vm.state.value.loaded || vm.state.value.needsReload) {
                        vm.refresh()
                        vm.state.first { !it.loading }
                    }
                    if (vm.state.value.canSave) {
                        vm.save()
                        vm.state.first { !it.saving }
                    }
                    habitsSaved = vm.state.value.let { it.loaded && !it.needsReload && !it.hasChanges && it.error == null }
                }
                // A user can keep editing while the other endpoint is in flight.
                // Never navigate away or claim the whole form is saved with new edits pending.
                preferencesSaved = preferencesSaved && !preferences.state.value.hasChanges
                habitsSaved = habitsSaved && habits?.state?.value?.hasChanges != true
                operation.value = PreferencesFormUiState(message = when {
                    preferencesSaved && habitsSaved -> if (habits == null) "Preferencias de actividades guardadas." else "Cambios guardados."
                    savePreferences && preferencesSaved -> "Preferencias guardadas. Hábitos de pantallas pendientes."
                    saveHabits && habitsSaved -> "Hábitos de pantallas guardados. Preferencias pendientes."
                    else -> "Quedan cambios pendientes. Tus respuestas siguen en el formulario."
                })
                if (preferencesSaved && habitsSaved) onSaved()
            } catch (exception: CancellationException) { throw exception }
            finally { operation.update { it.copy(saving = false) } }
        }
    }
}
