package com.juan.habitos.data.device

import android.app.usage.UsageEvents
import com.juan.habitos.domain.model.UsageMetricStatus
import java.time.Instant

data class LastNightActivityResult(
    val timestamp: Instant?,
    val status: UsageMetricStatus,
)

object LastNightActivityCalculator {
    fun calculate(
        events: List<RawUsageEvent>,
        nightIntervals: List<Pair<Instant, Instant>>,
    ): LastNightActivityResult {
        if (nightIntervals.isEmpty()) return unavailable()
        val activityEvents = events.asSequence()
            .filter { !it.packageName.isNullOrBlank() }
            .filter { it.eventType in ACTIVITY_EVENT_TYPES }
            .sortedBy(RawUsageEvent::timestamp)
            .toList()
        var latestEvidence: LastNightActivityResult? = null
        nightIntervals.sortedBy { it.first }.forEach { (start, end) ->
            val startMillis = start.toEpochMilli()
            val endMillis = end.toEpochMilli()
            val activeComponents = mutableSetOf<String>()
            activityEvents.asSequence()
                .takeWhile { it.timestamp < startMillis }
                .forEach { updateActiveComponents(activeComponents, it) }
            val activeAtStart = activeComponents.isNotEmpty()
            var observedTransition = false
            var observedResume = false
            var lastPause: Long? = null
            activityEvents.asSequence()
                .dropWhile { it.timestamp < startMillis }
                .takeWhile { it.timestamp < endMillis }
                .forEach { event ->
                    observedTransition = true
                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> observedResume = true
                        UsageEvents.Event.ACTIVITY_PAUSED -> lastPause = maxOf(lastPause ?: Long.MIN_VALUE, event.timestamp)
                    }
                    updateActiveComponents(activeComponents, event)
                }
            val intervalResult = when {
                activeComponents.isNotEmpty() && (activeAtStart || observedResume) ->
                    LastNightActivityResult(null, UsageMetricStatus.INCOMPLETE)
                lastPause != null ->
                    LastNightActivityResult(Instant.ofEpochMilli(lastPause!!), UsageMetricStatus.MEASURED)
                observedTransition ->
                    LastNightActivityResult(null, UsageMetricStatus.INCOMPLETE)
                else -> null
            }
            if (intervalResult != null) latestEvidence = intervalResult
        }
        return latestEvidence ?: unavailable()
    }

    private fun updateActiveComponents(active: MutableSet<String>, event: RawUsageEvent) {
        val packageName = event.packageName ?: return
        val key = "$packageName/${event.className.orEmpty()}"
        when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> active += key
            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.ACTIVITY_STOPPED -> active -= key
        }
    }

    private fun unavailable() = LastNightActivityResult(null, UsageMetricStatus.UNAVAILABLE)

    private val ACTIVITY_EVENT_TYPES = setOf(
        UsageEvents.Event.ACTIVITY_RESUMED,
        UsageEvents.Event.ACTIVITY_PAUSED,
        UsageEvents.Event.ACTIVITY_STOPPED,
    )
}
