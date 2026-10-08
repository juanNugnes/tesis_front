package com.juan.habitos.data.device

import com.google.gson.GsonBuilder

const val INTERACTIVE_SCREEN_CALCULATION_VERSION = "android-interactive-screen-v1"
const val INTERACTIVE_SCREEN_MINUTE_CONVERSION = "FLOOR_AFTER_TOTAL_MILLIS"

enum class InteractiveScreenObservationStatus { OBSERVED, OBSERVED_ZERO, INCOMPLETE }
enum class InteractiveScreenIncompleteReason { INITIAL_STATE_UNKNOWN, COVERAGE_GAP }
enum class CollectorRunQualityStatus { SUCCESS, PERMISSION_UNAVAILABLE, QUERY_RETURNED_NULL, ERROR }

data class CollectorRunQualityEvidence(
    val collectorRunId: String,
    val requestedStartEpochMillis: Long,
    val requestedEndEpochMillis: Long,
    val collectedAtEpochMillis: Long,
    val status: CollectorRunQualityStatus,
    val errorCode: String?,
    val errorMessage: String?,
    val eventCount: Int,
)

data class CanonicalCoverageSegment(
    val collectorRunId: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)

data class CoverageGap(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)

data class InitialScreenStateEvidence(
    val collectorRunId: String,
    val timestampEpochMillis: Long,
    val eventTypeCode: Int,
    val eventTypeName: String,
    val queryOrdinal: Int,
)

data class InteractiveScreenQualityEvidence(
    val collectorRuns: List<CollectorRunQualityEvidence>,
    val canonicalSegments: List<CanonicalCoverageSegment>,
    val coverageGaps: List<CoverageGap>,
    val coverageComplete: Boolean,
    val expectedCoverageMillis: Long,
    val coveredMillis: Long,
    val screenEventCount: Int,
    val initialScreenState: InitialScreenStateEvidence?,
)

/** Technical device-display observation. It does not identify or attribute a physical user. */
data class DailyDeviceInteractiveScreenObservation(
    val profileId: String?,
    val deviceId: String?,
    val localDate: String,
    val timezoneId: String,
    val dayStartEpochMillis: Long,
    val dayEndEpochMillis: Long,
    val interactiveScreenMinutes: Long?,
    val interactiveScreenMillis: Long?,
    val minuteConversion: String = INTERACTIVE_SCREEN_MINUTE_CONVERSION,
    val calculationVersion: String = INTERACTIVE_SCREEN_CALCULATION_VERSION,
    val status: InteractiveScreenObservationStatus,
    val incompleteReason: InteractiveScreenIncompleteReason?,
    val calculatedAtEpochMillis: Long,
    val qualityEvidence: InteractiveScreenQualityEvidence,
)

object DailyInteractiveScreenObservationJson {
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    fun encode(value: DailyDeviceInteractiveScreenObservation): String = gson.toJson(value)
}

object InteractiveScreenObservationCalculator {
    private const val SCREEN_INTERACTIVE = "SCREEN_INTERACTIVE"
    private const val SCREEN_NON_INTERACTIVE = "SCREEN_NON_INTERACTIVE"
    private const val MILLIS_PER_MINUTE = 60_000L

