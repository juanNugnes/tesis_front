package com.juan.habitos.data.device

import android.app.usage.UsageEvents
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.model.UsageMetricStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AggregatedUsageSnapshotMapperTest {
    private val zone = ZoneId.of("America/Argentina/Buenos_Aires")
    private val profile = Profile("Juana", "fox", 9, sleepStart = "22:00", sleepEnd = "07:00", id = "profile-1")

    @Test fun `maps Android aggregates to exact backend units timezone and identifiers`() {
        val snapshot = AggregatedUsageSnapshotMapper.map(
            evidence = AndroidAggregateEvidence(
                applications = listOf(
                    AndroidAppAggregate("com.video", "Video", 65_999),
                    AndroidAppAggregate("com.chat", "Chat", 59_999),
                ),
                events = listOf(
                    RawUsageEvent(1_000, null, 15, "SCREEN_INTERACTIVE"),
                    RawUsageEvent(61_000, null, 16, "SCREEN_NON_INTERACTIVE"),
                    RawUsageEvent(62_000, null, 18, "KEYGUARD_HIDDEN"),
                ),
                nightForegroundMillis = 60_999,
            ),
            profile = profile,
            capturedAt = Instant.parse("2026-10-16T20:00:00Z"),
            zoneId = zone,
            snapshotId = "7b2cb933-d975-4be9-8870-bd01112ef412",
        )

        assertEquals("7b2cb933-d975-4be9-8870-bd01112ef412", snapshot.snapshotId)
        assertEquals("2026-10-16", snapshot.date)
        assertEquals("America/Argentina/Buenos_Aires", snapshot.timezone)
        assertEquals("MINUTES", snapshot.durationUnit)
        assertEquals("ANDROID_USAGE_STATS_AGGREGATES", snapshot.measurementMethod)
        assertEquals("UNKNOWN", snapshot.coverageStatus)
        assertEquals(2, snapshot.totalUsageMinutes)
        assertEquals(1, snapshot.applications?.first { it.packageName == "com.video" }?.usageMinutes)
        assertEquals(0, snapshot.applications?.first { it.packageName == "com.chat" }?.usageMinutes)
        assertTrue(snapshot.applications.orEmpty().all { it.category == null })
        assertEquals(1, snapshot.nightUsageMinutes)
        assertEquals("MEASURED", snapshot.metricStatuses.totalUsageMinutes)
        assertTrue(snapshot.periodStartAt.endsWith("-03:00"))
        assertTrue(snapshot.periodEndAt.endsWith("-03:00"))
        assertFalse(Gson().toJson(snapshot).contains("profile_id"))
        assertFalse(Gson().toJson(snapshot).contains("device_id"))
        val explicitNullJson = GsonBuilder().serializeNulls().create().toJson(snapshot.copy(totalUsageMinutes = null))
        assertTrue(explicitNullJson.contains("\"total_usage_minutes\":null"))
    }

    @Test fun `permission denial remains null and is never converted to zero`() {
        val snapshot = AggregatedUsageSnapshotMapper.map(
            AndroidAggregateEvidence(null, null, null, UsageMetricStatus.PERMISSION_DENIED),
            profile,
            Instant.parse("2026-10-16T20:00:00Z"),
            zone,
            "snapshot-permission",
        )

        assertNull(snapshot.totalUsageMinutes)
        assertNull(snapshot.sessionCount)
        assertNull(snapshot.applications)
        assertEquals("PERMISSION_DENIED", snapshot.metricStatuses.totalUsageMinutes)
        assertEquals("PERMISSION_DENIED", snapshot.metricStatuses.applications)
        assertEquals("PERMISSION_DENIED", snapshot.metricStatuses.nightUsageMinutes)
    }

    @Test fun `missing night schedule is unavailable and has no invented window`() {
        val snapshot = AggregatedUsageSnapshotMapper.map(
            AndroidAggregateEvidence(emptyList(), emptyList(), null),
            profile.copy(sleepStart = null, sleepEnd = null),
            Instant.parse("2026-10-16T20:00:00Z"),
            zone,
            "snapshot-no-night",
        )

        assertNull(snapshot.nightUsageMinutes)
        assertNull(snapshot.nightWindow)
        assertEquals("UNAVAILABLE", snapshot.metricStatuses.nightUsageMinutes)
        assertEquals(0, snapshot.totalUsageMinutes)
    }

    @Test fun `null Android aggregates are capture errors rather than measured zero`() {
        val snapshot = AggregatedUsageSnapshotMapper.map(
            AndroidAggregateEvidence(applications = null, events = emptyList(), nightForegroundMillis = 0),
            profile,
            Instant.parse("2026-10-16T20:00:00Z"),
            zone,
            "snapshot-null-query",
        )

        assertNull(snapshot.totalUsageMinutes)
        assertNull(snapshot.applications)
        assertEquals("CAPTURE_ERROR", snapshot.metricStatuses.totalUsageMinutes)
        assertEquals("CAPTURE_ERROR", snapshot.metricStatuses.applications)
    }

    @Test fun `incomplete session evidence is marked partial per metric`() {
        val snapshot = AggregatedUsageSnapshotMapper.map(
            AndroidAggregateEvidence(
                applications = listOf(AndroidAppAggregate("com.video", null, 10 * 60_000L)),
                events = listOf(RawUsageEvent(1_000, null, 15, "SCREEN_INTERACTIVE")),
                nightForegroundMillis = 0,
            ),
            profile,
            Instant.parse("2026-10-16T20:00:00Z"),
            zone,
            "snapshot-partial",
        )

        assertEquals("INCOMPLETE", snapshot.metricStatuses.sessionCount)
        assertEquals("INCOMPLETE", snapshot.metricStatuses.maxSessionMinutes)
        assertEquals(0, snapshot.sessionCount)
        assertEquals("PARTIAL", snapshot.coverageStatus)
    }

    @Test fun `night window crossing midnight is clipped to the queried local day`() {
        val intervals = AggregatedUsageSnapshotMapper.nightIntervals(
            date = LocalDate.of(2026, 10, 16),
            periodStart = Instant.parse("2026-10-16T03:00:00Z"),
            periodEnd = Instant.parse("2026-10-17T02:00:00Z"),
            zoneId = zone,
            startTime = LocalTime.of(22, 0),
            endTime = LocalTime.of(7, 0),
        )

        assertEquals(2, intervals.size)
        assertEquals(Instant.parse("2026-10-16T03:00:00Z"), intervals[0].first)
        assertEquals(Instant.parse("2026-10-16T10:00:00Z"), intervals[0].second)
        assertEquals(Instant.parse("2026-10-17T01:00:00Z"), intervals[1].first)
        assertEquals(Instant.parse("2026-10-17T02:00:00Z"), intervals[1].second)
    }

    @Test fun `maps real nighttime pause with source timezone and dedicated status`() {
        val snapshot = AggregatedUsageSnapshotMapper.map(
            AndroidAggregateEvidence(
                applications = listOf(AndroidAppAggregate("com.video", "Video", 10 * 60_000L)),
                events = listOf(
                    RawUsageEvent(Instant.parse("2026-10-16T09:30:00Z").toEpochMilli(), "com.video", UsageEvents.Event.ACTIVITY_RESUMED, "ACTIVITY_RESUMED", "Player"),
                    RawUsageEvent(Instant.parse("2026-10-16T09:42:00Z").toEpochMilli(), "com.video", UsageEvents.Event.ACTIVITY_PAUSED, "ACTIVITY_PAUSED", "Player"),
                ),
                nightForegroundMillis = 10 * 60_000L,
            ),
            profile,
            Instant.parse("2026-10-16T20:00:00Z"),
            zone,
            "snapshot-last-night",
        )

        assertEquals("2026-10-16T06:42:00-03:00", snapshot.lastNightActivityAt)
        assertEquals("MEASURED", snapshot.metricStatuses.lastNightActivityAt)
        assertEquals("android-collector-1.1", snapshot.calculationVersion)
    }

    @Test fun `open nighttime application is incomplete and never receives a synthetic timestamp`() {
        val snapshot = AggregatedUsageSnapshotMapper.map(
            AndroidAggregateEvidence(
                applications = listOf(AndroidAppAggregate("com.video", "Video", 10 * 60_000L)),
                events = listOf(
                    RawUsageEvent(Instant.parse("2026-10-16T09:30:00Z").toEpochMilli(), "com.video", UsageEvents.Event.ACTIVITY_RESUMED, "ACTIVITY_RESUMED", "Player"),
                ),
                nightForegroundMillis = 10 * 60_000L,
            ),
            profile,
            Instant.parse("2026-10-16T20:00:00Z"),
            zone,
            "snapshot-open-night",
        )

        assertNull(snapshot.lastNightActivityAt)
        assertEquals("INCOMPLETE", snapshot.metricStatuses.lastNightActivityAt)
        assertEquals("PARTIAL", snapshot.coverageStatus)
    }
}
