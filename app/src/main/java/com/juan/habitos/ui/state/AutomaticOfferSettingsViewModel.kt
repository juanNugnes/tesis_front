package com.juan.habitos.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juan.habitos.domain.model.AutomaticOfferSettings
import com.juan.habitos.domain.repository.AutomaticOfferSettingsRepository
import com.juan.habitos.domain.repository.AdultAuthenticationRequiredException
import com.juan.habitos.domain.repository.AdultAuthorizationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class AutomaticOfferSettingsUiState(
    val loaded: Boolean = false,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val automaticEnabled: Boolean = false,
    val sharedPhone: Boolean = false,
    val hasChanges: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val authenticationRequired: Boolean = false,
    val forbidden: Boolean = false,
)

class AutomaticOfferSettingsViewModel(
    private val repository: AutomaticOfferSettingsRepository,
    private val profileId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AutomaticOfferSettingsUiState())
    val state = mutableState.asStateFlow()
    private var inFlight = false
    init { refresh() }

    fun automaticEnabled(value: Boolean) = edit { copy(automaticEnabled = value) }
    fun sharedPhone(value: Boolean) = edit { copy(sharedPhone = value) }
    private fun edit(change: AutomaticOfferSettingsUiState.() -> AutomaticOfferSettingsUiState) {
        if (!state.value.loaded || state.value.saving) return
        mutableState.update { it.change().copy(hasChanges = true, saved = false, error = null) }
    }

    fun refresh() {
        if (inFlight) return
        inFlight = true
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val settings = try { repository.load(profileId) } catch (error: HttpException) {
                    if (error.code() == 404) AutomaticOfferSettings(false, false) else throw error
                }
                mutableState.value = AutomaticOfferSettingsUiState(
                    loaded = true, loading = false,
                    automaticEnabled = settings.automaticEnabled, sharedPhone = settings.sharedPhone,
                )
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { mutableState.update { it.copy(
                loading = false,
                authenticationRequired = exception is AdultAuthenticationRequiredException,
                forbidden = exception is AdultAuthorizationException,
                error = when (exception) {
                    is AdultAuthenticationRequiredException -> "Tu sesión adulta venció. Volvé a iniciar sesión."
                    is AdultAuthorizationException -> "Esta cuenta adulta no tiene acceso a la configuración del perfil."
                    else -> "No pudimos cargar la configuración de ofertas automáticas."
                },
            ) } }
            finally { inFlight = false }
        }
    }

    fun save() {
        if (inFlight || !state.value.loaded || !state.value.hasChanges) return
        inFlight = true
        val requested = AutomaticOfferSettings(state.value.automaticEnabled, state.value.sharedPhone)
        mutableState.update { it.copy(saving = true, error = null, saved = false) }
        viewModelScope.launch {
            try {
                val saved = repository.save(profileId, requested)
                mutableState.update { it.copy(
                    saving = false, automaticEnabled = saved.automaticEnabled, sharedPhone = saved.sharedPhone,
                    hasChanges = false, saved = true,
                ) }
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { mutableState.update { it.copy(
                saving = false,
                authenticationRequired = exception is AdultAuthenticationRequiredException,
                forbidden = exception is AdultAuthorizationException,
                error = when (exception) {
                    is AdultAuthenticationRequiredException -> "Tu sesión adulta venció. No se guardaron los cambios."
                    is AdultAuthorizationException -> "Esta cuenta adulta no puede cambiar la configuración del perfil."
                    else -> "No se guardó la configuración de ofertas automáticas."
                },
            ) } }
            finally { inFlight = false }
        }
    }
}
