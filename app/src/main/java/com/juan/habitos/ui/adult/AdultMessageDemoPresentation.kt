package com.juan.habitos.ui.adult

import com.juan.habitos.domain.model.Alert
import com.juan.habitos.ui.state.AdultMessagesUiState

internal const val ADULT_MESSAGES_DEMO_LABEL = "Ejemplo de demostración · Datos simulados"

internal data class AdultMessageDemoAlert(val alert: Alert, val cardId: String, val version: Int)

internal fun adultMessageAlertForAlertsTab(
    alertsDemo: Boolean,
    messageAlert: AdultMessageDemoAlert?,
): AdultMessageDemoAlert? = messageAlert.takeUnless { alertsDemo }

internal fun openAdultMessageDemoAlert(
    target: AdultMessageDemoAlert,
    selectMessage: (String, Int) -> Unit,
    selectTab: (Int) -> Unit,
) {
    selectMessage(target.cardId, target.version)
    selectTab(ANALYSIS_TAB)
}

internal fun adultMessageDemoAlert(state: AdultMessagesUiState): AdultMessageDemoAlert? {
    if (!state.demo || state.loading || state.error != null || state.profileId == null) return null
    val current = state.current.firstOrNull { it.card.direction == "INCREASE" } ?: return null
    val opening = state.history?.versions.orEmpty()
        .filter { it.card.cardId == current.card.cardId }
        .minByOrNull { it.version } ?: return null
    return AdultMessageDemoAlert(
        alert = Alert(
            id = "adult-message-demo-${opening.card.cardId}",
            type = ADULT_MESSAGES_DEMO_LABEL,
            icon = "📈",
            title = "Cambio en el tiempo de uso",
            description = "Se registró un aumento en el tiempo de uso respecto del período anterior. Consultá el análisis para conocer más detalles.",
            detail = "", recommendation = "",
        ),
        cardId = opening.card.cardId,
        version = opening.version,
    )
}
