package com.juan.habitos.ui.adult

import com.juan.habitos.domain.model.ProfileContext
import com.juan.habitos.domain.model.ProfileContextType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ProfileContextFormattingTest {
    @Test fun `every technical context type has its friendly label`() {
        assertEquals("Vacaciones", profileContextTypeLabel(ProfileContextType.VACATION))
        assertEquals("Enfermedad", profileContextTypeLabel(ProfileContextType.ILLNESS))
        assertEquals("Actividad escolar", profileContextTypeLabel(ProfileContextType.SCHOOL_ACTIVITY))
        assertEquals("Cambio de rutina", profileContextTypeLabel(ProfileContextType.ROUTINE_CHANGE))
        assertEquals("Otro", profileContextTypeLabel(ProfileContextType.OTHER))
    }

    @Test fun `context dates use friendly range and open ended formats`() {
        val start = LocalDate.of(2026, 10, 4)
        assertEquals(
            "04/10/2026 - 10/10/2026",
            formatContextRange(ProfileContext("range", ProfileContextType.VACATION, start, LocalDate.of(2026, 10, 10))),
        )
        assertEquals(
            "Desde 04/10/2026",
            formatContextRange(ProfileContext("open", ProfileContextType.ROUTINE_CHANGE, start, null)),
        )
    }
}
