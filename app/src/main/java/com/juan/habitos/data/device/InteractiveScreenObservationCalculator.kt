package com.juan.habitos.data.device

import com.google.gson.GsonBuilder

const val INTERACTIVE_SCREEN_CALCULATION_VERSION = "interactive-screen-reference-v3-draft"
const val INTERACTIVE_SCREEN_MINUTE_CONVERSION = "TOTAL_MILLIS_DIVIDED_BY_60000"

enum class InteractiveScreenObservationStatus { OBSERVED, OBSERVED_ZERO, MISSING }
enum class InteractiveScreenMissingReason {
    INITIAL_STATE_UNKNOWN,
    INCOMPLETE_QUERY_COVERAGE,
    CONFLICTING_OVERLAPPING_QUERIES,
    AMBIGUOUS_EVENT_ORDER,
    OPEN_INTERVAL_ACROSS_RESTART,
    UNKNOWN_STATE_AFTER_RESTART,
}
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

data class CoverageGap(val startEpochMillis: Long, val endEpochMillis: Long)

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
    val sourceRunIds: List<String>,
    val restartInDay: Boolean,
    val conflictingOverlaps: Boolean,
    val ambiguousEventOrder: Boolean,
)

/** Technical device-display observation. It does not identify or attribute a physical user. */
data class DailyDeviceInteractiveScreenObservation(
    val profileId: String?,
    val deviceId: String?,
    val localDate: String,
    val timezoneId: String,
    val dayStartEpochMillis: Long,
    val dayEndEpochMillis: Long,
    val interactiveScreenMinutes: Double?,
    val interactiveScreenMillis: Long?,
    val minuteConversion: String = INTERACTIVE_SCREEN_MINUTE_CONVERSION,
    val calculationVersion: String = INTERACTIVE_SCREEN_CALCULATION_VERSION,
    val status: InteractiveScreenObservationStatus,
    val missingReason: InteractiveScreenMissingReason?,
    val calculatedAtEpochMillis: Long,
    val qualityEvidence: InteractiveScreenQualityEvidence,
)

object DailyInteractiveScreenObservationJson {
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    fun encode(value: DailyDeviceInteractiveScreenObservation): String = gson.toJson(value)
}

/** F12 v3 reconstruction used by both phone diagnostics and JVM parity vectors. */
object InteractiveScreenObservationCalculator {
    private const val SCREEN_INTERACTIVE = 15
    private const val SCREEN_NON_INTERACTIVE = 16
    private const val DEVICE_SHUTDOWN = 26
    private const val DEVICE_STARTUP = 27
    private const val MILLIS_PER_MINUTE = 60_000.0
    private val relevantCodes = setOf(
        SCREEN_INTERACTIVE,
        SCREEN_NON_INTERACTIVE,
        DEVICE_SHUTDOWN,
        DEVICE_STARTUP,
    )

