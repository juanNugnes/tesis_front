package com.juan.habitos.ui.adult

import org.junit.Assert.*
import org.junit.Test

class MonitoringConsentNoticeTest {
    @Test fun `summary covers collection purpose storage and revocation without claiming deletion`() {
        assertEquals(listOf("¿Qué información registramos?", "¿Para qué utilizamos esta información?", "¿Dónde se almacena y quién puede acceder?", "¿Puedo desactivar el monitoreo?"), monitoringNoticeSummary.map { it.title })
        val text = monitoringNoticeSummary.joinToString { it.body }
        listOf("aplicación", "sesiones", "nocturna", "perfil infantil", "mensajes", "fotografías", "contactos", "ubicación precisa", "backend", "no elimina automáticamente").forEach { assertTrue(it, text.contains(it)) }
    }

    @Test fun `expanded information states limits and provides all seven topics without fictional retention periods`() {
        assertEquals(7, monitoringNoticeDetails.size)
        val text = monitoringNoticeDetails.joinToString { it.body }
        listOf("autorización válida", "no lee", "No ofrece un trámite integral", "responsable del tratamiento", "canal de solicitudes", "no constituye un aviso legal integral").forEach { assertTrue(it, text.contains(it, ignoreCase = true)) }
        assertFalse(text.contains("180 días"))
        assertFalse(text.contains("30 días"))
        assertFalse(text.contains("@"))
    }

    @Test fun `acceptance explicitly identifies informed authorization`() {
        assertEquals("Leí la información sobre el monitoreo y autorizo el tratamiento de los datos indicados para las finalidades informadas", MONITORING_ACCEPTANCE_TEXT)
    }
}
