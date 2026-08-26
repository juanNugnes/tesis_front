package com.juan.habitos.data.device

import android.app.usage.UsageEvents

object UsageDiagnosticsCalculator {
    fun analyzeSessions(events: List<RawUsageEvent>): DeviceSessionAnalysis {
        val result = mutableListOf<DetectedSession>()
        val incomplete = mutableListOf<IncompleteDeviceSession>()
        var sessionStart: Long? = null
        events.sortedBy { it.timestamp }.forEach { event ->
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> if (sessionStart == null) sessionStart = event.timestamp
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> if (sessionStart != null) {
                    val start = sessionStart!!
                    if (event.timestamp >= start) result += DetectedSession(start, event.timestamp)
                    sessionStart = null
                } else incomplete += IncompleteDeviceSession(null, event.timestamp)
            }
        }
        sessionStart?.let { incomplete += IncompleteDeviceSession(it, null) }
        return DeviceSessionAnalysis(result, incomplete)
    }

    fun sessions(events: List<RawUsageEvent>): List<DetectedSession> = analyzeSessions(events).completed

    fun unlockCount(events: List<RawUsageEvent>): Int? {
        val count = unlockTimestamps(events).size
        return count.takeIf { it > 0 }
    }

    fun unlockTimestamps(events: List<RawUsageEvent>): List<Long> = events.filter { it.eventType == UsageEvents.Event.KEYGUARD_HIDDEN }.map { it.timestamp }.sorted()

    fun reconstructAppUsage(events: List<RawUsageEvent>, intervalEnd: Long): List<ReconstructedAppUsage> {
        val starts = mutableMapOf<String, Long>()
        val intervals = mutableMapOf<String, MutableList<AppForegroundInterval>>()
        events.sortedBy { it.timestamp }.forEach { event ->
            val packageName = event.packageName ?: return@forEach
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> starts.putIfAbsent(packageName, event.timestamp)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> starts.remove(packageName)?.let { start ->
                    if (event.timestamp >= start) intervals.getOrPut(packageName) { mutableListOf() } += AppForegroundInterval(start, event.timestamp)
                }
            }
        }
        starts.forEach { (packageName, start) -> if (intervalEnd >= start) intervals.getOrPut(packageName) { mutableListOf() } += AppForegroundInterval(start, intervalEnd) }
        return intervals.map { (packageName, values) -> ReconstructedAppUsage(packageName, values) }.sortedByDescending { it.totalMillis }
    }

    fun foregroundFromEvents(events: List<RawUsageEvent>, intervalEnd: Long): Long? {
        val reconstructed = reconstructAppUsage(events, intervalEnd)
        return reconstructed.sumOf { it.totalMillis }.takeIf { reconstructed.isNotEmpty() }
    }

    fun summary(apps: List<AppUsageDiagnostic>, events: List<RawUsageEvent>, intervalEnd: Long): UsageDiagnosticSummary {
        val sessions = sessions(events)
        return UsageDiagnosticSummary(
            totalForegroundMillis = apps.sumOf { it.foregroundMillis },
            eventForegroundMillis = foregroundFromEvents(events, intervalEnd),
            appCount = apps.size,
            unlockCount = unlockCount(events),
            sessionCount = sessions.size.takeIf { it > 0 },
            averageSessionMillis = sessions.takeIf { it.isNotEmpty() }?.map { it.durationMillis }?.average()?.toLong(),
            maxSessionMillis = sessions.maxOfOrNull { it.durationMillis },
            eventCount = events.size,
        )
    }
}