    fun calculate(
        diagnostic: DailyHistoricalUsageDiagnostic,
        calculatedAtEpochMillis: Long = System.currentTimeMillis(),
    ): DailyDeviceInteractiveScreenObservation {
        require(diagnostic.dayEndEpochMillis > diagnostic.dayStartEpochMillis) {
            "dayEndEpochMillis must be after dayStartEpochMillis"
        }
        val runEvidence = diagnostic.collectorRuns.map { it.toQualityEvidence() }
        val coverage = canonicalCoverage(
            diagnostic.collectorRuns.filter { it.successful },
            diagnostic.dayStartEpochMillis,
            diagnostic.dayEndEpochMillis,
        )
        val initialState = validatedInitialState(diagnostic)
        val canonicalEvents = canonicalEvents(coverage.segments, diagnostic.collectorRuns)
        val quality = InteractiveScreenQualityEvidence(
            collectorRuns = runEvidence,
            canonicalSegments = coverage.segments,
            coverageGaps = coverage.gaps,
            coverageComplete = coverage.gaps.isEmpty(),
            expectedCoverageMillis = diagnostic.dayEndEpochMillis - diagnostic.dayStartEpochMillis,
            coveredMillis = coverage.segments.sumOf { it.endEpochMillis - it.startEpochMillis },
            screenEventCount = canonicalEvents.count { it.isScreenStateEvent() },
            initialScreenState = initialState?.toEvidence(),
        )
        val incompleteReason = when {
            initialState == null -> InteractiveScreenIncompleteReason.INITIAL_STATE_UNKNOWN
            coverage.gaps.isNotEmpty() -> InteractiveScreenIncompleteReason.COVERAGE_GAP
            else -> null
        }
        if (incompleteReason != null) {
            return baseObservation(
                diagnostic = diagnostic,
                calculatedAtEpochMillis = calculatedAtEpochMillis,
                interactiveScreenMillis = null,
                status = InteractiveScreenObservationStatus.INCOMPLETE,
                incompleteReason = incompleteReason,
                quality = quality,
            )
        }

        var interactive = initialState!!.eventTypeName == SCREEN_INTERACTIVE
        var intervalStart = diagnostic.dayStartEpochMillis.takeIf { interactive }
        var totalInteractiveMillis = 0L
        canonicalEvents.forEach { event ->
            when (event.eventTypeName) {
                SCREEN_INTERACTIVE -> if (!interactive) {
                    interactive = true
                    intervalStart = event.timestampEpochMillis
                }
                SCREEN_NON_INTERACTIVE -> if (interactive) {
                    val start = checkNotNull(intervalStart)
                    totalInteractiveMillis += (event.timestampEpochMillis - start).coerceAtLeast(0)
                    interactive = false
                    intervalStart = null
                }
            }
        }
        if (interactive) {
            totalInteractiveMillis += (diagnostic.dayEndEpochMillis - checkNotNull(intervalStart)).coerceAtLeast(0)
        }
        return baseObservation(
            diagnostic = diagnostic,
            calculatedAtEpochMillis = calculatedAtEpochMillis,
            interactiveScreenMillis = totalInteractiveMillis,
            status = if (totalInteractiveMillis == 0L) InteractiveScreenObservationStatus.OBSERVED_ZERO else InteractiveScreenObservationStatus.OBSERVED,
            incompleteReason = null,
            quality = quality,
        )
    }

    private fun baseObservation(
        diagnostic: DailyHistoricalUsageDiagnostic,
        calculatedAtEpochMillis: Long,
        interactiveScreenMillis: Long?,
        status: InteractiveScreenObservationStatus,
        incompleteReason: InteractiveScreenIncompleteReason?,
        quality: InteractiveScreenQualityEvidence,
    ) = DailyDeviceInteractiveScreenObservation(
        profileId = diagnostic.profileId,
        deviceId = diagnostic.deviceId,
        localDate = diagnostic.localDate,
        timezoneId = diagnostic.timezoneId,
        dayStartEpochMillis = diagnostic.dayStartEpochMillis,
        dayEndEpochMillis = diagnostic.dayEndEpochMillis,
        interactiveScreenMinutes = interactiveScreenMillis?.div(MILLIS_PER_MINUTE),
        interactiveScreenMillis = interactiveScreenMillis,
        status = status,
        incompleteReason = incompleteReason,
        calculatedAtEpochMillis = calculatedAtEpochMillis,
        qualityEvidence = quality,
    )

    private fun validatedInitialState(diagnostic: DailyHistoricalUsageDiagnostic): RawCollectorEvent? {
        val evidence = diagnostic.previousScreenState ?: return null
        val run = diagnostic.collectorRuns.firstOrNull { it.collectorRunId == evidence.collectorRunId }
            ?.takeIf {
                it.successful &&
                    it.requestedEndEpochMillis >= diagnostic.dayStartEpochMillis &&
                    it.collectedAtEpochMillis >= diagnostic.dayStartEpochMillis
            }
            ?: return null
        val event = evidence.event.takeIf {
            it.collectorRunId == run.collectorRunId &&
                it.timestampEpochMillis < diagnostic.dayStartEpochMillis &&
                it.timestampEpochMillis >= run.requestedStartEpochMillis &&
                it.timestampEpochMillis < run.requestedEndEpochMillis &&
                it.isScreenStateEvent() &&
                it in run.events
        } ?: return null
        val lastScreenEvent = run.events
            .asSequence()
            .filter { it.timestampEpochMillis < diagnostic.dayStartEpochMillis && it.isScreenStateEvent() }
            .maxWithOrNull(compareBy<RawCollectorEvent> { it.timestampEpochMillis }.thenBy { it.queryOrdinal })
        return event.takeIf { it == lastScreenEvent }
    }

