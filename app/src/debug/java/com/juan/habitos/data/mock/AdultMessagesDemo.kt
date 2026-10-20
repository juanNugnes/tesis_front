package com.juan.habitos.data.mock

import com.juan.habitos.data.remote.*
import java.security.MessageDigest

/** Read-only examples: no API, storage, clock, or automatic evaluation. */
internal fun createAdultMessagesDemoRepository(): AdultMessagesRepository = AdultMessagesRepository { profileId ->
    fun identity(episode: String) = MessageDigest.getInstance("SHA-256")
        .digest("adult-messages-demo/$profileId/$episode".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    val recommendation = "Podés conversar con tu hijo/a sobre qué cambió en la rutina y acordar juntos un momento para una actividad que disfruten."
    fun card(increase: Boolean) = AdultMessageCard(
        cardId = identity(if (increase) "increase" else "decrease"),
        profileId = profileId,
        seriesId = "demo-series-$profileId",
        timeZone = "America/Argentina/Buenos_Aires",
        direction = if (increase) "INCREASE" else "DECREASE",
        status = "ACTIVE",
        title = if (increase) "Más uso registrado en este teléfono" else "Menos uso registrado en este teléfono",
        text = if (increase) "En el período reciente se registraron 2 h 20 min diarios, frente a 1 h 40 min en el período de referencia. La diferencia fue de 40 minutos más por día."
            else "En el período reciente se registraron 1 h 40 min diarios, frente a 2 h 20 min en el período de referencia. La diferencia fue de 40 minutos menos por día.",
        recommendation = if (increase) recommendation else "Pueden conversar sobre qué cambió en la rutina y qué acuerdos les resultan útiles.",
        evaluationDate = if (increase) "2026-02-02" else "2025-10-06",
        openingReferenceStart = if (increase) "2026-01-05" else "2025-09-08",
        openingRecentStart = if (increase) "2026-01-19" else "2025-09-22",
        comparisonRecentStart = if (increase) "2026-01-19" else "2025-09-22",
        statistics = AdultMessageStatistics(
            recent = if (increase) 140.0 else 100.0,
            reference = if (increase) 100.0 else 140.0,
            delta = if (increase) 40.0 else -40.0,
            minimum = if (increase) 40.0 else -40.0,
            maximum = if (increase) 40.0 else -40.0,
            above = if (increase) 14.0 else 0.0,
            below = if (increase) 0.0 else 14.0,
            percentChange = if (increase) 40.0 else -100.0 * 40.0 / 140.0,
            observedDaysRecent = 14, observedDaysReference = 14,
        ),
        measurement = "uso registrado en este teléfono",
        measurementDetail = "Ejemplo de demostración · Datos simulados. Uso registrado en un teléfono; no identifica quién lo usó ni incluye otras pantallas.",
        configurationId = "adult-messages-internal-candidate-v1",
        configurationFingerprint = "0".repeat(64),
        scope = "internal_candidate_not_family_validated",
        deliveryStatus = "CANDIDATE_NOT_DELIVERED",
    )

    val increase = card(true)
    val decrease = card(false)
    val followUp = increase.copy(
        status = "INCONCLUSIVE",
        title = "Seguimiento del tiempo de uso",
        text = "El tiempo de uso registrado continúa por encima de la referencia inicial, aunque la diferencia se redujo. Todavía no hay evidencia suficiente para considerar finalizado el cambio.",
        recommendation = "Podés continuar conversando sobre los hábitos de uso y revisar los acuerdos que establecieron.",
        evaluationDate = "2026-02-09",
        comparisonRecentStart = "2026-01-26",
        statistics = null,
        measurementDetail = increase.measurementDetail + " El texto de seguimiento es ilustrativo; no es una cita literal del motor.",
    )
    AdultMessageHistory(listOf(
        AdultMessageVersion(1, decrease),
        AdultMessageVersion(2, decrease.copy(status = "EXPIRED", evaluationDate = "2025-12-01")),
        AdultMessageVersion(1, increase),
        AdultMessageVersion(2, followUp),
    ))
}
