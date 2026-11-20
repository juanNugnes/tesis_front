package com.juan.habitos.diagnostics

import com.google.gson.JsonParser
import com.juan.habitos.data.device.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.*

class DiagnosticExportSampleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `yesterday follows device IANA date rather than UTC`() {
        val clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneId.of("America/Argentina/Buenos_Aires"))
        assertEquals(LocalDate.parse("2026-10-17"), DiagnosticExportDay.resolve(true, clock).date)
        assertEquals(LocalDate.parse("2026-10-18"), DiagnosticExportDay.resolve(false, clock).date)
        assertEquals(clock.zone, DiagnosticExportDay.resolve(true, clock).zone)
    }

    @Test fun `resolved sample date remains fixed across midnight and DST day is not forced to 24 hours`() {
        val zone = ZoneId.of("America/New_York")
        val before = Clock.fixed(Instant.parse("2026-11-02T04:59:59Z"), zone)
        val day = DiagnosticExportDay.resolve(true, before)
        val after = DiagnosticExportDay.resolve(true, Clock.offset(before, Duration.ofSeconds(2)))
        assertEquals(LocalDate.parse("2026-10-31"), day.date)
        assertEquals(LocalDate.parse("2026-11-01"), after.date)
        val window = DailyHistoricalUsageDiagnosticCollector.dayWindow(after.date, after.zone)
        assertEquals(25 * 3_600_000L, window.dayEndEpochMillis - window.dayStartEpochMillis)
    }

    @Test fun `both JSON files preserve shared capture evidence original events and full observation`() {
        val date = LocalDate.parse("2026-10-18")
        val zone = ZoneId.of("America/Argentina/Buenos_Aires")
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val collector = object : UsageEventRunCollector {
            override fun collectRun(requestedStartEpochMillis: Long, requestedEndEpochMillis: Long): UsageCollectorRun =
                UsageCollectorRunFactory.success(
                    requestedStartEpochMillis = requestedStartEpochMillis,
                    requestedEndEpochMillis = requestedEndEpochMillis,
                    collectedAtEpochMillis = start + 100_000_000,
                    androidVersion = "Test Android", deviceModel = "Test Phone",
                    eventsInQueryOrder = if (requestedEndEpochMillis == start) listOf(
                        CollectedUsageEvent(start - 1, 16, "SCREEN_NON_INTERACTIVE", "android", "System"),
                    ) else listOf(
                        CollectedUsageEvent(start + 60_000, 15, "SCREEN_INTERACTIVE", "example.app", "OriginalClass"),
                        CollectedUsageEvent(start + 120_000, 16, "SCREEN_NON_INTERACTIVE", "android", null),
                    ),
                )
        }
        val historical = DailyHistoricalUsageDiagnosticCollector(collector).collect(date, zone, "profile", "device", false)
        val sample = DiagnosticExportSample.from(historical, true)
        val observation = requireNotNull(sample.observation)
        assertEquals(historical.localDate, observation.localDate)
        assertEquals(historical.timezoneId, observation.timezoneId)
        assertEquals(historical.dayStartEpochMillis, observation.dayStartEpochMillis)
        assertEquals(historical.dayEndEpochMillis, observation.dayEndEpochMillis)
        assertEquals(historical.collectorRuns.map { it.collectorRunId }, observation.qualityEvidence.collectorRuns.map { it.collectorRunId })
        assertEquals(60_000L, observation.interactiveScreenMillis)
        val raw = JsonParser.parseString(sample.historicalJson).asJsonObject
        val event = raw.getAsJsonArray("collectorRuns")[1].asJsonObject.getAsJsonArray("events")[0].asJsonObject
        assertEquals("OriginalClass", event["className"].asString)
        assertEquals(0, event["queryOrdinal"].asInt)
        assertEquals(start + 60_000, event["timestampEpochMillis"].asLong)
        val json = DailyInteractiveScreenObservationJson.encode(observation)
        val compact = JsonParser.parseString(json).asJsonObject
        assertTrue(compact.has("missingReason"))
        assertTrue(compact.has("calculationVersion"))
        val evidence = compact.getAsJsonObject("qualityEvidence")
        listOf("collectorRuns", "coverageGaps", "coverageComplete", "restartInDay", "conflictingOverlaps", "ambiguousEventOrder").forEach { assertTrue(evidence.has(it)) }
        assertFalse(compact.has("quality_verified"))
        assertFalse(compact.has("assignment_verified"))
        val rawFile = HistoricalJsonFileExporter.prepare(temporary.root, sample.historicalJson, date)
        val compactFile = HistoricalJsonFileExporter.prepare(temporary.root, json, date, observation = true)
        assertEquals("modera-interactive-screen-observation-2026-10-18.json", compactFile.file.name)
        assertNotEquals(rawFile.file, compactFile.file)
        assertEquals(json, compactFile.file.readText())
        assertEquals(sample.historicalJson, rawFile.file.readText())
    }

    @Test fun `permission failure remains evidence and never becomes observed zero`() {
        val collector = object : UsageEventRunCollector {
            override fun collectRun(requestedStartEpochMillis: Long, requestedEndEpochMillis: Long) =
                UsageCollectorRunFactory.permissionUnavailable("$requestedStartEpochMillis", requestedStartEpochMillis, requestedEndEpochMillis, 1, "Android", "Phone")
        }
        val historical = DailyHistoricalUsageDiagnosticCollector(collector).collect(LocalDate.parse("2026-10-18"), ZoneId.of("UTC"), null, null, false)
        val sample = DiagnosticExportSample.from(historical, true)
        assertEquals(InteractiveScreenObservationStatus.MISSING, sample.observation?.status)
        assertNull(sample.observation?.interactiveScreenMillis)
        assertTrue(sample.historicalJson.contains("USAGE_ACCESS_NOT_GRANTED"))
        assertNull(DiagnosticExportSample.from(historical, false).observation)
    }
}
