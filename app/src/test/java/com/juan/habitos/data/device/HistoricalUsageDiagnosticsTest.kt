package com.juan.habitos.data.device

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class HistoricalUsageDiagnosticsTest {
    @Test fun `collector run generates unique id and preserves range collection and device context`() {
        val first = successfulRun(start = 100, end = 200, collectedAt = 250)
        val second = successfulRun(start = 100, end = 200, collectedAt = 260)

        assertNotEquals(first.collectorRunId, second.collectorRunId)
        assertEquals(first.collectorRunId, UUID.fromString(first.collectorRunId).toString())
        assertEquals(100L, first.requestedStartEpochMillis)
        assertEquals(200L, first.requestedEndEpochMillis)
        assertEquals(250L, first.collectedAtEpochMillis)
        assertTrue(first.usagePermissionAvailable)
        assertFalse(first.queryReturnedNull)
        assertNull(first.errorCode)
        assertEquals("Android 15", first.androidVersion)
        assertEquals("Test Phone", first.deviceModel)
    }

    @Test fun `all events are exported and original ordinal survives timestamp sort`() {
        val run = successfulRun(
            events = listOf(
                CollectedUsageEvent(300, 99, "UNKNOWN_99", null, null),
                CollectedUsageEvent(100, UsageEvents.Event.SCREEN_INTERACTIVE, "SCREEN_INTERACTIVE", "android", "SystemUi"),
                CollectedUsageEvent(200, UsageEvents.Event.USER_INTERACTION, "USER_INTERACTION", "example.app", null),
            ),
        )

        assertEquals(3, run.eventCount)
        assertEquals(run.events.size, run.eventCount)
        assertEquals(listOf(100L, 200L, 300L), run.events.map { it.timestampEpochMillis })
        assertEquals(listOf(1, 2, 0), run.events.map { it.queryOrdinal })
        assertEquals(99, run.events.last().eventTypeCode)
        assertEquals("UNKNOWN_99", run.events.last().eventTypeName)
        assertNull(run.events.last().packageName)
        assertNull(run.events.last().className)
        assertEquals("SystemUi", run.events.first().className)
        assertTrue(run.events.all { it.collectorRunId == run.collectorRunId })
    }

    @Test fun `known and unknown event type names remain readable without dropping code`() {
        assertEquals("SCREEN_INTERACTIVE", AndroidDeviceUsageDataSource.eventName(15))
        assertEquals("SCREEN_NON_INTERACTIVE", AndroidDeviceUsageDataSource.eventName(16))
        assertEquals("ACTIVITY_STOPPED", AndroidDeviceUsageDataSource.eventName(23))
        assertEquals("EVENT_TYPE_987", AndroidDeviceUsageDataSource.eventName(987))
    }

    @Test fun `permission null exception and successful empty query are different outcomes`() {
        val permission = UsageCollectorRunFactory.permissionUnavailable("permission", 1, 2, 3, "Android", "Phone")
        val returnedNull = UsageCollectorRunFactory.queryReturnedNull("null", 1, 2, 3, "Android", "Phone")
        val exception = UsageCollectorRunFactory.exception("exception", 1, 2, 3, "Android", "Phone", IllegalStateException("query failed"))
        val empty = successfulRun(events = emptyList())

        assertFalse(permission.usagePermissionAvailable)
        assertFalse(permission.queryReturnedNull)
        assertEquals(CollectorErrorCode.USAGE_PERMISSION_NOT_AVAILABLE, permission.errorCode)
        assertTrue(returnedNull.usagePermissionAvailable)
        assertTrue(returnedNull.queryReturnedNull)
        assertEquals(CollectorErrorCode.QUERY_EVENTS_RETURNED_NULL, returnedNull.errorCode)
        assertTrue(exception.usagePermissionAvailable)
        assertFalse(exception.queryReturnedNull)
        assertEquals(CollectorErrorCode.QUERY_EVENTS_EXCEPTION, exception.errorCode)
        assertEquals("query failed", exception.errorMessage)
        assertTrue(empty.successful)
        assertEquals(0, empty.eventCount)
        assertNull(empty.errorCode)
    }

    @Test fun `daily bounds use local midnight and next midnight across DST`() {
        val date = LocalDate.of(2026, 11, 1)
        val zone = ZoneId.of("America/New_York")
        val window = DailyHistoricalUsageDiagnosticCollector.dayWindow(date, zone)

        assertEquals(date.atStartOfDay(zone).toInstant().toEpochMilli(), window.dayStartEpochMillis)
        assertEquals(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), window.dayEndEpochMillis)
        assertEquals(25 * 60 * 60 * 1_000L, window.dayEndEpochMillis - window.dayStartEpochMillis)
        assertEquals("America/New_York", window.timezoneId)
    }

    @Test fun `previous state keeps real event and references its complete successful run`() {
        val date = LocalDate.of(2026, 10, 7)
        val zone = ZoneId.of("America/Argentina/Buenos_Aires")
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val collector = object : UsageEventRunCollector {
            override fun collectRun(requestedStartEpochMillis: Long, requestedEndEpochMillis: Long): UsageCollectorRun {
                val events = if (requestedEndEpochMillis == dayStart) listOf(
                    CollectedUsageEvent(dayStart - 120_000, 15, "SCREEN_INTERACTIVE", null, null),
                    CollectedUsageEvent(dayStart - 60_000, 16, "SCREEN_NON_INTERACTIVE", "android", null),
                ) else emptyList()
                return successfulRun(start = requestedStartEpochMillis, end = requestedEndEpochMillis, events = events)
            }
        }

        val result = DailyHistoricalUsageDiagnosticCollector(collector, nowEpochMillis = { dayStart + 3_600_000 })
            .collect(date, zone, "profile-1", "device-1", includeOverlappingRuns = false)

        val evidence = assertNotNull(result.previousScreenState).let { result.previousScreenState!! }
        assertEquals("profile-1", result.profileId)
        assertEquals("device-1", result.deviceId)
        assertEquals("2026-10-07", result.localDate)
        assertEquals("America/Argentina/Buenos_Aires", result.timezoneId)
        assertEquals(dayStart, result.dayStartEpochMillis)
        assertEquals(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), result.dayEndEpochMillis)
        assertEquals(dayStart - 60_000L, evidence.event.timestampEpochMillis)
        assertEquals("SCREEN_NON_INTERACTIVE", evidence.event.eventTypeName)
        val completeRun = result.collectorRuns.single { it.collectorRunId == evidence.collectorRunId }
        assertTrue(completeRun.successful)
        assertEquals(2, completeRun.events.size)
        assertEquals(evidence.event, completeRun.events.last())
    }

    @Test fun `overlapping queries retain duplicate event with distinct run identity`() {
        val date = LocalDate.of(2026, 10, 7)
        val zone = ZoneId.of("UTC")
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = dayStart + 12 * 60 * 60 * 1_000L
        val sharedTimestamp = now - 45 * 60 * 1_000L
        val collector = object : UsageEventRunCollector {
            override fun collectRun(requestedStartEpochMillis: Long, requestedEndEpochMillis: Long): UsageCollectorRun {
                val events = if (sharedTimestamp in requestedStartEpochMillis until requestedEndEpochMillis) {
                    listOf(CollectedUsageEvent(sharedTimestamp, 15, "SCREEN_INTERACTIVE", "android", null))
                } else emptyList()
                return successfulRun(start = requestedStartEpochMillis, end = requestedEndEpochMillis, events = events)
            }
        }

        val result = DailyHistoricalUsageDiagnosticCollector(collector, nowEpochMillis = { now })
            .collect(date, zone, null, null, includeOverlappingRuns = true)
        val overlappingRuns = result.collectorRuns.takeLast(2)

        assertEquals(2, overlappingRuns.size)
        assertTrue(overlappingRuns.all { it.eventCount == 1 })
        assertNotEquals(overlappingRuns[0].collectorRunId, overlappingRuns[1].collectorRunId)
        assertEquals(sharedTimestamp, overlappingRuns[0].events.single().timestampEpochMillis)
        assertEquals(sharedTimestamp, overlappingRuns[1].events.single().timestampEpochMillis)
        assertEquals(overlappingRuns[0].collectorRunId, overlappingRuns[0].events.single().collectorRunId)
        assertEquals(overlappingRuns[1].collectorRunId, overlappingRuns[1].events.single().collectorRunId)
    }

    @Test fun `json keeps required fields nullability and no premature observation id`() {
        val date = LocalDate.of(2026, 10, 7)
        val zone = ZoneId.of("America/Argentina/Buenos_Aires")
        val run = successfulRun(
            events = listOf(CollectedUsageEvent(123, 15, "SCREEN_INTERACTIVE", null, null)),
        )
        val window = DailyHistoricalUsageDiagnosticCollector.dayWindow(date, zone)
        val value = DailyHistoricalUsageDiagnostic(
            profileId = "profile-1",
            deviceId = "device-1",
            localDate = date.toString(),
            timezoneId = zone.id,
            dayStartEpochMillis = window.dayStartEpochMillis,
            dayEndEpochMillis = window.dayEndEpochMillis,
            previousScreenState = PreviousScreenStateEvidence(run.collectorRunId, run.events.single()),
            collectorRuns = listOf(run),
        )

        val json = HistoricalUsageDiagnosticJson.encode(value)
        val reexportedJson = HistoricalUsageDiagnosticJson.encode(value)

        assertEquals(json, reexportedJson)
        listOf(
            "formatVersion", "profileId", "deviceId", "localDate", "timezoneId", "dayStartEpochMillis",
            "dayEndEpochMillis", "previousScreenState", "collectorRuns", "collectorRunId",
            "requestedStartEpochMillis", "requestedEndEpochMillis", "collectedAtEpochMillis",
            "usagePermissionAvailable", "queryReturnedNull", "eventCount", "errorCode", "errorMessage",
            "androidVersion", "deviceModel", "events", "timestampEpochMillis", "eventTypeCode",
            "eventTypeName", "queryOrdinal", "packageName", "className",
        ).forEach { field -> assertTrue("Missing $field", json.contains("\"$field\"")) }
        assertTrue(json.contains("\"packageName\": null"))
        assertTrue(json.contains("\"className\": null"))
        assertFalse(json.contains("historicalObservationId"))
    }

    private fun successfulRun(
        start: Long = 100,
        end: Long = 500,
        collectedAt: Long = 600,
        events: List<CollectedUsageEvent> = emptyList(),
    ) = UsageCollectorRunFactory.success(
        requestedStartEpochMillis = start,
        requestedEndEpochMillis = end,
        collectedAtEpochMillis = collectedAt,
        androidVersion = "Android 15",
        deviceModel = "Test Phone",
        eventsInQueryOrder = events,
    )
}