    fun calculate(
        diagnostic: DailyHistoricalUsageDiagnostic,
        calculatedAtEpochMillis: Long = System.currentTimeMillis(),
    ): DailyDeviceInteractiveScreenObservation {
        require(diagnostic.dayEndEpochMillis > diagnostic.dayStartEpochMillis) {
            "dayEndEpochMillis must be after dayStartEpochMillis"
        }
        validateDiagnostic(diagnostic)

        val start = diagnostic.dayStartEpochMillis
        val end = diagnostic.dayEndEpochMillis
        val successfulRuns = diagnostic.collectorRuns.filter { it.successful }
        val predecessor = successfulRuns
            .asSequence()
            .flatMap { it.events.asSequence() }
            .filter { it.timestampEpochMillis < start && it.eventTypeCode in relevantCodes }
            .maxWithOrNull(compareBy<RawCollectorEvent> { it.timestampEpochMillis }.thenBy { it.queryOrdinal })

        if (predecessor == null) {
            return missingObservation(
                diagnostic,
                calculatedAtEpochMillis,
                InteractiveScreenMissingReason.INITIAL_STATE_UNKNOWN,
                initialState = null,
                segments = emptyList(),
                gaps = coverageGaps(successfulRuns, start, end),
                canonicalEvents = emptyList(),
            )
        }

        val coverageStart = predecessor.timestampEpochMillis
        val segments = selectedSegments(successfulRuns, coverageStart, end)
        if (segments == null) {
            return missingObservation(
                diagnostic,
                calculatedAtEpochMillis,
                InteractiveScreenMissingReason.INCOMPLETE_QUERY_COVERAGE,
                initialState = predecessor,
                segments = emptyList(),
                gaps = coverageGaps(successfulRuns, coverageStart, end),
                canonicalEvents = emptyList(),
            )
        }

        val canonicalEvents = canonicalEvents(segments, diagnostic.collectorRuns)
        if (hasConflictingOverlaps(successfulRuns, coverageStart, end)) {
            return missingObservation(
                diagnostic,
                calculatedAtEpochMillis,
                InteractiveScreenMissingReason.CONFLICTING_OVERLAPPING_QUERIES,
                predecessor,
                segments,
                emptyList(),
                canonicalEvents,
                conflictingOverlaps = true,
            )
        }

        if (hasAmbiguousEventOrder(canonicalEvents)) {
            return missingObservation(
                diagnostic,
                calculatedAtEpochMillis,
                InteractiveScreenMissingReason.AMBIGUOUS_EVENT_ORDER,
                predecessor,
                segments,
                emptyList(),
                canonicalEvents,
                ambiguousEventOrder = true,
            )
        }

        var state: Int? = null
        var interactiveSince: Long? = null
        var totalInteractiveMillis = 0L
        for (event in canonicalEvents) {
            val timestamp = event.timestampEpochMillis
            val code = event.eventTypeCode
            if (code == DEVICE_SHUTDOWN || code == DEVICE_STARTUP) {
                if (timestamp in start until end) {
                    val reason = if (state == SCREEN_INTERACTIVE) {
                        InteractiveScreenMissingReason.OPEN_INTERVAL_ACROSS_RESTART
                    } else {
                        InteractiveScreenMissingReason.UNKNOWN_STATE_AFTER_RESTART
                    }
                    return missingObservation(
                        diagnostic,
                        calculatedAtEpochMillis,
                        reason,
                        predecessor,
                        segments,
                        emptyList(),
                        canonicalEvents,
                        restartInDay = true,
                    )
                }
                state = null
                interactiveSince = null
                continue
            }
            if (timestamp < start) {
                state = code
                continue
            }
            if (state == null) {
                return missingObservation(
                    diagnostic,
                    calculatedAtEpochMillis,
                    InteractiveScreenMissingReason.INITIAL_STATE_UNKNOWN,
                    predecessor,
                    segments,
                    emptyList(),
                    canonicalEvents,
                )
            }
            if (code == SCREEN_INTERACTIVE) {
                if (state != SCREEN_INTERACTIVE) interactiveSince = timestamp
                state = SCREEN_INTERACTIVE
            } else if (code == SCREEN_NON_INTERACTIVE) {
                if (state == SCREEN_INTERACTIVE) {
                    totalInteractiveMillis += timestamp - (interactiveSince ?: start)
                    interactiveSince = null
                }
                state = SCREEN_NON_INTERACTIVE
            }
        }
        if (state == null) {
            return missingObservation(
                diagnostic,
                calculatedAtEpochMillis,
                InteractiveScreenMissingReason.INITIAL_STATE_UNKNOWN,
                predecessor,
                segments,
                emptyList(),
                canonicalEvents,
            )
        }
        if (state == SCREEN_INTERACTIVE) {
            totalInteractiveMillis += end - (interactiveSince ?: start)
        }

        return baseObservation(
            diagnostic = diagnostic,
            calculatedAtEpochMillis = calculatedAtEpochMillis,
            interactiveScreenMillis = totalInteractiveMillis,
            status = if (totalInteractiveMillis == 0L) {
                InteractiveScreenObservationStatus.OBSERVED_ZERO
            } else {
                InteractiveScreenObservationStatus.OBSERVED
            },
            missingReason = null,
            quality = qualityEvidence(diagnostic, predecessor, segments, emptyList(), canonicalEvents),
        )
    }

