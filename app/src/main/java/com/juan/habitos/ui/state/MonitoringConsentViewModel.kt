package com.juan.habitos.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juan.habitos.domain.model.MonitoringConsent
import com.juan.habitos.domain.model.MonitoringConsentStatus
import com.juan.habitos.domain.model.RepresentativeVerificationStatus
import com.juan.habitos.domain.repository.AdultAuthenticationRequiredException
import com.juan.habitos.domain.repository.AdultAuthorizationException
import com.juan.habitos.domain.repository.MonitoringConsentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val PRIVACY_NOTICE_VERSION = "1.0"
private const val USAGE_MONITORING_PURPOSE = "USAGE_MONITORING"

data class MonitoringConsentUiState(
    val loading: Boolean = true,
    val verificationStatus: RepresentativeVerificationStatus? = null,
    val verificationMissing: Boolean = false,
    val consent: MonitoringConsent? = null,
    val submitting: Boolean = false,
    val showRevokeConfirmation: Boolean = false,
    val errorMessage: String? = null,
    val adultAuthenticationRequired: Boolean = false,
) {
    val monitoringEnabled: Boolean get() = consent?.status == MonitoringConsentStatus.ACTIVE
    val monitoringRevoked: Boolean get() = consent?.status == MonitoringConsentStatus.REVOKED
    val canGrant: Boolean get() =
        !loading && !submitting && verificationStatus == RepresentativeVerificationStatus.APPROVED && !monitoringEnabled
    val canRevoke: Boolean get() = !loading && !submitting && monitoringEnabled
}

class MonitoringConsentViewModel(
    private val repository: MonitoringConsentRepository,
    private val profileId: String,
    private val demoMode: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(MonitoringConsentUiState())
    val state: StateFlow<MonitoringConsentUiState> = _state.asStateFlow()

    init {
        if (demoMode) {
            _state.value = MonitoringConsentUiState(
                loading = false,
                verificationMissing = true,
                errorMessage = "Esta opción no está disponible en el modo de demostración.",
            )
        } else {
            refresh()
        }
    }

    fun grant() {
        if (!_state.value.canGrant) return
        _state.update { it.copy(submitting = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                repository.grantConsent(
                    profileId = profileId,
                    privacyNoticeVersion = PRIVACY_NOTICE_VERSION,
                    purposes = setOf(USAGE_MONITORING_PURPOSE),
                )
                load()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                handleFailure(exception, "No pudimos habilitar el monitoreo. Intentá nuevamente.")
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }

    fun requestRevoke() {
        if (_state.value.canRevoke) _state.update { it.copy(showRevokeConfirmation = true) }
    }

    fun cancelRevoke() {
        if (!_state.value.submitting) _state.update { it.copy(showRevokeConfirmation = false) }
    }

    fun confirmRevoke() {
        val activeConsent = _state.value.consent?.takeIf { it.status == MonitoringConsentStatus.ACTIVE } ?: return
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val revoked = repository.revokeConsent(profileId, activeConsent.id)
                _state.update { it.copy(showRevokeConfirmation = false) }
                load(fallbackConsent = revoked)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                handleFailure(exception, "No pudimos desactivar el monitoreo. Intentá nuevamente.")
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }

    fun refresh() {
        if (demoMode || _state.value.loading && _state.value.verificationStatus != null) return
        viewModelScope.launch { load() }
    }

    private suspend fun load(fallbackConsent: MonitoringConsent? = null) {
        _state.update { it.copy(loading = true, errorMessage = null) }
        try {
            val verification = repository.getRepresentativeVerification(profileId)
            if (verification == null) {
                _state.update {
                    it.copy(
                        loading = false,
                        verificationStatus = null,
                        verificationMissing = true,
                        consent = fallbackConsent?.takeIf { consent -> consent.status == MonitoringConsentStatus.REVOKED },
                    )
                }
                return
            }
            val currentConsent = if (verification.status == RepresentativeVerificationStatus.APPROVED) {
                repository.getCurrentConsent(profileId)
            } else null
            _state.update {
                it.copy(
                    loading = false,
                    verificationStatus = verification.status,
                    verificationMissing = false,
                    consent = currentConsent ?: fallbackConsent,
                    adultAuthenticationRequired = false,
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            handleFailure(exception, "No pudimos consultar la autorización. Revisá la conexión e intentá nuevamente.")
        }
    }

    private fun handleFailure(exception: Exception, fallbackMessage: String) {
        _state.update {
            it.copy(
                loading = false,
                errorMessage = when (exception) {
                    is AdultAuthenticationRequiredException -> "La sesión adulta venció. Iniciá sesión nuevamente."
                    is AdultAuthorizationException -> "No tenés permiso para administrar el monitoreo de este perfil."
                    else -> fallbackMessage
                },
                adultAuthenticationRequired = exception is AdultAuthenticationRequiredException,
            )
        }
    }
}
