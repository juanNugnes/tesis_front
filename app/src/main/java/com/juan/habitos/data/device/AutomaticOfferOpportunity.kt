package com.juan.habitos.data.device

import java.security.MessageDigest

enum class InteractiveOpportunityState {
    ELIGIBLE,
    BELOW_THRESHOLD,
    SCREEN_NON_INTERACTIVE,
    MISSING_EVIDENCE,
}

data class InteractiveOpportunityQuality(
    val collectorRunId: String,
    val requestedStartEpochMillis: Long,
    val requestedEndEpochMillis: Long,
    val collectedAtEpochMillis: Long,
    val usagePermissionAvailable: Boolean,
    val queryReturnedNull: Boolean,
    val errorCode: String?,
    val eventCount: Int,
    val lifecycleInterruptionObserved: Boolean,
    val reason: String?,
)

/** Android evidence only. Policy and attribution fields intentionally do not exist here. */
data class InteractiveOpportunityEvidence(
    val formatVersion: Int = 1,
    val calculationVersion: String = OpenInteractiveIntervalEvaluator.CALCULATION_VERSION,
    val profileId: String,
    val deviceId: String,
    val seriesId: String,
    val intervalId: String?,
    val intervalStartEpochMillis: Long?,
    val evaluatedAtEpochMillis: Long,
    val continuousInteractiveMillis: Long?,
    val interactiveState: String,
    val gameActive: Boolean? = null,
    val state: InteractiveOpportunityState,
    val quality: InteractiveOpportunityQuality,
)

