package com.juan.habitos.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juan.habitos.data.remote.AdultHistoricalEvaluationDto
import com.juan.habitos.data.remote.AdultHistoricalEvaluationsRepository
import com.juan.habitos.data.remote.AdultHistoricalSubmissionDto
import com.juan.habitos.domain.repository.AdultAuthenticationRequiredException
import com.juan.habitos.domain.repository.AdultAuthorizationException
import com.juan.habitos.domain.repository.ProfileRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class HistoricalEvaluationItemUi(
    val date: String,
    val title: String,
    val description: String,
)

data class AdultHistoricalEvaluationsUiState(
    val profileId: String? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val items: List<HistoricalEvaluationItemUi> = emptyList(),
    val error: String? = null,
    val authenticationRequired: Boolean = false,
    val forbidden: Boolean = false,
)

private val historicalDateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

internal fun historicalEvaluationPresentation(
    submission: AdultHistoricalSubmissionDto,
): HistoricalEvaluationItemUi {
    val date = LocalDate.parse(submission.localDate).format(historicalDateFormatter)
    val evaluation = submission.analyticsEvaluation
    val text = when (submission.status) {
        "PENDING", "SENDING" -> "Evaluación en proceso" to
            "La comparación histórica todavía se está procesando."
        "TECHNICAL_ERROR", "CONFLICT" -> "No se pudo completar la evaluación" to
            if (submission.retryable) {
                "Hubo un problema técnico. Podés actualizar los resultados para consultar nuevamente."
            } else {
                "Hubo un problema técnico y no hay un resultado C1 para mostrar."
            }
        "PROCESSED" -> processedHistoricalEvaluationText(evaluation)
        else -> "Resultado no disponible" to
            "No hay un resultado C1 compatible para mostrar en este período."
    }
    return HistoricalEvaluationItemUi(date, text.first, text.second)
}

private fun processedHistoricalEvaluationText(
    evaluation: AdultHistoricalEvaluationDto?,
): Pair<String, String> = when {
    evaluation == null -> "Resultado no disponible" to
        "La evaluación finalizó, pero no hay un resultado C1 disponible para mostrar."
    evaluation.outcome == "ABSTAIN" && evaluation.abstentionReason == "INSUFFICIENT_HISTORY" ->
        "Historial insuficiente" to
            "Todavía no hay suficiente historial para realizar la comparación."
    evaluation.outcome == "ABSTAIN" -> "Comparación no realizada" to
        "C1 se abstuvo de realizar la comparación para este período."
    evaluation.outcome == "NO_CHANGE" -> "Sin cambios detectados" to
        "La comparación histórica se realizó y no detectó cambios."
    evaluation.outcome == "CHANGE" -> "Cambio detectado" to
        "C1 detectó un cambio respecto del historial disponible. Este resultado no es un riesgo, un diagnóstico ni una alerta."
    else -> "Resultado no disponible" to
        "La evaluación finalizó, pero no hay un resultado C1 compatible para mostrar."
}

class AdultHistoricalEvaluationsViewModel(
    profiles: ProfileRepository,
    private val repository: AdultHistoricalEvaluationsRepository,
) : ViewModel() {
    private val refreshes = MutableStateFlow(0)
    private val _state = MutableStateFlow(AdultHistoricalEvaluationsUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(profiles.profile.distinctUntilChanged(), refreshes) { profile, _ -> profile }
                .collectLatest { profile ->
                    val profileId = profile?.id
                    if (profileId == null) {
                        _state.value = AdultHistoricalEvaluationsUiState()
                        return@collectLatest
                    }

                    val previous = _state.value.takeIf { it.profileId == profileId }
                    _state.value = AdultHistoricalEvaluationsUiState(
                        profileId = profileId,
                        loading = previous?.loaded != true,
                        refreshing = previous?.loaded == true,
                        loaded = previous?.loaded == true,
                        items = previous?.items.orEmpty(),
                    )
                    try {
                        val submissions = repository.list(profileId)
                        currentCoroutineContext().ensureActive()
                        _state.value = AdultHistoricalEvaluationsUiState(
                            profileId = profileId,
                            loaded = true,
                            items = submissions.map(::historicalEvaluationPresentation),
                        )
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        currentCoroutineContext().ensureActive()
                        _state.value = AdultHistoricalEvaluationsUiState(
                            profileId = profileId,
                            loaded = previous?.loaded == true,
                            items = previous?.items.orEmpty(),
                            error = when (exception) {
                                is AdultAuthenticationRequiredException ->
                                    "Tu sesión adulta venció. Volvé a iniciar sesión."
                                is AdultAuthorizationException ->
                                    "Esta cuenta adulta no tiene permiso para consultar las evaluaciones de este perfil."
                                is IllegalArgumentException ->
                                    "No pudimos interpretar los resultados históricos recibidos."
                                else ->
                                    "No pudimos actualizar las evaluaciones. Revisá la conexión e intentá nuevamente."
                            },
                            authenticationRequired = exception is AdultAuthenticationRequiredException,
                            forbidden = exception is AdultAuthorizationException,
                        )
                    }
                }
        }
    }

    fun refresh() {
        refreshes.value++
    }
}
