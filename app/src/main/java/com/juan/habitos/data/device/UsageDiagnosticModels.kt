package com.juan.habitos.data.device

data class RawUsageEvent(
    val timestamp: Long,
    val packageName: String?,
    val eventType: Int,
    val eventName: String,
    val className: String? = null,
)

data class AppUsageDiagnostic(
    val packageName: String,
    val foregroundMillis: Long,
    val lastTimeUsed: Long,
)

data class DetectedSession(val startMillis: Long, val endMillis: Long) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)
}

data class IncompleteDeviceSession(val startMillis: Long?, val endMillis: Long?)

data class DeviceSessionAnalysis(
    val completed: List<DetectedSession>,
    val incomplete: List<IncompleteDeviceSession>,
)

data class AppForegroundInterval(val startMillis: Long, val endMillis: Long) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)
}

data class ReconstructedAppUsage(
    val packageName: String,
    val intervals: List<AppForegroundInterval>,
) {
    val totalMillis: Long get() = intervals.sumOf { it.durationMillis }
}

data class UsageDiagnosticSummary(
    val totalForegroundMillis: Long,
    val eventForegroundMillis: Long?,
    val appCount: Int,
    val unlockCount: Int?,
    val sessionCount: Int?,
    val averageSessionMillis: Long?,
    val maxSessionMillis: Long?,
    val eventCount: Int,
)

data class UsageDiagnosticSnapshot(
    val fromMillis: Long,
    val toMillis: Long,
    val applications: List<AppUsageDiagnostic>,
    val events: List<RawUsageEvent>,
    val deviceSessions: List<DetectedSession>,
    val incompleteDeviceSessions: List<IncompleteDeviceSession>,
    val unlockTimestamps: List<Long>,
    val reconstructedApplications: List<ReconstructedAppUsage>,
    val summary: UsageDiagnosticSummary,
)
