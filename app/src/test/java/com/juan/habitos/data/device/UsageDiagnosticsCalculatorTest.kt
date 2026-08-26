package com.juan.habitos.data.device

import android.app.usage.UsageEvents
import org.junit.Assert.*
import org.junit.Test

class UsageDiagnosticsCalculatorTest {
    private fun event(time: Long, type: Int, packageName: String? = null) = RawUsageEvent(time, packageName, type, "test")

    @Test fun `one complete screen pair creates one session`() {
        val sessions = UsageDiagnosticsCalculator.sessions(listOf(event(1_000, UsageEvents.Event.SCREEN_INTERACTIVE), event(6_000, UsageEvents.Event.SCREEN_NON_INTERACTIVE)))
        assertEquals(listOf(DetectedSession(1_000, 6_000)), sessions)
    }

    @Test fun `multiple pairs create multiple sessions`() {
        val sessions = UsageDiagnosticsCalculator.sessions(listOf(event(1_000, UsageEvents.Event.SCREEN_INTERACTIVE), event(6_000, UsageEvents.Event.SCREEN_NON_INTERACTIVE), event(10_000, UsageEvents.Event.SCREEN_INTERACTIVE), event(20_000, UsageEvents.Event.SCREEN_NON_INTERACTIVE)))
        assertEquals(2, sessions.size)
    }

    @Test fun `initial closing event without opening is ignored`() {
        val sessions = UsageDiagnosticsCalculator.sessions(listOf(event(1_000, UsageEvents.Event.SCREEN_NON_INTERACTIVE), event(2_000, UsageEvents.Event.SCREEN_INTERACTIVE), event(4_000, UsageEvents.Event.SCREEN_NON_INTERACTIVE)))
        assertEquals(listOf(DetectedSession(2_000, 4_000)), sessions)
    }

    @Test fun `final opening event without closing is ignored`() {
        assertTrue(UsageDiagnosticsCalculator.sessions(listOf(event(1_000, UsageEvents.Event.SCREEN_INTERACTIVE))).isEmpty())
    }

    @Test fun `incomplete sessions are reported separately`() {
        val analysis = UsageDiagnosticsCalculator.analyzeSessions(listOf(event(1_000, UsageEvents.Event.SCREEN_NON_INTERACTIVE), event(2_000, UsageEvents.Event.SCREEN_INTERACTIVE)))
        assertTrue(analysis.completed.isEmpty()); assertEquals(listOf(IncompleteDeviceSession(null, 1_000), IncompleteDeviceSession(2_000, null)), analysis.incomplete)
    }

    @Test fun `duplicate screen events do not duplicate sessions`() {
        val sessions = UsageDiagnosticsCalculator.sessions(listOf(event(1_000, UsageEvents.Event.SCREEN_INTERACTIVE), event(2_000, UsageEvents.Event.SCREEN_INTERACTIVE), event(5_000, UsageEvents.Event.SCREEN_NON_INTERACTIVE), event(6_000, UsageEvents.Event.SCREEN_NON_INTERACTIVE)))
        assertEquals(listOf(DetectedSession(1_000, 5_000)), sessions)
    }

    @Test fun `summary calculates average and maximum session`() {
        val events = listOf(event(0, UsageEvents.Event.SCREEN_INTERACTIVE), event(10_000, UsageEvents.Event.SCREEN_NON_INTERACTIVE), event(20_000, UsageEvents.Event.SCREEN_INTERACTIVE), event(50_000, UsageEvents.Event.SCREEN_NON_INTERACTIVE))
        val summary = UsageDiagnosticsCalculator.summary(emptyList(), events, 50_000)
        assertEquals(2, summary.sessionCount); assertEquals(20_000L, summary.averageSessionMillis); assertEquals(30_000L, summary.maxSessionMillis)
    }

    @Test fun `unlock count is available only when keyguard hidden exists`() {
        assertNull(UsageDiagnosticsCalculator.unlockCount(emptyList()))
        assertEquals(2, UsageDiagnosticsCalculator.unlockCount(listOf(event(1, UsageEvents.Event.KEYGUARD_HIDDEN), event(2, UsageEvents.Event.KEYGUARD_HIDDEN))))
    }

    @Test fun `empty input exposes unavailable experimental metrics`() {
        val summary = UsageDiagnosticsCalculator.summary(emptyList(), emptyList(), 1_000)
        assertEquals(0, summary.totalForegroundMillis); assertEquals(0, summary.appCount); assertNull(summary.unlockCount); assertNull(summary.sessionCount); assertNull(summary.averageSessionMillis); assertNull(summary.maxSessionMillis)
    }

    @Test fun `multiple app openings create separate intervals and correct total`() {
        val events = listOf(event(1_000, UsageEvents.Event.ACTIVITY_RESUMED, "spotify"), event(4_000, UsageEvents.Event.ACTIVITY_PAUSED, "spotify"), event(10_000, UsageEvents.Event.ACTIVITY_RESUMED, "spotify"), event(15_000, UsageEvents.Event.ACTIVITY_STOPPED, "spotify"))
        val usage = UsageDiagnosticsCalculator.reconstructAppUsage(events, 20_000).single()
        assertEquals(listOf(AppForegroundInterval(1_000, 4_000), AppForegroundInterval(10_000, 15_000)), usage.intervals); assertEquals(8_000L, usage.totalMillis)
    }

    @Test fun `duplicate activity events do not duplicate foreground time`() {
        val events = listOf(event(1_000, UsageEvents.Event.ACTIVITY_RESUMED, "app"), event(2_000, UsageEvents.Event.ACTIVITY_RESUMED, "app"), event(6_000, UsageEvents.Event.ACTIVITY_PAUSED, "app"), event(7_000, UsageEvents.Event.ACTIVITY_STOPPED, "app"))
        val usage = UsageDiagnosticsCalculator.reconstructAppUsage(events, 10_000).single()
        assertEquals(listOf(AppForegroundInterval(1_000, 6_000)), usage.intervals); assertEquals(5_000L, usage.totalMillis)
    }
}
