package com.juan.habitos.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juan.habitos.domain.repository.ChildPreferencesRepository
import com.juan.habitos.domain.repository.DeviceLinkException
import com.juan.habitos.domain.repository.DeviceLinkFailureReason
import com.juan.habitos.domain.repository.DeviceRepository
import com.juan.habitos.domain.repository.ProfileContextRepository
import com.juan.habitos.domain.repository.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeviceProfileLinkUiState(
    val code: String = "",
    val submitting: Boolean = false,
    val success: Boolean = false,
    val errorMessage: String? = null,
) {
    val valid: Boolean get() = code.matches(Regex("\\d{6}"))
}

class DeviceProfileLinkViewModel(
    private val deviceRepository: DeviceRepository,
    private val profileRepository: ProfileRepository,
    private val preferencesRepository: ChildPreferencesRepository,
    private val contextRepository: ProfileContextRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DeviceProfileLinkUiState())
    val state = _state.asStateFlow()
    private var navigationDelivered = false

    fun code(value: String) {
        if (_state.value.submitting || _state.value.success) return
        _state.update { it.copy(code = value.take(7), errorMessage = null) }
    }

    fun submit(onLinked: () -> Unit) {
        val current = _state.value
        if (!current.valid || current.submitting || current.success) return
        _state.value = current.copy(submitting = true, errorMessage = null)
        viewModelScope.launch {
            try {
                deviceRepository.claimProfile(current.code)
                profileRepository.completeOnboarding()
                refreshWithoutInvalidatingClaim { profileRepository.refreshSettings() }
                refreshWithoutInvalidatingClaim { preferencesRepository.refresh() }
                refreshWithoutInvalidatingClaim { contextRepository.refresh() }
                _state.update { it.copy(submitting = false, success = true, errorMessage = null) }
                if (!navigationDelivered) {
                    navigationDelivered = true
                    onLinked()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val message = when ((exception as? DeviceLinkException)?.reason) {
                    DeviceLinkFailureReason.INVALID_OR_EXPIRED_CODE ->
                        "El código no es válido o venció. Pedí uno nuevo e intentá nuevamente."
                    DeviceLinkFailureReason.INSTALLATION_CONFLICT ->
                        "No pudimos vincular este dispositivo con ese perfil."
                    else -> "No pudimos vincular el perfil. Revisá la conexión e intentá nuevamente."
                }
                _state.update { it.copy(submitting = false, errorMessage = message) }
            }
        }
    }

    private suspend fun refreshWithoutInvalidatingClaim(block: suspend () -> Unit) {
        try {
            block()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // The claim is already valid. Cached data remains available for a later refresh.
        }
    }
}
