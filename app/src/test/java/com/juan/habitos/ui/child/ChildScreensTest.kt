package com.juan.habitos.ui.child

import org.junit.Assert.assertEquals
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
}
