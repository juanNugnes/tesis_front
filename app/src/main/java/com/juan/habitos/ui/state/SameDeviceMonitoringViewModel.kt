package com.juan.habitos.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juan.habitos.domain.repository.AdultAuthenticationRequiredException
import com.juan.habitos.domain.repository.AdultAuthorizationException
import com.juan.habitos.domain.repository.DeviceAuthenticationRequiredException
import com.juan.habitos.domain.repository.DeviceRepository
import com.juan.habitos.domain.repository.LinkRepository
import com.juan.habitos.domain.repository.ProfileLocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class SameDeviceMonitoringStage { CHECKING_LINK, LINKING_PROFILE }

data class SameDeviceMonitoringUiState(
    val loading: Boolean = true,
    val stage: SameDeviceMonitoringStage = SameDeviceMonitoringStage.CHECKING_LINK,
    val ready: Boolean = false,
    val adultAuthenticationRequired: Boolean = false,
    val errorMessage: String? = null,
)

class SameDeviceMonitoringViewModel(
    private val profileStore: ProfileLocalStore,
    private val deviceRepository: DeviceRepository,
    private val linkRepository: LinkRepository,
    private val demoMode: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(SameDeviceMonitoringUiState())
    val state: StateFlow<SameDeviceMonitoringUiState> = _state.asStateFlow()
    private var preparation: Job? = null

    init {
        prepare()
    }

    fun prepare() {
        if (preparation?.isActive == true) return
        preparation = viewModelScope.launch {
            _state.value = SameDeviceMonitoringUiState()
            if (demoMode) {
                _state.value = SameDeviceMonitoringUiState(loading = false, ready = true)
                return@launch
            }
            try {
                val localProfile = profileStore.profile.first()
                    ?: error("No hay un perfil infantil configurado en este dispositivo.")
                val profileId = localProfile.id
                    ?: error("El perfil infantil todavía no está vinculado.")
                val alreadyLinked = linkRepository.profiles().any { it.id == profileId }
                if (!alreadyLinked) {
                    _state.value = SameDeviceMonitoringUiState(stage = SameDeviceMonitoringStage.LINKING_PROFILE)
                    val linkCode = deviceRepository.createAdultProfileLinkCode()
                    val linkedProfile = linkRepository.findProfile(linkCode.code)
                        ?: error("No pudimos vincular el perfil infantil con la cuenta adulta.")
                    check(linkedProfile.id == profileId) { "La vinculación devolvió un perfil diferente." }
                    linkRepository.link(linkedProfile)
                }
                _state.value = SameDeviceMonitoringUiState(loading = false, ready = true)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: AdultAuthenticationRequiredException) {
                _state.value = SameDeviceMonitoringUiState(
                    loading = false,
                    adultAuthenticationRequired = true,
                    errorMessage = "Iniciá sesión como adulto para continuar.",
                )
            } catch (_: AdultAuthorizationException) {
                _state.value = SameDeviceMonitoringUiState(
                    loading = false,
                    errorMessage = "La cuenta adulta no tiene permiso para administrar este perfil.",
                )
            } catch (_: DeviceAuthenticationRequiredException) {
                _state.value = SameDeviceMonitoringUiState(
                    loading = false,
                    errorMessage = "El dispositivo infantil necesita volver a vincularse antes de continuar.",
                )
            } catch (exception: Exception) {
                _state.value = SameDeviceMonitoringUiState(
                    loading = false,
                    errorMessage = exception.message?.takeIf { it.isNotBlank() }
                        ?: "No pudimos preparar la autorización. Revisá la conexión e intentá nuevamente.",
                )
            }
        }
    }
}
