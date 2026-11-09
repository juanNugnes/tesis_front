package com.juan.habitos.ui.adult

import com.juan.habitos.domain.model.Alert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AdultAlertsPresentationTest {
    private val messageAlert = AdultMessageDemoAlert(
        alert = Alert("message-1", "MENSAJES", "📈", "Título", "Detalle", "", ""),
        cardId = "card-1",
        version = 2,
    )

    @Test fun `alerts demo suppresses duplicate duration message but regular tab keeps it`() {
        assertNull(adultMessageAlertForAlertsTab(alertsDemo = true, messageAlert))
        assertSame(messageAlert, adultMessageAlertForAlertsTab(alertsDemo = false, messageAlert))
    }

    @Test fun `adult message alert still opens its analysis version`() {
        var selectedMessage: Pair<String, Int>? = null
        var selectedTab: Int? = null

        openAdultMessageDemoAlert(
            target = messageAlert,
            selectMessage = { id, version -> selectedMessage = id to version },
            selectTab = { selectedTab = it },
        )

        assertEquals("card-1" to 2, selectedMessage)
        assertEquals(ANALYSIS_TAB, selectedTab)
    }
}
