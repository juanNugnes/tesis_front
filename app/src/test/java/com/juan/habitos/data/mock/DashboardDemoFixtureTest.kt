package com.juan.habitos.data.mock

import com.juan.habitos.domain.model.DashboardPeriod
import com.juan.habitos.domain.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardDemoFixtureTest {
    @Test fun `fixture contains four consistent weeks and only matches Santiago age eight`() {
        val fixture = dashboardDemoFixture

        assertEquals(28, fixture.history.size)
        assertTrue(fixture.history.all { day -> day.applications.sumOf { it.minutes } == day.totalMinutes })
        assertTrue(fixture.matches(Profile("Santiago", "🦊", 8)))
        assertTrue(fixture.matches(Profile("  santiago  ", "🦊", 8)))
        assertFalse(fixture.matches(Profile("Santiago", "🦊", 9)))
        assertFalse(fixture.matches(Profile("Otro perfil", "🦊", 8)))
    }

    @Test fun `today summary applications and rest data share one consistent source`() {
        val overview = dashboardDemoFixture.overview(DashboardPeriod.TODAY)

        assertEquals(140, overview.usage.currentMinutes)
        assertEquals(8, overview.usage.sessionCount)
        assertEquals(35, overview.usage.maxSessionMinutes)
        assertEquals(20, overview.usage.nightUsageMinutes)
        assertEquals("21:00", overview.nightWindowStart)
        assertEquals("07:00", overview.nightWindowEnd)
        assertEquals("22:30", dashboardDemoFixture.lastActivityLocalTime)
        assertEquals(
            listOf("YouTube Kids" to 55, "Roblox" to 30, "Khan Academy" to 35, "Chrome" to 20),
            overview.applications.orEmpty().map { it.name to it.minutes },
        )
        assertEquals(overview.usage.currentMinutes, overview.applications.orEmpty().sumOf { it.minutes })
    }

    @Test fun `week and analysis values are derived from the simulated history`() {
        val week = dashboardDemoFixture.overview(DashboardPeriod.WEEK)

        assertEquals(980, week.usage.currentMinutes)
        assertEquals(56, week.usage.sessionCount)
        assertEquals(40, week.usage.maxSessionMinutes)
        assertEquals(980, week.applications.orEmpty().sumOf { it.minutes })
        assertEquals(DashboardDemoComparison(100, 140), dashboardDemoFixture.durationComparison)
        assertEquals(40, dashboardDemoFixture.durationComparison.difference)
        assertEquals(40, dashboardDemoFixture.durationComparison.percentage)
        assertEquals(DashboardDemoComparison(6, 8), dashboardDemoFixture.sessionsComparison)
        assertEquals(2, dashboardDemoFixture.sessionsComparison.difference)
        assertEquals(33, dashboardDemoFixture.sessionsComparison.percentage)
        assertEquals(DashboardDemoComparison(10, 20), dashboardDemoFixture.restComparison)
        assertEquals(100, dashboardDemoFixture.restComparison.percentage)
    }

    @Test fun `dashboard demo flag is debug only and disabled unless explicitly requested`() {
        assertTrue(dashboardDemoEnabled(isDebug = true, dashboardDemoMode = true))
        assertFalse(dashboardDemoEnabled(isDebug = true, dashboardDemoMode = false))
        assertFalse(dashboardDemoEnabled(isDebug = false, dashboardDemoMode = true))
    }

    @Test fun `local accompaniment message derives its metrics from the fixture`() {
        val comparison = dashboardDemoFixture.durationComparison
        val history = dashboardDemoFixture.messageHistory("profile-demo")
        val card = history.versions.single().card

        assertEquals("profile-demo", card.profileId)
        assertEquals(comparison.habitual.toDouble(), card.statistics?.reference)
        assertEquals(comparison.recent.toDouble(), card.statistics?.recent)
        assertEquals(comparison.difference.toDouble(), card.statistics?.delta)
        assertEquals(comparison.percentage.toDouble(), card.statistics?.percentChange)
        assertTrue(card.text.contains("1 h 40 min"))
        assertTrue(card.text.contains("2 h 20 min"))
        assertTrue(card.measurementDetail.contains(DASHBOARD_DEMO_MESSAGE_LABEL))
        assertEquals("NOT_DELIVERED", card.deliveryStatus)
    }
}
