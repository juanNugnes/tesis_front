package com.juan.habitos.data.device

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.juan.habitos.domain.model.AppUsage
import com.juan.habitos.domain.model.DailyUsage
import com.juan.habitos.domain.model.UsageMetricStatus
import com.juan.habitos.domain.repository.DeviceUsageDataSource
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class AndroidDeviceUsageDataSource(private val context: Context) : DeviceUsageDataSource, UsageEventRunCollector {
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

    fun queryAggregates(
        start: Instant,
        end: Instant,
        nightIntervals: List<Pair<Instant, Instant>>,
    ): AndroidAggregateEvidence {
        val permission = try {
            hasUsageAccess()
        } catch (_: Exception) {
            return AndroidAggregateEvidence(null, null, null, UsageMetricStatus.CAPTURE_ERROR)
        }
        if (!permission) return AndroidAggregateEvidence(null, null, null, UsageMetricStatus.PERMISSION_DENIED)
        return try {
            val applications = manager.readApplicationAggregates(context.packageManager, start.toEpochMilli(), end.toEpochMilli())
            val eventStream = manager.queryEvents(start.toEpochMilli(), end.toEpochMilli())
            val events = eventStream?.let { stream ->
                buildList {
                    val event = UsageEvents.Event()
                    while (stream.hasNextEvent()) {
                        stream.getNextEvent(event)
                        add(RawUsageEvent(event.timeStamp, event.packageName?.takeIf(String::isNotBlank), event.eventType, eventName(event.eventType)))
                    }
                }.sortedBy(RawUsageEvent::timestamp)
            }
            var nightFailure: UsageMetricStatus? = null
            val nightMillis = if (nightIntervals.isEmpty()) null else nightIntervals.sumOf { (nightStart, nightEnd) ->
                val values = manager.readApplicationAggregates(context.packageManager, nightStart.toEpochMilli(), nightEnd.toEpochMilli())
                if (values == null) {
                    nightFailure = UsageMetricStatus.CAPTURE_ERROR
                    0L
                } else values.sumOf(AndroidAppAggregate::foregroundMillis)
            }.takeIf { nightFailure == null }
            AndroidAggregateEvidence(applications, events, nightMillis, nightFailureStatus = nightFailure)
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to capture Android usage aggregates", exception)
            AndroidAggregateEvidence(null, null, null, UsageMetricStatus.CAPTURE_ERROR)
        }
    }

    override fun collectRun(requestedStartEpochMillis: Long, requestedEndEpochMillis: Long): UsageCollectorRun {
        val collectorRunId = UUID.randomUUID().toString()
        val androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val deviceModel = Build.MODEL
        val usagePermissionAvailable = try {
            hasUsageAccess()
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to inspect usage permission for collectorRunId=$collectorRunId", exception)
            return UsageCollectorRunFactory.exception(
                collectorRunId = collectorRunId,
                requestedStartEpochMillis = requestedStartEpochMillis,
                requestedEndEpochMillis = requestedEndEpochMillis,
                collectedAtEpochMillis = System.currentTimeMillis(),
                androidVersion = androidVersion,
                deviceModel = deviceModel,
                throwable = exception,
                usagePermissionAvailable = false,
            )
        }
        if (!usagePermissionAvailable) {
            return UsageCollectorRunFactory.permissionUnavailable(
                collectorRunId = collectorRunId,
                requestedStartEpochMillis = requestedStartEpochMillis,
                requestedEndEpochMillis = requestedEndEpochMillis,
                collectedAtEpochMillis = System.currentTimeMillis(),
                androidVersion = androidVersion,
                deviceModel = deviceModel,
            )
        }
        return try {
            val stream = manager.queryEvents(requestedStartEpochMillis, requestedEndEpochMillis)
                ?: return UsageCollectorRunFactory.queryReturnedNull(
                    collectorRunId = collectorRunId,
                    requestedStartEpochMillis = requestedStartEpochMillis,
                    requestedEndEpochMillis = requestedEndEpochMillis,
                    collectedAtEpochMillis = System.currentTimeMillis(),
                    androidVersion = androidVersion,
                    deviceModel = deviceModel,
                )
            val events = buildList {
                val event = UsageEvents.Event()
                while (stream.hasNextEvent()) {
                    stream.getNextEvent(event)
                    add(
                        CollectedUsageEvent(
                            timestampEpochMillis = event.timeStamp,
                            eventTypeCode = event.eventType,
                            eventTypeName = eventName(event.eventType),
                            packageName = event.packageName?.takeIf { it.isNotBlank() },
                            className = event.className?.takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
            UsageCollectorRunFactory.success(
                collectorRunId = collectorRunId,
                requestedStartEpochMillis = requestedStartEpochMillis,
                requestedEndEpochMillis = requestedEndEpochMillis,
                collectedAtEpochMillis = System.currentTimeMillis(),
                androidVersion = androidVersion,
                deviceModel = deviceModel,
                eventsInQueryOrder = events,
            )
        } catch (exception: Exception) {
            Log.e(TAG, "queryEvents failed for collectorRunId=$collectorRunId", exception)
            UsageCollectorRunFactory.exception(
                collectorRunId = collectorRunId,
                requestedStartEpochMillis = requestedStartEpochMillis,
                requestedEndEpochMillis = requestedEndEpochMillis,
                collectedAtEpochMillis = System.currentTimeMillis(),
                androidVersion = androidVersion,
                deviceModel = deviceModel,
                throwable = exception,
            )
        }
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
        private const val TAG = "UsageCollector"

        fun eventName(type: Int): String = when (type) {
            0 -> "NONE"
            1 -> "ACTIVITY_RESUMED"
            2 -> "ACTIVITY_PAUSED"
            5 -> "CONFIGURATION_CHANGE"
            7 -> "USER_INTERACTION"
            8 -> "SHORTCUT_INVOCATION"
            11 -> "STANDBY_BUCKET_CHANGED"
            15 -> "SCREEN_INTERACTIVE"
            16 -> "SCREEN_NON_INTERACTIVE"
            17 -> "KEYGUARD_SHOWN"
            18 -> "KEYGUARD_HIDDEN"
            19 -> "FOREGROUND_SERVICE_START"
            20 -> "FOREGROUND_SERVICE_STOP"
            23 -> "ACTIVITY_STOPPED"
            26 -> "DEVICE_SHUTDOWN"
            27 -> "DEVICE_STARTUP"
            else -> "UNKNOWN_$type"
        }
    }
}