    private fun validateDiagnostic(diagnostic: DailyHistoricalUsageDiagnostic) {
        val runIds = mutableSetOf<String>()
        diagnostic.collectorRuns.forEach { run ->
            require(run.collectorRunId.isNotBlank()) { "collectorRunId must not be blank" }
            require(run.requestedStartEpochMillis < run.requestedEndEpochMillis) { "invalid query window" }
            require(run.eventCount >= 0) { "eventCount must not be negative" }
            require(runIds.add(run.collectorRunId)) { "duplicate collectorRunId" }
            require(run.events.size == run.eventCount) { "eventCount does not match exported events" }
            run.events.forEach { event ->
                require(run.successful) { "events cannot belong to a failed query" }
                require(event.collectorRunId == run.collectorRunId) { "event belongs to a different query" }
                require(
                    event.requestedStartEpochMillis == run.requestedStartEpochMillis &&
                        event.requestedEndEpochMillis == run.requestedEndEpochMillis
                ) { "event query window does not match its query" }
                require(event.timestampEpochMillis in run.requestedStartEpochMillis until run.requestedEndEpochMillis) {
                    "event timestamp is outside its query"
                }
                require(event.queryOrdinal >= 0) { "queryOrdinal must not be negative" }
            }
        }
    }

    private fun selectedSegments(
        successfulRuns: List<UsageCollectorRun>,
        coverageStart: Long,
        dayEnd: Long,
    ): List<CanonicalCoverageSegment>? {
        val segments = mutableListOf<CanonicalCoverageSegment>()
        var cursor = coverageStart
        while (cursor < dayEnd) {
            val selected = successfulRuns
                .asSequence()
                .filter { it.requestedStartEpochMillis <= cursor && cursor < it.requestedEndEpochMillis }
                .sortedWith(
                    compareByDescending<UsageCollectorRun> { it.requestedEndEpochMillis }
                        .thenByDescending { it.collectedAtEpochMillis }
                        .thenBy { it.collectorRunId }
                )
                .firstOrNull()
                ?: return null
            val segmentEnd = minOf(dayEnd, selected.requestedEndEpochMillis)
            segments += CanonicalCoverageSegment(selected.collectorRunId, cursor, segmentEnd)
            cursor = segmentEnd
        }
        return segments
    }

    private fun coverageGaps(
        successfulRuns: List<UsageCollectorRun>,
        coverageStart: Long,
        dayEnd: Long,
    ): List<CoverageGap> {
        val windows = successfulRuns.sortedWith(
            compareBy<UsageCollectorRun> { it.requestedStartEpochMillis }.thenBy { it.requestedEndEpochMillis }
        )
        val gaps = mutableListOf<CoverageGap>()
        var cursor = coverageStart
        for (run in windows) {
            if (run.requestedEndEpochMillis <= cursor) continue
            if (run.requestedStartEpochMillis > cursor) {
                gaps += CoverageGap(cursor, minOf(dayEnd, run.requestedStartEpochMillis))
            }
            cursor = maxOf(cursor, run.requestedEndEpochMillis)
            if (cursor >= dayEnd) break
        }
        if (cursor < dayEnd) gaps += CoverageGap(cursor, dayEnd)
        return gaps.filter { it.endEpochMillis > it.startEpochMillis }
    }

    private fun canonicalEvents(
        segments: List<CanonicalCoverageSegment>,
        runs: List<UsageCollectorRun>,
    ): List<RawCollectorEvent> {
        val runsById = runs.associateBy { it.collectorRunId }
        return segments.flatMap { segment ->
            orderedRelevant(
                runsById.getValue(segment.collectorRunId),
                segment.startEpochMillis,
                segment.endEpochMillis,
            )
        }
    }

    private fun orderedRelevant(run: UsageCollectorRun, begin: Long, end: Long): List<RawCollectorEvent> =
        run.events
            .asSequence()
            .filter { it.timestampEpochMillis in begin until end && it.eventTypeCode in relevantCodes }
            .sortedWith(compareBy<RawCollectorEvent> { it.timestampEpochMillis }.thenBy { it.queryOrdinal })
            .toList()

    private fun hasConflictingOverlaps(
        successfulRuns: List<UsageCollectorRun>,
        coverageStart: Long,
        dayEnd: Long,
    ): Boolean {
        successfulRuns.forEachIndexed { index, left ->
            successfulRuns.drop(index + 1).forEach { right ->
                val overlapStart = maxOf(
                    coverageStart,
                    left.requestedStartEpochMillis,
                    right.requestedStartEpochMillis,
                )
                val overlapEnd = minOf(dayEnd, left.requestedEndEpochMillis, right.requestedEndEpochMillis)
                if (overlapStart < overlapEnd) {
                    val leftSignature = orderedRelevant(left, overlapStart, overlapEnd)
                        .map { it.timestampEpochMillis to it.eventTypeCode }
                    val rightSignature = orderedRelevant(right, overlapStart, overlapEnd)
                        .map { it.timestampEpochMillis to it.eventTypeCode }
                    if (leftSignature != rightSignature) return true
                }
            }
        }
        return false
    }

