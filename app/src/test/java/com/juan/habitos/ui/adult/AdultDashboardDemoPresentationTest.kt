package com.juan.habitos.ui.adult

import com.juan.habitos.domain.model.Profile
import com.juan.habitos.ui.state.AdultMessagesUiState
import com.juan.habitos.ui.state.dashboardDemoMessagesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdultDashboardDemoPresentationTest {
    @Test fun `dashboard local message uses explicit fictitious label`() {
        val state = dashboardDemoMessagesState(Profile("Santiago", "🦊", 8, id = "profile-1"))

        assertEquals("Ejemplo ficticio", adultMessagesDemoLabel(state))
    }

    @Test fun `real messages have no demo label`() {
        assertNull(adultMessagesDemoLabel(AdultMessagesUiState(demo = false)))
    }
}
