package com.juan.habitos.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juan.habitos.data.remote.*
import com.juan.habitos.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import retrofit2.HttpException
import com.google.gson.Gson

private data class AdultMessageFailureDetail(val retryable: Boolean = false)
private data class AdultMessageFailureBody(val detail: AdultMessageFailureDetail?)

internal fun adultMessageRetryable(error: Exception): Boolean = when (error) {
    is AdultAuthenticationRequiredException, is AdultAuthorizationException -> false
    is HttpException -> runCatching {
        Gson().fromJson(error.response()?.errorBody()?.string(), AdultMessageFailureBody::class.java)?.detail?.retryable
    }.getOrNull() ?: (error.code() >= 500 || error.code() == 429)
    is IllegalArgumentException -> false
    else -> true
}

data class AdultMessagesUiState(
    val profileId: String? = null,
    val loading: Boolean = false,
    val history: AdultMessageHistory? = null,
    val error: String? = null,
    val retryable: Boolean = false,
    val demo: Boolean = false,
    val authenticationRequired: Boolean = false,
) {
    val current get() = history?.latestCards().orEmpty().filter { it.card.status in currentMessageStatuses }
    val archived get() = history?.latestCards().orEmpty().filter { it.card.status !in currentMessageStatuses }
}

class AdultMessagesViewModel(
    profiles: ProfileRepository,
    private val repository: AdultMessagesRepository,
    private val demo: Boolean = false,
) : ViewModel() {
    private val retries = MutableStateFlow(0)
    private val _state = MutableStateFlow(AdultMessagesUiState(demo = demo))
    val state = _state.asStateFlow()
    init {
        viewModelScope.launch {
            combine(profiles.profile.map { it?.id }.distinctUntilChanged(), retries) { id, _ -> id }
                .collectLatest { id ->
                    _state.value = AdultMessagesUiState(profileId = id, loading = id != null, demo = demo)
                    if (id != null) {
                        try {
                            val history = repository.history(id)
                            currentCoroutineContext().ensureActive()
                            _state.value = AdultMessagesUiState(profileId = id, history = history, demo = demo)
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) {
                            currentCoroutineContext().ensureActive()
                            val message = when (e) {
                                is AdultAuthenticationRequiredException -> "Tu sesión adulta venció. Volvé a iniciar sesión."
                                is AdultAuthorizationException -> "No tenés autorización para consultar los mensajes de este perfil."
                                is HttpException -> "El servicio de mensajes no está disponible en este momento."
                                is IllegalArgumentException -> "No pudimos interpretar los mensajes de este perfil."
                                else -> "No pudimos obtener los mensajes. Revisá la conexión e intentá nuevamente."
                            }
                            _state.value = AdultMessagesUiState(profileId = id, error = message,
                                retryable = adultMessageRetryable(e), demo = demo,
                                authenticationRequired = e is AdultAuthenticationRequiredException)
                        }
                    }
                }
        }
    }
    fun retry() { retries.value++ }
}