    private fun hasAmbiguousEventOrder(events: List<RawCollectorEvent>): Boolean {
        val typeByPosition = mutableMapOf<Pair<Long, Int>, Int>()
        events.forEach { event ->
            val position = event.timestampEpochMillis to event.queryOrdinal
            val priorType = typeByPosition.putIfAbsent(position, event.eventTypeCode)
            if (priorType != null && priorType != event.eventTypeCode) return true
        }
        return false
    }

    private fun missingObservation(
        diagnostic: DailyHistoricalUsageDiagnostic,
        calculatedAtEpochMillis: Long,
        reason: InteractiveScreenMissingReason,
        initialState: RawCollectorEvent?,
        segments: List<CanonicalCoverageSegment>,
        gaps: List<CoverageGap>,
        canonicalEvents: List<RawCollectorEvent>,
        restartInDay: Boolean = false,
        conflictingOverlaps: Boolean = false,
        ambiguousEventOrder: Boolean = false,
    ) = baseObservation(
        diagnostic = diagnostic,
        calculatedAtEpochMillis = calculatedAtEpochMillis,
        interactiveScreenMillis = null,
        status = InteractiveScreenObservationStatus.MISSING,
        missingReason = reason,
        quality = qualityEvidence(
            diagnostic,
            initialState,
            segments,
            gaps,
            canonicalEvents,
            restartInDay,
            conflictingOverlaps,
            ambiguousEventOrder,
        ),
    )

    private fun qualityEvidence(
        diagnostic: DailyHistoricalUsageDiagnostic,
        initialState: RawCollectorEvent?,
        segments: List<CanonicalCoverageSegment>,
        gaps: List<CoverageGap>,
        canonicalEvents: List<RawCollectorEvent>,
        restartInDay: Boolean = false,
        conflictingOverlaps: Boolean = false,
        ambiguousEventOrder: Boolean = false,
    ) = InteractiveScreenQualityEvidence(
        collectorRuns = diagnostic.collectorRuns.map { it.toQualityEvidence() },
        canonicalSegments = segments,
        coverageGaps = gaps,
        coverageComplete = gaps.isEmpty() && segments.isNotEmpty(),
        expectedCoverageMillis = diagnostic.dayEndEpochMillis -
            (initialState?.timestampEpochMillis ?: diagnostic.dayStartEpochMillis),
        coveredMillis = segments.sumOf { it.endEpochMillis - it.startEpochMillis },
        screenEventCount = canonicalEvents.count {
            it.eventTypeCode == SCREEN_INTERACTIVE || it.eventTypeCode == SCREEN_NON_INTERACTIVE
        },
        initialScreenState = initialState
            ?.takeIf { it.eventTypeCode == SCREEN_INTERACTIVE || it.eventTypeCode == SCREEN_NON_INTERACTIVE }
            ?.toEvidence(),
        sourceRunIds = segments.map { it.collectorRunId }.distinct(),
        restartInDay = restartInDay,
        conflictingOverlaps = conflictingOverlaps,
        ambiguousEventOrder = ambiguousEventOrder,
    )

    private fun baseObservation(
        diagnostic: DailyHistoricalUsageDiagnostic,
        calculatedAtEpochMillis: Long,
        interactiveScreenMillis: Long?,
        status: InteractiveScreenObservationStatus,
        missingReason: InteractiveScreenMissingReason?,
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
        missingReason = missingReason,
        calculatedAtEpochMillis = calculatedAtEpochMillis,
        qualityEvidence = quality,
    )

    private fun UsageCollectorRun.toQualityEvidence() = CollectorRunQualityEvidence(
        collectorRunId = collectorRunId,
        requestedStartEpochMillis = requestedStartEpochMillis,
        requestedEndEpochMillis = requestedEndEpochMillis,
        collectedAtEpochMillis = collectedAtEpochMillis,
        status = when {
            successful -> CollectorRunQualityStatus.SUCCESS
            !usagePermissionAvailable && errorCode == CollectorErrorCode.USAGE_PERMISSION_NOT_AVAILABLE ->
                CollectorRunQualityStatus.PERMISSION_UNAVAILABLE
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
}
