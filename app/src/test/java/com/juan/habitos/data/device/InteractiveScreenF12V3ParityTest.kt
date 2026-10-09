package com.juan.habitos.data.device

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

class InteractiveScreenF12V3ParityTest {
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()

    @Test fun `real Kotlin calculator matches all shared F12 v3 vectors and exports comparator results`() {
        val fixturePath = System.getProperty("f12.vectors.path")
            ?.let(Path::of)
            ?: frontendRoot().parent.resolve("modera-modelo/tests/fixtures/interactive_screen_vectors_v3.json")
        require(Files.isRegularFile(fixturePath)) { "F12 v3 fixture not found: $fixturePath" }
        val fixture = Files.newBufferedReader(fixturePath).use { gson.fromJson(it, Fixture::class.java) }
        assertEquals("The shared reference must contain exactly 15 vectors", 15, fixture.vectors.size)

        val rows = fixture.vectors.map { vector ->
            val start = Instant.parse(vector.day_start_utc ?: fixture.default_day_start_utc).toEpochMilli()
            val end = Instant.parse(vector.day_end_utc ?: fixture.default_day_end_utc).toEpochMilli()
            val collectedAt = end + 5 * MINUTE_MILLIS
            val runs = vector.queries.map { query ->
                val queryStart = start + query.begin * MINUTE_MILLIS
                val queryEnd = start + query.end * MINUTE_MILLIS
                val events = query.events.map { values ->
                    val code = values[1]
                    RawCollectorEvent(
                        collectorRunId = query.id,
                        requestedStartEpochMillis = queryStart,
                        requestedEndEpochMillis = queryEnd,
                        collectedAtEpochMillis = collectedAt,
                        timestampEpochMillis = start + values[0] * MINUTE_MILLIS,
                        eventTypeCode = code.toInt(),
                        eventTypeName = eventName(code),
                        queryOrdinal = values[2].toInt(),
                        packageName = null,
                        className = null,
                    )
                }
                UsageCollectorRun(
                    collectorRunId = query.id,
                    requestedStartEpochMillis = queryStart,
                    requestedEndEpochMillis = queryEnd,
                    collectedAtEpochMillis = collectedAt,
                    usagePermissionAvailable = query.success,
                    queryReturnedNull = false,
                    eventCount = events.size,
                    errorCode = if (query.success) null else "QUERY_FAILED",
                    errorMessage = null,
                    androidVersion = "F12 fixture",
                    deviceModel = "JVM",
                    events = events,
                )
            }
            val zoneId = vector.time_zone ?: "UTC"
            val result = InteractiveScreenObservationCalculator.calculate(
                DailyHistoricalUsageDiagnostic(
                    profileId = null,
                    deviceId = "f12-parity-device",
                    localDate = Instant.ofEpochMilli(start).atZone(ZoneId.of(zoneId)).toLocalDate().toString(),
                    timezoneId = zoneId,
                    dayStartEpochMillis = start,
                    dayEndEpochMillis = end,
                    previousScreenState = null,
                    collectorRuns = runs,
                ),
                calculatedAtEpochMillis = collectedAt,
            )
            assertEquals("${vector.id}: status", vector.expected.status, result.status.name)
            assertEquals("${vector.id}: reason", vector.expected.reason, result.missingReason?.name)
            if (vector.expected.minutes == null) {
                assertNull("${vector.id}: minutes", result.interactiveScreenMinutes)
            } else {
                assertEquals(
                    "${vector.id}: minutes",
                    vector.expected.minutes,
                    checkNotNull(result.interactiveScreenMinutes),
                    0.000001,
                )
            }
            ComparatorRow(
                id = vector.id,
                status = result.status.name,
                minutes = result.interactiveScreenMinutes,
                reason = result.missingReason?.name,
            )
        }

        val outputPath = System.getProperty("f12.results.path")
            ?.let(Path::of)
            ?: frontendRoot().resolve("app/build/outputs/f12/android-results.json")
        Files.createDirectories(outputPath.parent)
        Files.newBufferedWriter(outputPath).use {
            gson.toJson(ComparatorSubmission(results = rows), it)
        }
    }

    private fun frontendRoot(): Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
        .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) && Files.isDirectory(it.resolve("app")) }
        ?: error("Unable to locate tesis_frontend root from ${Path.of("").toAbsolutePath()}")

    private fun eventName(code: Long): String = when (code.toInt()) {
        15 -> "SCREEN_INTERACTIVE"
        16 -> "SCREEN_NON_INTERACTIVE"
        26 -> "DEVICE_SHUTDOWN"
        27 -> "DEVICE_STARTUP"
        else -> "UNKNOWN_$code"
    }

    private data class Fixture(
        val default_day_start_utc: String,
        val default_day_end_utc: String,
        val vectors: List<Vector>,
    )

    private data class Vector(
        val id: String,
        val time_zone: String?,
        val day_start_utc: String?,
        val day_end_utc: String?,
        val queries: List<Query>,
        val expected: Expected,
    )

    private data class Query(
        val id: String,
        val begin: Long,
        val end: Long,
        val success: Boolean,
        val events: List<List<Long>>,
    )

    private data class Expected(
        val status: String,
        val minutes: Double?,
        val reason: String?,
    )

    private data class ComparatorSubmission(
        val schema_version: String = "interactive-screen-parity-v3-draft",
        val results: List<ComparatorRow>,
    )

    private data class ComparatorRow(
        val id: String,
        val status: String,
        val minutes: Double?,
        val reason: String?,
    )

    companion object {
        private const val MINUTE_MILLIS = 60_000L
    }
}
