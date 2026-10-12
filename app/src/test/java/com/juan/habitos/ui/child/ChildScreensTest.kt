package com.juan.habitos.ui.child

import com.juan.habitos.ui.state.ActivityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChildScreensTest {
    @Test fun `zero selections shows not selected message`() {
        assertEquals("Hoy todavía no elegiste actividades", activityHistoryMessage(0))
    }

    @Test fun `one selection uses singular`() {
        assertEquals("Hoy elegiste 1 actividad", activityHistoryMessage(1))
    }

    @Test fun `multiple selections use plural with real count`() {
        assertEquals("Hoy elegiste 4 actividades", activityHistoryMessage(4))
    }

    @Test fun `missing confirmed count shows neutral message`() {
        assertEquals("Tus actividades de hoy aparecerán acá", activityHistoryMessage(null))
    }

    @Test fun `eligibility unknown shows preferences message and action when navigation is available`() {
        val presentation = activityUnavailablePresentation(ActivityState.PreferencesRequired, navigationAvailable = true)!!
        assertEquals("Antes de mostrarte actividades, un adulto tiene que completar tus preferencias.", presentation.message)
        assertEquals("Completar preferencias", presentation.actionLabel)
    }

    @Test fun `eligibility unknown omits action when navigation is unavailable`() {
        assertNull(activityUnavailablePresentation(ActivityState.PreferencesRequired, navigationAvailable = false)!!.actionLabel)
    }

    @Test fun `no eligible activities uses neutral text without preferences action`() {
        val presentation = activityUnavailablePresentation(ActivityState.NoEligibleActivities, navigationAvailable = true)!!
        assertEquals("Por ahora no encontramos una actividad para mostrarte.", presentation.message)
        assertNull(presentation.actionLabel)
    }

    @Test fun `technical error never exposes preferences action`() {
        val presentation = activityUnavailablePresentation(ActivityState.Error("Temporal"), navigationAvailable = true)!!
        assertEquals("Temporal", presentation.message)
        assertNull(presentation.actionLabel)
    }
}
