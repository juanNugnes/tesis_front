package com.juan.habitos.data.device

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.juan.habitos.domain.model.AppUsage
import com.juan.habitos.domain.model.DailyUsage
import com.juan.habitos.domain.repository.DeviceUsageDataSource
import java.time.LocalDate
import java.time.ZoneId

class AndroidDeviceUsageDataSource(private val context: Context) : DeviceUsageDataSource {
    private val manager = context.getSystemService(UsageStatsManager::class.java)

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun query(fromMillis: Long, toMillis: Long): UsageDiagnosticSnapshot {
        if (!hasUsageAccess() || toMillis <= fromMillis) return emptySnapshot(fromMillis, toMillis)
        val apps = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, fromMillis, toMillis)
            .orEmpty()
            .filter { it.totalTimeInForeground > 0 }
            .groupBy { it.packageName }
            .map { (packageName, stats) -> AppUsageDiagnostic(packageName, stats.sumOf { it.totalTimeInForeground }, stats.maxOf { it.lastTimeUsed }) }
            .sortedByDescending { it.foregroundMillis }
        val events = buildList {
            val stream = manager.queryEvents(fromMillis, toMillis)
            val event = UsageEvents.Event()
            while (stream.hasNextEvent()) {
                stream.getNextEvent(event)
                add(RawUsageEvent(event.timeStamp, event.packageName?.takeIf { it.isNotBlank() }, event.eventType, eventName(event.eventType)))
            }
        }.sortedBy { it.timestamp }
        val sessionAnalysis = UsageDiagnosticsCalculator.analyzeSessions(events)
        return UsageDiagnosticSnapshot(fromMillis, toMillis, apps, events, sessionAnalysis.completed, sessionAnalysis.incomplete, UsageDiagnosticsCalculator.unlockTimestamps(events), UsageDiagnosticsCalculator.reconstructAppUsage(events, toMillis), UsageDiagnosticsCalculator.summary(apps, events, toMillis))
    }

    override suspend fun getDailyUsage(): DailyUsage {
        val now = System.currentTimeMillis()
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val snapshot = query(start, now)
        return DailyUsage((snapshot.summary.totalForegroundMillis / 60_000).toInt(), 0, snapshot.summary.sessionCount ?: 0)
    }

    override suspend fun getApplicationUsage(): List<AppUsage> {
        val now = System.currentTimeMillis()
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return query(start, now).applications.map { AppUsage(it.packageName, it.packageName, (it.foregroundMillis / 60_000).toInt(), "", "📱") }
    }

    private fun emptySnapshot(from: Long, to: Long): UsageDiagnosticSnapshot = UsageDiagnosticSnapshot(from, to, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), UsageDiagnosticsCalculator.summary(emptyList(), emptyList(), to))

    companion object {
        fun eventName(type: Int): String = when (type) {
            UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
            UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
            UsageEvents.Event.ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
            UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
            UsageEvents.Event.KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
            UsageEvents.Event.KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
            UsageEvents.Event.USER_INTERACTION -> "USER_INTERACTION"
            UsageEvents.Event.SHORTCUT_INVOCATION -> "SHORTCUT_INVOCATION"
            UsageEvents.Event.STANDBY_BUCKET_CHANGED -> "STANDBY_BUCKET_CHANGED"
            UsageEvents.Event.FOREGROUND_SERVICE_START -> "FOREGROUND_SERVICE_START"
            UsageEvents.Event.FOREGROUND_SERVICE_STOP -> "FOREGROUND_SERVICE_STOP"
            UsageEvents.Event.CONFIGURATION_CHANGE -> "CONFIGURATION_CHANGE"
            UsageEvents.Event.DEVICE_SHUTDOWN -> "DEVICE_SHUTDOWN"
            UsageEvents.Event.DEVICE_STARTUP -> "DEVICE_STARTUP"
            else -> "EVENT_$type"
        }
    }
}
