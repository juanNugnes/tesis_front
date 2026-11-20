package com.juan.habitos.data.device

import com.google.gson.JsonParser
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class F12RawEvidenceTest {
    @Test fun `export uses exact Analytics summary and JSONL fields`() {
        val run = run(
            events = listOf(
                CollectedUsageEvent(1_100, 16, "SCREEN_NON_INTERACTIVE", null, null),
                CollectedUsageEvent(1_200, 15, "SCREEN_INTERACTIVE", "example.app", "MainActivity"),
            ),
        )

        val pair = F12RawEvidenceContract.export(run)
        val summary = JsonParser.parseString(pair.summaryBytes.toString(Charsets.UTF_8)).asJsonObject
        val rows = pair.eventsBytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank)
            .map { JsonParser.parseString(it).asJsonObject }.toList()

        assertEquals(
            setOf(
                "collectorRunId", "requestedBeginEpochMs", "requestedEndEpochMs", "collectedAtEpochMs",
                "usageAccessAvailable", "queryReturnedNull", "eventCount", "errorCode", "errorMessage",
            ),
            summary.keySet(),
        )
        assertEquals(
            setOf(
                "schemaVersion", "collectorRunId", "queryBeginEpochMs", "queryEndEpochMs", "collectedAtEpochMs",
                "eventTimeEpochMs", "eventTypeCode", "eventTypeName", "queryOrdinal", "packageName", "className",
                "androidApiLevel", "manufacturer", "model",
            ),
            rows.single { it["queryOrdinal"].asInt == 0 }.keySet(),
        )
        assertEquals(F12_RAW_EVENT_SCHEMA_VERSION, rows[0]["schemaVersion"].asString)
        assertTrue(rows[0]["packageName"].isJsonNull)
        assertEquals("example.app", rows[1]["packageName"].asString)
        assertEquals(35, rows[1]["androidApiLevel"].asInt)
        assertEquals("SYNTHETIC", rows[1]["manufacturer"].asString)
        assertEquals("NOT-A-PHONE", rows[1]["model"].asString)
        assertEquals(pair.summaryBytes.sha256(), pair.summarySha256)
        assertEquals(pair.eventsBytes.sha256(), pair.eventsSha256)
        assertTrue(pair.eventsBytes.toString(Charsets.UTF_8).endsWith("\n"))
    }

    @Test fun `API 28 aliases and unknown values match Analytics mapper`() {
        assertEquals("MOVE_TO_FOREGROUND", UsageEventTypeNameMapper.nameFor(1, 28))
        assertEquals("ACTIVITY_RESUMED", UsageEventTypeNameMapper.nameFor(1, 29))
        assertEquals("EVENT_TYPE_27", UsageEventTypeNameMapper.nameFor(27, 28))
        assertEquals("DEVICE_STARTUP", UsageEventTypeNameMapper.nameFor(27, 35))
        assertEquals("EVENT_TYPE_987", UsageEventTypeNameMapper.nameFor(987, 35))
    }

    @Test fun `factory preserves query ordinals while sorting out of order events`() {
        val run = run(
            events = listOf(
                CollectedUsageEvent(1_200, 15, "SCREEN_INTERACTIVE", null, null),
                CollectedUsageEvent(1_100, 16, "SCREEN_NON_INTERACTIVE", null, null),
            ),
        )

        val pair = F12RawEvidenceContract.export(run)
        val rows = pair.eventsBytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank)
            .map { JsonParser.parseString(it).asJsonObject }.toList()

        assertEquals(listOf(1_100L, 1_200L), rows.map { it["eventTimeEpochMs"].asLong })
        assertEquals(listOf(1, 0), rows.map { it["queryOrdinal"].asInt })
    }

    @Test fun `empty successful capture remains explicit and does not claim completeness`() {
        val diagnostic = diagnostic(previous = run(start = 100, end = 1_000), day = run(events = emptyList()))

        val record = F12RawEvidenceContract.record(diagnostic, revision = 1, createdAt = Instant.ofEpochMilli(3_000))

        assertEquals(F12CaptureAssessment.NO_EVENTS_OBSERVED, record.quality.assessment)
        assertTrue(record.quality.dayQuerySucceeded)
        assertFalse(record.quality.priorScreenStateObserved)
        assertTrue("DAY_COMPLETENESS_NOT_ASSERTED" in record.quality.qualityFlags)
        assertTrue("NO_EVENTS_OBSERVED" in record.quality.qualityFlags)
        assertTrue(record.exportPairs.all { it.eventsBytes.isEmpty() })
    }

    @Test fun `failed previous query is retained and marks partial capture`() {
        val previous = UsageCollectorRunFactory.permissionUnavailable(
            requestedStartEpochMillis = 100,
            requestedEndEpochMillis = 1_000,
            collectedAtEpochMillis = 2_100,
            androidVersion = "Android 15",
            deviceModel = "NOT-A-PHONE",
            androidApiLevel = 35,
            manufacturer = "SYNTHETIC",
        )
        val day = run(events = listOf(CollectedUsageEvent(1_100, 15, "SCREEN_INTERACTIVE", null, null)))

        val record = F12RawEvidenceContract.record(diagnostic(previous, day), 1)
        val previousSummary = JsonParser.parseString(record.exportPairs.first().summaryBytes.toString(Charsets.UTF_8)).asJsonObject

        assertEquals(F12CaptureAssessment.PARTIAL_CAPTURE, record.quality.assessment)
        assertEquals("USAGE_ACCESS_NOT_GRANTED", previousSummary["errorCode"].asString)
        assertEquals(0, previousSummary["eventCount"].asInt)
    }

    @Test fun `restart is preserved and flagged without inferring continuity`() {
        val day = run(events = listOf(CollectedUsageEvent(1_100, 27, "DEVICE_STARTUP", null, null)))

        val record = F12RawEvidenceContract.record(diagnostic(run(start = 100, end = 1_000), day), 1)

        assertTrue(record.quality.deviceRestartObserved)
        assertTrue("DEVICE_LIFECYCLE_REVIEW_REQUIRED" in record.quality.qualityFlags)
        assertTrue("DAY_COMPLETENESS_NOT_ASSERTED" in record.quality.qualityFlags)
    }

    @Test fun `contract rejects mismatched names and events outside query`() {
        val wrongName = run(events = listOf(CollectedUsageEvent(1_100, 27, "EVENT_TYPE_27", null, null)))
        val outside = run(events = listOf(CollectedUsageEvent(2_000, 16, "SCREEN_NON_INTERACTIVE", null, null)))

        assertThrows(IllegalArgumentException::class.java) { F12RawEvidenceContract.export(wrongName) }
        assertThrows(IllegalArgumentException::class.java) { F12RawEvidenceContract.export(outside) }
    }

    private fun diagnostic(previous: UsageCollectorRun, day: UsageCollectorRun) = DailyHistoricalUsageDiagnostic(
        profileId = "profile-1",
        deviceId = "device-1",
        localDate = "1970-01-01",
        timezoneId = "UTC",
        dayStartEpochMillis = 1_000,
        dayEndEpochMillis = 2_000,
        previousScreenState = null,
        collectorRuns = listOf(previous, day),
    )

    private fun run(
        start: Long = 1_000,
        end: Long = 2_000,
        events: List<CollectedUsageEvent> = emptyList(),
    ) = UsageCollectorRunFactory.success(
        collectorRunId = if (start < 1_000) {
            "a12f9120-7641-4ae2-a988-d6c43d4cfd17"
        } else {
            "e12f9120-7641-4ae2-a988-d6c43d4cfd17"
        },
        requestedStartEpochMillis = start,
        requestedEndEpochMillis = end,
        collectedAtEpochMillis = 2_100,
        androidVersion = "Android 15",
        deviceModel = "NOT-A-PHONE",
        eventsInQueryOrder = events,
        androidApiLevel = 35,
        manufacturer = "SYNTHETIC",
    )
}