    private fun canonicalCoverage(
        successfulRuns: List<UsageCollectorRun>,
        dayStart: Long,
        dayEnd: Long,
    ): CoverageResult {
        val usable = successfulRuns.mapNotNull { run ->
            val effectiveStart = maxOf(run.requestedStartEpochMillis, dayStart)
            val effectiveEnd = minOf(run.requestedEndEpochMillis, run.collectedAtEpochMillis, dayEnd)
            EffectiveRunCoverage(run, effectiveStart, effectiveEnd).takeIf { effectiveEnd > effectiveStart }
        }
        val segments = mutableListOf<CanonicalCoverageSegment>()
        val gaps = mutableListOf<CoverageGap>()
        var cursor = dayStart
        while (cursor < dayEnd) {
            val selected = usable
                .asSequence()
                .filter { it.startEpochMillis <= cursor && it.endEpochMillis > cursor }
                .sortedWith(
                    compareByDescending<EffectiveRunCoverage> { it.endEpochMillis }
                        .thenBy { it.startEpochMillis }
                        .thenBy { it.run.collectedAtEpochMillis }
                        .thenBy { it.run.collectorRunId }
                )
                .firstOrNull()
            if (selected == null) {
                val nextStart = usable.asSequence()
                    .map { it.startEpochMillis }
                    .filter { it > cursor }
                    .minOrNull()
                    ?.coerceAtMost(dayEnd)
                    ?: dayEnd
                gaps += CoverageGap(cursor, nextStart)
                cursor = nextStart
            } else {
                val segmentEnd = selected.endEpochMillis
                segments += CanonicalCoverageSegment(selected.run.collectorRunId, cursor, segmentEnd)
                cursor = segmentEnd
            }
        }
        return CoverageResult(segments, gaps)
    }

    private fun canonicalEvents(
        segments: List<CanonicalCoverageSegment>,
        runs: List<UsageCollectorRun>,
    ): List<RawCollectorEvent> {
        val runsById = runs.associateBy { it.collectorRunId }
        return segments.flatMap { segment ->
            runsById.getValue(segment.collectorRunId).events
                .asSequence()
                .filter { it.timestampEpochMillis >= segment.startEpochMillis && it.timestampEpochMillis < segment.endEpochMillis }
                .sortedWith(compareBy<RawCollectorEvent> { it.timestampEpochMillis }.thenBy { it.queryOrdinal })
                .toList()
        }
    }

    private fun UsageCollectorRun.toQualityEvidence() = CollectorRunQualityEvidence(
        collectorRunId = collectorRunId,
        requestedStartEpochMillis = requestedStartEpochMillis,
        requestedEndEpochMillis = requestedEndEpochMillis,
        collectedAtEpochMillis = collectedAtEpochMillis,
        status = when {
            successful -> CollectorRunQualityStatus.SUCCESS
            !usagePermissionAvailable && errorCode == CollectorErrorCode.USAGE_PERMISSION_NOT_AVAILABLE -> CollectorRunQualityStatus.PERMISSION_UNAVAILABLE
            queryReturnedNull -> CollectorRunQualityStatus.QUERY_RETURNED_NULL
            else -> CollectorRunQualityStatus.ERROR
        },
        errorCode = errorCode,
        errorMessage = errorMessage,
        eventCount = eventCount,
    )

    private fun RawCollectorEvent.toEvidence() = InitialScreenStateEvidence(
        collectorRunId = collectorRunId,
        timestampEpochMillis = timestampEpochMillis,
        eventTypeCode = eventTypeCode,
        eventTypeName = eventTypeName,
        queryOrdinal = queryOrdinal,
    )

    private fun RawCollectorEvent.isScreenStateEvent(): Boolean =
        eventTypeName == SCREEN_INTERACTIVE || eventTypeName == SCREEN_NON_INTERACTIVE

    private data class CoverageResult(
        val segments: List<CanonicalCoverageSegment>,
        val gaps: List<CoverageGap>,
    )

    private data class EffectiveRunCoverage(
        val run: UsageCollectorRun,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
    )
}
