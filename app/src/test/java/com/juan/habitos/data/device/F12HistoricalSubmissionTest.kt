package com.juan.habitos.data.device

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class F12HistoricalSubmissionTest {
    @Test fun closedObservedDayBecomesExactBackendRepresentationWithStableIdentity() {
        val diagnostic = diagnostic(listOf(
            event(-30, 16, 0), event(60, 15, 1), event(120, 16, 2),
        ))
        val observation = InteractiveScreenObservationCalculator.calculate(diagnostic, END + 60_000)
        val first = F12HistoricalRequestFactory.create(diagnostic, observation)
        val second = F12HistoricalRequestFactory.create(diagnostic, observation)

        assertEquals(first, second)
        assertEquals("capture-test-v1", first.measurementVersion)
        assertEquals("UTC", first.timeZone)
        assertTrue(first.requestId.startsWith("f12-"))
        with(first.representation) {
            assertEquals("daily_use_duration_minutes_v2", representationSpecId)
            assertEquals("DAILY_USE_DURATION", phenomenon)
            assertEquals(Instant.ofEpochMilli(START).toString(), intervalStart)
            assertEquals(Instant.ofEpochMilli(END).toString(), intervalEnd)
            assertEquals("OBSERVED", status)
            assertEquals(60.0, value!!, 0.0)
            assertEquals("minutes", unit)
            assertNull(coverage.value)
            assertEquals("daily_capture_completeness_not_quantified", coverage.basis)
            assertTrue(qualityFlags.isEmpty())
            assertEquals("android-usage-events", provenance.sourceId)
            assertEquals("f12-backend-bridge-v1-candidate", provenance.sourceVersion)
            assertEquals("v2", provenance.adapterVersion)
            assertEquals(64, provenance.inputFingerprint.length)
        }
    }

    @Test fun missingEvidenceRemainsNullAndIsNeverConvertedToObservedZero() {
        val diagnostic = diagnostic(emptyList(), begin = START)
        val observation = InteractiveScreenObservationCalculator.calculate(diagnostic, END + 1)
        val request = F12HistoricalRequestFactory.create(diagnostic, observation)
        assertEquals("MISSING", request.representation.status)
        assertNull(request.representation.value)
        assertEquals(listOf("INCOMPLETE_DAILY_CAPTURE"), request.representation.qualityFlags)
    }

    @Test fun openDayIsRejectedBeforeARequestCanBeCreated() {
        val diagnostic = diagnostic(listOf(event(-30, 16, 0)))
        val observation = InteractiveScreenObservationCalculator.calculate(diagnostic, END - 1)
        assertThrows(IllegalArgumentException::class.java) {
            F12HistoricalRequestFactory.create(diagnostic, observation)
        }
    }

    private fun diagnostic(events: List<RawCollectorEvent>, begin: Long = START - 60 * 60_000) =
        DailyHistoricalUsageDiagnostic(
            profileId = "profile-1", deviceId = "device-1", localDate = "2026-10-05", timezoneId = "UTC",
            dayStartEpochMillis = START, dayEndEpochMillis = END, previousScreenState = null,
            collectorRuns = listOf(UsageCollectorRun(
                collectorRunId = "run-1", requestedStartEpochMillis = begin, requestedEndEpochMillis = END,
                collectedAtEpochMillis = END + 1, usagePermissionAvailable = true, queryReturnedNull = false,
                eventCount = events.size, errorCode = null, errorMessage = null, androidVersion = "test",
                deviceModel = "test", events = events,
            )),
        )

    private fun event(minutes: Long, code: Int, ordinal: Int) = RawCollectorEvent(
        collectorRunId = "run-1", requestedStartEpochMillis = START - 60 * 60_000, requestedEndEpochMillis = END,
        collectedAtEpochMillis = END + 1, timestampEpochMillis = START + minutes * 60_000,
        eventTypeCode = code, eventTypeName = if (code == 15) "SCREEN_INTERACTIVE" else "SCREEN_NON_INTERACTIVE",
        queryOrdinal = ordinal, packageName = null, className = null,
    )

    companion object {
        private val START = Instant.parse("2026-10-05T00:00:00Z").toEpochMilli()
        private val END = Instant.parse("2026-10-06T00:00:00Z").toEpochMilli()
    }
}
