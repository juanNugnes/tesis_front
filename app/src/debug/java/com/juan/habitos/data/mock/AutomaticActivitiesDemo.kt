package com.juan.habitos.data.mock

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

internal const val AUTOMATIC_DEMO_LABEL = "Ejemplo de demostración · Datos simulados"

internal enum class AutomaticDemoScenario { DAYTIME, BEDTIME, INELIGIBLE, WAITING }
internal enum class AutomaticDemoStage { GENERATED, PRESENTED, SELECTED, STARTED, COMPLETED, REJECTED }
internal enum class AutomaticDemoEvent { GENERATED, PRESENTED, ACCEPTED, START_DECLARED, COMPLETION_DECLARED, REJECTED }

internal data class AutomaticDemoOffer(
    val id: String,
    val profileId: String,
    val scenario: AutomaticDemoScenario,
    val reason: String,
    val message: String,
    val title: String,
    val description: String,
    val stage: AutomaticDemoStage = AutomaticDemoStage.GENERATED,
    val events: List<AutomaticDemoEvent> = listOf(AutomaticDemoEvent.GENERATED),
)

internal data class AutomaticActivitiesDemoState(
    val profileId: String,
    val offer: AutomaticDemoOffer? = null,
    val scenario: AutomaticDemoScenario? = null,
    val rejections: Int = 0,
    val paused: Boolean = false,
)

/**
 * Manual, in-memory presentation script, NOT an eligibility/policy engine.
 * No repository, API, preferences, persistent history, timer, or real measurement is available here.
 * Each scenario is a separate fictional opportunity; switching skips context, never phone time.
 * Only the current offer and at most six lifecycle events are kept. Reset/process death clears it.
 */
internal class AutomaticActivitiesDemoViewModel(val profileId: String) : ViewModel() {
    init { require(profileId.isNotBlank()) }
    private val identity = MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private var sequence = 0L
    private val mutableState = MutableStateFlow(AutomaticActivitiesDemoState(profileId))
    val state = mutableState.asStateFlow()

    fun simulate(scenario: AutomaticDemoScenario) {
        val previous = state.value
        if (previous.paused) return
        // Repeated taps/recomposition cannot generate a second active copy.
        if (previous.offer?.stage in listOf(AutomaticDemoStage.GENERATED, AutomaticDemoStage.PRESENTED, AutomaticDemoStage.SELECTED, AutomaticDemoStage.STARTED)) return
        val offer = when (scenario) {
            AutomaticDemoScenario.DAYTIME -> AutomaticDemoOffer(
                id = "demo-auto-$identity-${++sequence}", profileId = profileId, scenario = scenario,
                reason = "Pausa diurna · escenario ficticio de 45 minutos continuos entre las 08:00 y antes de las 20:00. No es una medición del teléfono.",
                message = "¿Hacemos una pausa? Tengo una idea para vos.",
                title = "Dibujá algo que te guste",
                description = "Elegí algo que te divierta e intentá dibujarlo a tu manera.",
            )
            AutomaticDemoScenario.BEDTIME -> AutomaticDemoOffer(
                id = "demo-auto-$identity-${++sequence}", profileId = profileId, scenario = scenario,
                reason = "Proximidad del descanso · escenario ficticio dentro de los 60 minutos previos. No cambia tu horario real de descanso.",
                message = "Se acerca el momento de descansar. ¿Te gustaría hacer algo tranquilo?",
                title = "Inventá un cuento",
                description = "Imaginá un personaje y contá una historia sobre su aventura.",
            )
            AutomaticDemoScenario.INELIGIBLE, AutomaticDemoScenario.WAITING -> null
        }
        mutableState.value = previous.copy(offer = offer, scenario = scenario)
    }

    fun presented(offerId: String) = transition(offerId, AutomaticDemoStage.GENERATED, AutomaticDemoStage.PRESENTED, AutomaticDemoEvent.PRESENTED)
    fun accept(offerId: String) = transition(offerId, AutomaticDemoStage.PRESENTED, AutomaticDemoStage.SELECTED, AutomaticDemoEvent.ACCEPTED)
    fun start(offerId: String) = transition(offerId, AutomaticDemoStage.SELECTED, AutomaticDemoStage.STARTED, AutomaticDemoEvent.START_DECLARED)
    fun finish(offerId: String) = transition(offerId, AutomaticDemoStage.STARTED, AutomaticDemoStage.COMPLETED, AutomaticDemoEvent.COMPLETION_DECLARED)

    fun reject(offerId: String) {
        val previous = state.value
        if (previous.offer?.id != offerId || previous.offer.stage != AutomaticDemoStage.PRESENTED) return
        val count = (previous.rejections + 1).coerceAtMost(2)
        mutableState.value = previous.copy(
            offer = previous.offer.copy(stage = AutomaticDemoStage.REJECTED, events = previous.offer.events + AutomaticDemoEvent.REJECTED),
            rejections = count, paused = count == 2,
        )
    }

    private fun transition(id: String, from: AutomaticDemoStage, to: AutomaticDemoStage, event: AutomaticDemoEvent) {
        val previous = state.value
        val offer = previous.offer ?: return
        if (offer.id != id || offer.stage != from) return
        mutableState.value = previous.copy(offer = offer.copy(stage = to, events = offer.events + event))
    }

    fun reset() {
        // Do not reuse IDs: a late callback from before reset must never affect a new offer.
        mutableState.value = AutomaticActivitiesDemoState(profileId)
    }
}