class OpenInteractiveIntervalEvaluator(
    private val collector: UsageEventRunCollector,
    private val lookbackMillis: Long = DEFAULT_LOOKBACK_MILLIS,
    private val gameStateDetector: (UsageCollectorRun) -> Boolean? = { null },
) {
    fun evaluate(profileId: String, deviceId: String, evaluatedAtEpochMillis: Long): InteractiveOpportunityEvidence {
        require(profileId.isNotBlank() && deviceId.isNotBlank())
        val start = (evaluatedAtEpochMillis - lookbackMillis).coerceAtLeast(0)
        val run = collector.collectRun(start, evaluatedAtEpochMillis)
        val seriesId = "interactive-series-${sha256("$profileId|$deviceId|$CALCULATION_VERSION")}".take(96)
        if (!run.successful) return evidence(
            profileId, deviceId, seriesId, evaluatedAtEpochMillis, run,
            state = InteractiveOpportunityState.MISSING_EVIDENCE,
            interactiveState = "UNKNOWN",
            reason = run.errorCode ?: "COLLECTOR_RUN_UNSUCCESSFUL",
        )

        var interactiveSince: Long? = null
        var state = ScreenState.UNKNOWN
        var lifecycleObserved = false
        run.events.asSequence()
            .filter { it.timestampEpochMillis in start..evaluatedAtEpochMillis }
            .sortedWith(compareBy<RawCollectorEvent> { it.timestampEpochMillis }.thenBy { it.queryOrdinal })
            .forEach { event ->
                when (event.eventTypeCode) {
                    SCREEN_INTERACTIVE -> {
                        if (state != ScreenState.INTERACTIVE) interactiveSince = event.timestampEpochMillis
                        state = ScreenState.INTERACTIVE
                    }
                    SCREEN_NON_INTERACTIVE -> {
                        interactiveSince = null
                        state = ScreenState.NON_INTERACTIVE
                    }
                    DEVICE_SHUTDOWN, DEVICE_STARTUP -> {
                        lifecycleObserved = true
                        interactiveSince = null
                        state = ScreenState.UNKNOWN
                    }
                }
            }

        val since = interactiveSince
        if (state != ScreenState.INTERACTIVE || since == null) {
            val result = if (state == ScreenState.NON_INTERACTIVE) InteractiveOpportunityState.SCREEN_NON_INTERACTIVE
            else InteractiveOpportunityState.MISSING_EVIDENCE
            return evidence(
                profileId, deviceId, seriesId, evaluatedAtEpochMillis, run,
                state = result,
                interactiveState = state.name,
                lifecycleObserved = lifecycleObserved,
                reason = if (lifecycleObserved && state == ScreenState.UNKNOWN) "LIFECYCLE_STATE_UNKNOWN" else
                    if (state == ScreenState.UNKNOWN) "NO_SCREEN_STATE_EVIDENCE" else null,
            )
        }

        val duration = (evaluatedAtEpochMillis - since).coerceAtLeast(0)
        val intervalId = "interactive-interval-${sha256("$seriesId|$since")}".take(96)
        return evidence(
            profileId, deviceId, seriesId, evaluatedAtEpochMillis, run,
            state = if (duration >= FIRST_OPPORTUNITY_THRESHOLD_MILLIS) InteractiveOpportunityState.ELIGIBLE
                else InteractiveOpportunityState.BELOW_THRESHOLD,
            interactiveState = ScreenState.INTERACTIVE.name,
            intervalId = intervalId,
            intervalStart = since,
            duration = duration,
            lifecycleObserved = lifecycleObserved,
            gameActive = gameStateDetector(run),
        )
    }

    private fun evidence(
        profileId: String,
        deviceId: String,
        seriesId: String,
        evaluatedAt: Long,
        run: UsageCollectorRun,
        state: InteractiveOpportunityState,
        interactiveState: String,
        intervalId: String? = null,
        intervalStart: Long? = null,
        duration: Long? = null,
        lifecycleObserved: Boolean = false,
        reason: String? = null,
        gameActive: Boolean? = null,
    ) = InteractiveOpportunityEvidence(
        profileId = profileId,
        deviceId = deviceId,
        seriesId = seriesId,
        intervalId = intervalId,
        intervalStartEpochMillis = intervalStart,
        evaluatedAtEpochMillis = evaluatedAt,
        continuousInteractiveMillis = duration,
        interactiveState = interactiveState,
        gameActive = gameActive,
        state = state,
        quality = InteractiveOpportunityQuality(
            collectorRunId = run.collectorRunId,
            requestedStartEpochMillis = run.requestedStartEpochMillis,
            requestedEndEpochMillis = run.requestedEndEpochMillis,
            collectedAtEpochMillis = run.collectedAtEpochMillis,
            usagePermissionAvailable = run.usagePermissionAvailable,
            queryReturnedNull = run.queryReturnedNull,
            errorCode = run.errorCode,
            eventCount = run.eventCount,
            lifecycleInterruptionObserved = lifecycleObserved,
            reason = reason,
        ),
    )

    private enum class ScreenState { UNKNOWN, INTERACTIVE, NON_INTERACTIVE }

    companion object {
        const val CALCULATION_VERSION = "android-open-interactive-v1"
        const val FIRST_OPPORTUNITY_THRESHOLD_MILLIS = 45 * 60 * 1_000L
        const val DEFAULT_LOOKBACK_MILLIS = 24 * 60 * 60 * 1_000L
        private const val SCREEN_INTERACTIVE = 15
        private const val SCREEN_NON_INTERACTIVE = 16
        private const val DEVICE_SHUTDOWN = 26
        private const val DEVICE_STARTUP = 27
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }

internal fun UsageCollectorRun.lastForegroundPackage(): String? {
    if (!successful) return null
    var foregroundPackage: String? = null
    events.sortedWith(compareBy<RawCollectorEvent> { it.timestampEpochMillis }.thenBy { it.queryOrdinal })
        .forEach { event ->
            when (event.eventTypeCode) {
                1, 23 -> foregroundPackage = event.packageName ?: foregroundPackage
                2, 24 -> if (event.packageName == foregroundPackage) foregroundPackage = null
                26 -> foregroundPackage = null
            }
        }
    return foregroundPackage
}
