package com.juan.habitos.data.mock

import com.juan.habitos.domain.model.AlertDisplayStatus
import com.juan.habitos.domain.model.AlertOrigin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoAlertFixturesTest {
    @Test fun `active demo contains the three agreed families with exact comparisons and periods`() {
        val active = activeAlerts(demoAlertFixtures())

        assertEquals(listOf("demo-duration-01", "demo-rest-01", "demo-apps-01"), active.map { it.id })
        with(active[0]) {
            assertEquals("02/11/2026", evaluatedAt)
            assertTrue(description.contains("2 h 20 min por día"))
            assertTrue(description.contains("1 h 40 min"))
            assertTrue(description.contains("40 min más por día"))
            assertTrue(periods.contains("14 días cada período"))
            assertTrue(periods.contains("05/10 al 18/10"))
            assertTrue(periods.contains("19/10 al 01/11"))
        }
        with(active[1]) {
            assertEquals("02/11/2026", evaluatedAt)
            assertTrue(description.contains("20 min por noche"))
            assertTrue(description.contains("10 min por noche"))
            assertTrue(description.contains("21:00 a 07:00"))
            assertTrue(measurement.contains("siete noches evaluables"))
            assertTrue(periods.contains("26/10 al 01/11"))
            assertTrue(periods.contains("19/10 al 25/10"))
        }
        with(active[2]) {
            assertEquals("02/11/2026", evaluatedAt)
            assertTrue(description.contains("30 a 60 min por día"))
            assertTrue(description.contains("90 a 60 min por día"))
            assertTrue(measurement.contains("primer plano"))
            assertTrue(periods.contains("26/10 al 01/11"))
        }
    }

    @Test fun `every fixture is explicitly fictitious`() {
        val fixtures = demoAlertFixtures()

        assertEquals("Datos ficticios · Demostración", ALERTS_DEMO_HEADER)
        assertEquals("Ejemplo ficticio", ALERTS_DEMO_CARD_LABEL)
        assertTrue(fixtures.all { it.origin == AlertOrigin.DEMO_FICTITIOUS })
    }

    @Test fun `corrections and insufficient states stay outside active findings`() {
        val fixtures = demoAlertFixtures()
        val active = activeAlerts(fixtures)
        val review = reviewAlerts(fixtures)

        assertEquals(3, active.size)
        assertTrue(active.all { it.displayStatus == AlertDisplayStatus.ACTIVE })
        assertTrue(review.any { it.displayStatus == AlertDisplayStatus.INSUFFICIENT_WITH_PRIOR })
        assertTrue(review.any { it.displayStatus == AlertDisplayStatus.INSUFFICIENT_WITHOUT_PRIOR })
        assertTrue(review.any { it.displayStatus == AlertDisplayStatus.WITHDRAWN })
        assertTrue(review.any { it.displayStatus == AlertDisplayStatus.CORRECTED_CONTINUES })
        assertFalse(review.any { it.id in active.map { item -> item.id } })
    }

    @Test fun `alerts demo flag is independent and never enables fixtures in release`() = runTest {
        assertTrue(alertsDemoEnabled(isDebug = true, alertsDemoMode = true))
        assertTrue(alertRepositoryForMode(isDebug = true, alertsDemoMode = true) is FakeAlertRepository)
        assertFalse(alertsDemoEnabled(isDebug = true, alertsDemoMode = false))
        assertTrue(alertRepositoryForMode(isDebug = true, alertsDemoMode = false).alerts().isEmpty())
        assertFalse(alertsDemoEnabled(isDebug = false, alertsDemoMode = true))
        assertTrue(alertRepositoryForMode(isDebug = false, alertsDemoMode = true).alerts().isEmpty())
    }
}
