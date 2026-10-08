package com.juan.habitos.data.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class InteractiveScreenObservationCalculatorTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val defaultStart = 1_000_000L
    private val defaultEnd = defaultStart + 24 * hour

    @Test fun `known non interactive initial state sums multiple intervals`() {
        val run = run(
            "day", defaultStart, defaultEnd,
            event(defaultStart + 8 * hour + 15 * minute, "SCREEN_INTERACTIVE"),
            event(defaultStart + 8 * hour + 40 * minute, "SCREEN_NON_INTERACTIVE"),
            event(defaultStart + 10 * hour, "SCREEN_INTERACTIVE"),
            event(defaultStart + 10 * hour + 12 * minute, "SCREEN_NON_INTERACTIVE"),
            event(defaultStart + 11 * hour, "ACTIVITY_RESUMED"),
        )

        val result = calculate(initial = "SCREEN_NON_INTERACTIVE", dayRuns = listOf(run))

        assertEquals(37L, result.interactiveScreenMinutes)
        assertEquals(37 * minute, result.interactiveScreenMillis)
        assertEquals(InteractiveScreenObservationStatus.OBSERVED, result.status)
        assertEquals(4, result.qualityEvidence.screenEventCount)
    }

    @Test fun `known interactive initial state begins interval exactly at day start`() {
        val run = run("day", defaultStart, defaultEnd, event(defaultStart + 25 * minute, "SCREEN_NON_INTERACTIVE"))

        val result = calculate(initial = "SCREEN_INTERACTIVE", dayRuns = listOf(run))

        assertEquals(25L, result.interactiveScreenMinutes)
        assertEquals(25 * minute, result.interactiveScreenMillis)
    }

    @Test fun `unknown initial state is incomplete and never zero`() {
        val run = run("day", defaultStart, defaultEnd)

        val result = calculate(initial = null, dayRuns = listOf(run))

        assertEquals(InteractiveScreenObservationStatus.INCOMPLETE, result.status)
        assertEquals(InteractiveScreenIncompleteReason.INITIAL_STATE_UNKNOWN, result.incompleteReason)
        assertNull(result.interactiveScreenMinutes)
        assertNull(result.interactiveScreenMillis)
    }

    @Test fun `interactive interval still open is closed exactly at day end`() {
        val run = run("day", defaultStart, defaultEnd, event(defaultEnd - 10 * minute, "SCREEN_INTERACTIVE"))

        val result = calculate(initial = "SCREEN_NON_INTERACTIVE", dayRuns = listOf(run))

        assertEquals(10L, result.interactiveScreenMinutes)
        assertEquals(10 * minute, result.interactiveScreenMillis)
    }

    @Test fun `valid empty capture with known non interactive state is observed zero`() {
        val result = calculate(
            initial = "SCREEN_NON_INTERACTIVE",
            dayRuns = listOf(run("empty", defaultStart, defaultEnd)),
        )

        assertEquals(InteractiveScreenObservationStatus.OBSERVED_ZERO, result.status)
        assertEquals(0L, result.interactiveScreenMinutes)
        assertEquals(0L, result.interactiveScreenMillis)
        assertTrue(result.qualityEvidence.coverageComplete)
    }

    @Test fun `gap between successful queries makes observation incomplete`() {
        val runs = listOf(
            run("a", defaultStart, defaultStart + 10 * hour),
            run("b", defaultStart + 11 * hour, defaultEnd),
        )

        val result = calculate(initial = "SCREEN_NON_INTERACTIVE", dayRuns = runs)

        assertEquals(InteractiveScreenObservationStatus.INCOMPLETE, result.status)
        assertEquals(InteractiveScreenIncompleteReason.COVERAGE_GAP, result.incompleteReason)
        assertNull(result.interactiveScreenMinutes)
        assertEquals(listOf(CoverageGap(defaultStart + 10 * hour, defaultStart + 11 * hour)), result.qualityEvidence.coverageGaps)
    }

    @Test fun `requested future after collection time is not treated as covered`() {
        val collectedAt = defaultStart + 12 * hour
        val run = UsageCollectorRunFactory.success(
            collectorRunId = "current-day",
            requestedStartEpochMillis = defaultStart,
            requestedEndEpochMillis = defaultEnd,
            collectedAtEpochMillis = collectedAt,
            androidVersion = "Android",
            deviceModel = "Phone",
            eventsInQueryOrder = emptyList(),
        )

        val result = calculate(initial = "SCREEN_NON_INTERACTIVE", dayRuns = listOf(run))

        assertEquals(InteractiveScreenObservationStatus.INCOMPLETE, result.status)
        assertEquals(InteractiveScreenIncompleteReason.COVERAGE_GAP, result.incompleteReason)
        assertEquals(listOf(CoverageGap(collectedAt, defaultEnd)), result.qualityEvidence.coverageGaps)
    }

    @Test fun `failed query does not invalidate another successful run covering same interval`() {
        val failed = UsageCollectorRunFactory.permissionUnavailable("failed", defaultStart, defaultEnd, 10, "Android", "Phone")
        val valid = run("valid", defaultStart, defaultEnd)

        val result = calculate(initial = "SCREEN_NON_INTERACTIVE", dayRuns = listOf(failed, valid))

        assertEquals(InteractiveScreenObservationStatus.OBSERVED_ZERO, result.status)
        assertTrue(result.qualityEvidence.coverageComplete)
        assertEquals(1, result.qualityEvidence.collectorRuns.count { it.status == CollectorRunQualityStatus.PERMISSION_UNAVAILABLE })
        assertTrue(result.qualityEvidence.collectorRuns.any { it.status == CollectorRunQualityStatus.SUCCESS })
    }

    @Test fun `overlapping queries use canonical half open segments without double counting`() {
        val interactive = defaultStart + 10 * hour + 30 * minute
        val nonInteractive = defaultStart + 11 * hour
        val a = run("a", defaultStart, defaultStart + 12 * hour,
            event(interactive, "SCREEN_INTERACTIVE"), event(nonInteractive, "SCREEN_NON_INTERACTIVE"))
        val b = run("b", defaultStart + 10 * hour, defaultEnd,
            event(interactive, "SCREEN_INTERACTIVE"), event(nonInteractive, "SCREEN_NON_INTERACTIVE"))

        val result = calculate(initial = "SCREEN_NON_INTERACTIVE", dayRuns = listOf(a, b))

        assertEquals(30L, result.interactiveScreenMinutes)
        assertEquals(
            listOf(
                CanonicalCoverageSegment("a", defaultStart, defaultStart + 12 * hour),
                CanonicalCoverageSegment("b", defaultStart + 12 * hour, defaultEnd),
            ),
            result.qualityEvidence.canonicalSegments,
        )
        assertEquals(2, result.qualityEvidence.screenEventCount)
    }

    @Test fun `events sharing timestamp remain ordered by query ordinal and are not deduplicated`() {
        val timestamp = defaultStart + hour
        val closesImmediately = run("close", defaultStart, defaultEnd,
            event(timestamp, "SCREEN_INTERACTIVE"), event(timestamp, "SCREEN_NON_INTERACTIVE"))
        val opensAfterClose = run("open", defaultStart, defaultEnd,
            event(timestamp, "SCREEN_NON_INTERACTIVE"), event(timestamp, "SCREEN_INTERACTIVE"))

        val first = calculate(initial = "SCREEN_NON_INTERACTIVE", dayRuns = listOf(closesImmediately))
        val second = calculate(initial = "SCREEN_NON_INTERACTIVE", dayRuns = listOf(opensAfterClose))

        assertEquals(InteractiveScreenObservationStatus.OBSERVED_ZERO, first.status)
        assertEquals(0L, first.interactiveScreenMillis)
        assertEquals(defaultEnd - timestamp, second.interactiveScreenMillis)
        assertEquals(2, first.qualityEvidence.screenEventCount)
        assertEquals(2, second.qualityEvidence.screenEventCount)
    }

    @Test fun `events outside half open day do not affect result`() {
        val run = run("day", defaultStart, defaultEnd,
            event(defaultStart - minute, "SCREEN_INTERACTIVE"),
            event(defaultEnd, "SCREEN_INTERACTIVE"),
            event(defaultEnd + minute, "SCREEN_NON_INTERACTIVE"))

        val result = calculate(initial = "SCREEN_NON_INTERACTIVE", dayRuns = listOf(run))

        assertEquals(InteractiveScreenObservationStatus.OBSERVED_ZERO, result.status)
        assertEquals(0, result.qualityEvidence.screenEventCount)
    }

    @Test fun `interval crossing midnight is allocated independently to each day`() {
        val midnight = defaultEnd
        val dayOneRun = run("day-1", defaultStart, midnight, event(midnight - 10 * minute, "SCREEN_INTERACTIVE"))
        val dayTwoRun = run("day-2", midnight, midnight + 24 * hour, event(midnight + 10 * minute, "SCREEN_NON_INTERACTIVE"))

        val dayOne = calculate(initial = "SCREEN_NON_INTERACTIVE", dayRuns = listOf(dayOneRun))
        val dayTwo = calculate(
            initial = "SCREEN_INTERACTIVE",
            dayRuns = listOf(dayTwoRun),
            dayStart = midnight,
            dayEnd = midnight + 24 * hour,
            previousTimestamp = midnight - 10 * minute,
        )

        assertEquals(10L, dayOne.interactiveScreenMinutes)
        assertEquals(10L, dayTwo.interactiveScreenMinutes)
    }

    @Test fun `non twenty four hour day uses supplied real epoch bounds`() {
        val date = LocalDate.of(2026, 3, 8)
        val zone = ZoneId.of("America/New_York")
        val window = DailyHistoricalUsageDiagnosticCollector.dayWindow(date, zone)
        val run = run("dst", window.dayStartEpochMillis, window.dayEndEpochMillis)

        val result = calculate(
            initial = "SCREEN_INTERACTIVE",
            dayRuns = listOf(run),
            dayStart = window.dayStartEpochMillis,
            dayEnd = window.dayEndEpochMillis,
        )

        assertEquals(23 * 60L, result.interactiveScreenMinutes)
        assertEquals(window.dayEndEpochMillis - window.dayStartEpochMillis, result.interactiveScreenMillis)
        assertEquals(result.qualityEvidence.expectedCoverageMillis, result.qualityEvidence.coveredMillis)
    }

    @Test fun `repeated screen states do not create duplicate or fictitious intervals`() {
        val run = run("day", defaultStart, defaultEnd,
            event(defaultStart + hour, "SCREEN_INTERACTIVE"),
            event(defaultStart + 2 * hour, "SCREEN_INTERACTIVE"),
            event(defaultStart + 3 * hour, "SCREEN_NON_INTERACTIVE"),
            event(defaultStart + 4 * hour, "SCREEN_NON_INTERACTIVE"))

        val result = calculate(initial = "SCREEN_NON_INTERACTIVE", dayRuns = listOf(run))

        assertEquals(2 * 60L, result.interactiveScreenMinutes)
        assertEquals(2 * hour, result.interactiveScreenMillis)
    }

    @Test fun `event exactly at day start is included`() {
        val run = run("day", defaultStart, defaultEnd,
            event(defaultStart, "SCREEN_INTERACTIVE"), event(defaultStart + 10 * minute, "SCREEN_NON_INTERACTIVE"))

        val result = calculate(initial = "SCREEN_NON_INTERACTIVE", dayRuns = listOf(run))

        assertEquals(10L, result.interactiveScreenMinutes)
    }

    @Test fun `event exactly at day end is excluded`() {
        val run = run("day", defaultStart, defaultEnd, event(defaultEnd, "SCREEN_INTERACTIVE"))

        val result = calculate(initial = "SCREEN_NON_INTERACTIVE", dayRuns = listOf(run))

        assertEquals(InteractiveScreenObservationStatus.OBSERVED_ZERO, result.status)
        assertEquals(0, result.qualityEvidence.screenEventCount)
    }

    @Test fun `millis are summed before explicit floor conversion to minutes`() {
        val duration = 90_001L
        val run = run("day", defaultStart, defaultEnd,
            event(defaultStart + minute, "SCREEN_INTERACTIVE"),
            event(defaultStart + minute + duration, "SCREEN_NON_INTERACTIVE"))

        val result = calculate(initial = "SCREEN_NON_INTERACTIVE", dayRuns = listOf(run))

        assertEquals(duration, result.interactiveScreenMillis)
        assertEquals(1L, result.interactiveScreenMinutes)
        assertEquals("FLOOR_AFTER_TOTAL_MILLIS", result.minuteConversion)
        assertEquals("android-interactive-screen-v1", result.calculationVersion)
    }

    @Test fun `positive sub minute duration is observed and not observed zero`() {
        val run = run("day", defaultStart, defaultEnd,
            event(defaultStart + minute, "SCREEN_INTERACTIVE"),
            event(defaultStart + minute + 1, "SCREEN_NON_INTERACTIVE"))

        val result = calculate(initial = "SCREEN_NON_INTERACTIVE", dayRuns = listOf(run))

        assertEquals(1L, result.interactiveScreenMillis)
        assertEquals(0L, result.interactiveScreenMinutes)
        assertEquals(InteractiveScreenObservationStatus.OBSERVED, result.status)
    }

    private fun calculate(
        initial: String?,
        dayRuns: List<UsageCollectorRun>,
        dayStart: Long = defaultStart,
        dayEnd: Long = defaultEnd,
        previousTimestamp: Long = dayStart - 1,
    ): DailyDeviceInteractiveScreenObservation {
        val previous = initial?.let {
            run("previous-$dayStart", dayStart - 7 * 24 * hour, dayStart, event(previousTimestamp, it))
        }
        val runs = listOfNotNull(previous) + dayRuns
        val diagnostic = DailyHistoricalUsageDiagnostic(
            profileId = "profile-1",
            deviceId = "device-1",
            localDate = "2026-10-07",
            timezoneId = "UTC",
            dayStartEpochMillis = dayStart,
            dayEndEpochMillis = dayEnd,
            previousScreenState = previous?.let { PreviousScreenStateEvidence(it.collectorRunId, it.events.single()) },
            collectorRuns = runs,
        )
        return InteractiveScreenObservationCalculator.calculate(diagnostic, calculatedAtEpochMillis = 999)
    }

    private fun event(timestamp: Long, name: String) = CollectedUsageEvent(
        timestampEpochMillis = timestamp,
        eventTypeCode = when (name) {
            "SCREEN_INTERACTIVE" -> 15
            "SCREEN_NON_INTERACTIVE" -> 16
            else -> 1
        },
        eventTypeName = name,
        packageName = null,
        className = null,
    )

    private fun run(
        id: String,
        start: Long,
        end: Long,
        vararg events: CollectedUsageEvent,
    ) = UsageCollectorRunFactory.success(
        collectorRunId = id,
        requestedStartEpochMillis = start,
        requestedEndEpochMillis = end,
        collectedAtEpochMillis = end + 1,
        androidVersion = "Android",
        deviceModel = "Phone",
        eventsInQueryOrder = events.toList(),
    )
}
