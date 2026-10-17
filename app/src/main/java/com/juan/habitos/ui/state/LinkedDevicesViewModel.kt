package com.juan.habitos.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juan.habitos.domain.model.AdultManagedDevice
import com.juan.habitos.domain.repository.AdultAuthenticationRequiredException
import com.juan.habitos.domain.repository.AdultAuthorizationException
import com.juan.habitos.domain.repository.AdultDeviceNotFoundException
import com.juan.habitos.domain.repository.AdultDeviceRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LinkedDeviceUiItem(
    internal val deviceId: String,
    val title: String,
    val active: Boolean,
    val lastActivity: String?,
    val canDeactivate: Boolean,
)

data class LinkedDevicesUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val devices: List<LinkedDeviceUiItem> = emptyList(),
    val confirmationDeviceId: String? = null,
    val deactivatingDeviceId: String? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val adultAuthenticationRequired: Boolean = false,
    val demoMode: Boolean = false,
)

class LinkedDevicesViewModel(
    private val repository: AdultDeviceRepository,
    private val profileId: String,
    private val demoMode: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(LinkedDevicesUiState(demoMode = demoMode))
    val state: StateFlow<LinkedDevicesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading || _state.value.refreshing) return
        viewModelScope.launch {
            val initial = _state.value.devices.isEmpty()
            _state.update {
                it.copy(
                    loading = initial,
                    refreshing = !initial,
                    errorMessage = null,
                    successMessage = null,
                    adultAuthenticationRequired = false,
                )
            }
            try {
                val devices = repository.devices(profileId)
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        devices = devices.toUiItems(demoMode),
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                handleFailure(exception, "No pudimos cargar los dispositivos. Revisá la conexión e intentá nuevamente.")
            }
        }
    }

    fun requestDeactivate(deviceId: String) {
        val device = _state.value.devices.firstOrNull { it.deviceId == deviceId } ?: return
        if (!device.canDeactivate || _state.value.deactivatingDeviceId != null) return
        _state.update { it.copy(confirmationDeviceId = deviceId, errorMessage = null, successMessage = null) }
    }

    fun cancelDeactivate() {
        if (_state.value.deactivatingDeviceId == null) _state.update { it.copy(confirmationDeviceId = null) }
    }

    fun confirmDeactivate() {
        val deviceId = _state.value.confirmationDeviceId ?: return
        if (demoMode || _state.value.deactivatingDeviceId != null) return
        val current = _state.value.devices.firstOrNull { it.deviceId == deviceId } ?: return
        if (!current.active) return
        _state.update { it.copy(deactivatingDeviceId = deviceId, errorMessage = null, successMessage = null) }
        viewModelScope.launch {
            try {
                val updated = repository.deactivate(profileId, deviceId)
                require(!updated.active) { "Deactivated device remained active" }
                _state.update { state ->
                    state.copy(
                        devices = state.devices.map { if (it.deviceId == deviceId) updated.toUiItem(it.title, demoMode) else it },
                        confirmationDeviceId = null,
                        deactivatingDeviceId = null,
                        successMessage = "Dispositivo desvinculado. El historial anterior se conserva.",
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                handleFailure(exception, "No pudimos desvincular el dispositivo. Intentá nuevamente.")
            } finally {
                _state.update { it.copy(deactivatingDeviceId = null) }
            }
        }
    }

    private fun handleFailure(exception: Exception, fallback: String) {
        _state.update {
            it.copy(
                loading = false,
                refreshing = false,
                confirmationDeviceId = null,
                errorMessage = when (exception) {
                    is AdultAuthenticationRequiredException -> "La sesión adulta venció. Iniciá sesión nuevamente."
                    is AdultAuthorizationException -> "No tenés permiso para administrar los dispositivos de este perfil."
                    is AdultDeviceNotFoundException -> "El dispositivo ya no está disponible. Actualizá el listado."
                    else -> fallback
                },
                adultAuthenticationRequired = exception is AdultAuthenticationRequiredException,
            )
        }
    }
}

internal fun List<AdultManagedDevice>.toUiItems(demoMode: Boolean): List<LinkedDeviceUiItem> {
    val platformTotals = groupingBy { it.platform.uppercase() }.eachCount()
    val platformPositions = mutableMapOf<String, Int>()
    return map { device ->
        val platform = device.platform.uppercase()
        val position = (platformPositions[platform] ?: 0) + 1
        platformPositions[platform] = position
        val label = platformLabel(platform)
        val title = if ((platformTotals[platform] ?: 0) > 1) "$label $position" else label
        device.toUiItem(title, demoMode)
    }
}

private fun AdultManagedDevice.toUiItem(title: String, demoMode: Boolean) = LinkedDeviceUiItem(
    deviceId = id,
    title = title,
    active = active,
    lastActivity = lastSeenAt?.let(::formatLastActivity),
    canDeactivate = active && !demoMode,
)

private fun platformLabel(platform: String) = when (platform) {
    "ANDROID" -> "Dispositivo Android"
    "IOS" -> "Dispositivo iOS"
    else -> "Dispositivo móvil"
}

private fun formatLastActivity(value: String): String = runCatching {
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale("es", "AR"))
    Instant.parse(value).atZone(ZoneId.systemDefault()).format(formatter)
}.getOrDefault("Fecha no disponible")
