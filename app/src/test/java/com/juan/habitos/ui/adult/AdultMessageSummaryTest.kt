package com.juan.habitos.ui.adult

import com.juan.habitos.data.remote.AdultMessageHistory
import com.juan.habitos.data.remote.messageFixture
import com.juan.habitos.ui.state.AdultMessagesUiState
import org.junit.Assert.*
import org.junit.Test

class AdultMessageSummaryTest {
    @Test fun `summary uses only latest current message title including correction date ordering`() {
        val first = messageFixture().versions.first()
        val newer = first.copy(card = first.card.copy(cardId = "second", title = "Más reciente", evaluationDate = "2026-02-09"))
        val corrected = first.copy(version = 2, card = first.card.copy(title = "Título corregido", updatedDate = "2026-02-16"))
        val state = AdultMessagesUiState(profileId = first.card.profileId,
            history = AdultMessageHistory(listOf(newer, first, corrected)))

        assertEquals("Título corregido", adultMessageSummaryTitle(state))
        assertNull(adultMessageSummaryTitle(state.copy(loading = true)))
        assertNull(adultMessageSummaryTitle(state.copy(error = "Sin conexión")))
        assertNull(adultMessageSummaryTitle(state.copy(profileId = null)))
    }

    @Test fun `summary occupies no space for empty history or retired latest version`() {
        assertNull(adultMessageSummaryTitle(AdultMessagesUiState(profileId = "child", history = AdultMessageHistory(emptyList()))))
        val first = messageFixture().versions.first()
        for (status in listOf("WITHDRAWN", "CORRECTED_HISTORY", "RESOLVED", "EXPIRED", "SERIES_CHANGED")) {
            val latest = first.copy(version = 2, card = first.card.copy(status = status))
            assertNull(adultMessageSummaryTitle(AdultMessagesUiState(profileId = first.card.profileId,
                history = AdultMessageHistory(listOf(first, latest)))))
        }
    }
}
