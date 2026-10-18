package com.juan.habitos.data.device

import android.app.usage.UsageEvents
import com.juan.habitos.domain.model.UsageMetricStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastNightActivityCalculatorTest {
    private val interval = Instant.parse("2026-10-17T01:00:00Z") to Instant.parse("2026-10-17T10:00:00Z")

    @Test fun `latest observed pause is a measured nighttime activity closure`() {
        val result = LastNightActivityCalculator.calculate(
            listOf(
                event("2026-10-17T02:00:00Z", UsageEvents.Event.ACTIVITY_RESUMED, "video", "Player"),
                event("2026-10-17T02:42:00Z", UsageEvents.Event.ACTIVITY_PAUSED, "video", "Player"),
                event("2026-10-17T03:00:00Z", UsageEvents.Event.ACTIVITY_RESUMED, "chat", "Chat"),
                event("2026-10-17T03:15:00Z", UsageEvents.Event.ACTIVITY_PAUSED, "chat", "Chat"),
            ),
            listOf(interval),
        )

        assertEquals(UsageMetricStatus.MEASURED, result.status)
        assertEquals(Instant.parse("2026-10-17T03:15:00Z"), result.timestamp)
    }

    @Test fun `application opened before bedtime can close inside the night window`() {
        val result = LastNightActivityCalculator.calculate(
            listOf(
                event("2026-10-17T00:50:00Z", UsageEvents.Event.ACTIVITY_RESUMED, "video", "Player"),
                event("2026-10-17T01:20:00Z", UsageEvents.Event.ACTIVITY_PAUSED, "video", "Player"),
            ),
            listOf(interval),
        )

        assertEquals(UsageMetricStatus.MEASURED, result.status)
        assertEquals(Instant.parse("2026-10-17T01:20:00Z"), result.timestamp)
    }

    @Test fun `open session never substitutes capture or interval end for observed activity`() {
        val result = LastNightActivityCalculator.calculate(
            listOf(event("2026-10-17T03:00:00Z", UsageEvents.Event.ACTIVITY_RESUMED, "video", "Player")),
            listOf(interval),
        )

        assertEquals(UsageMetricStatus.INCOMPLETE, result.status)
        assertNull(result.timestamp)
    }

    @Test fun `application switch remains incomplete while newer application is open`() {
        val result = LastNightActivityCalculator.calculate(
            listOf(
                event("2026-10-17T02:00:00Z", UsageEvents.Event.ACTIVITY_RESUMED, "video", "Player"),
                event("2026-10-17T03:00:00Z", UsageEvents.Event.ACTIVITY_RESUMED, "chat", "Chat"),
                event("2026-10-17T03:00:00Z", UsageEvents.Event.ACTIVITY_PAUSED, "video", "Player"),
            ),
            listOf(interval),
        )

        assertEquals(UsageMetricStatus.INCOMPLETE, result.status)
        assertNull(result.timestamp)
    }

    @Test fun `pause after midnight is valid without inventing the missing resume`() {
        val result = LastNightActivityCalculator.calculate(
            listOf(event("2026-10-17T03:25:00Z", UsageEvents.Event.ACTIVITY_PAUSED, "video", "Player")),
            listOf(interval),
        )

        assertEquals(UsageMetricStatus.MEASURED, result.status)
        assertEquals(Instant.parse("2026-10-17T03:25:00Z"), result.timestamp)
    }

    @Test fun `no observed application closure is unavailable`() {
        val result = LastNightActivityCalculator.calculate(emptyList(), listOf(interval))

        assertEquals(UsageMetricStatus.UNAVAILABLE, result.status)
        assertNull(result.timestamp)
    }

    private fun event(at: String, type: Int, packageName: String, className: String) = RawUsageEvent(
        Instant.parse(at).toEpochMilli(),
        packageName,
        type,
        "test",
        className,
    )
}
