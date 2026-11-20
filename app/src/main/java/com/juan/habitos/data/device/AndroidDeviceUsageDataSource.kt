package com.juan.habitos.data.device

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
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

    /** Null means the foreground application cannot be established reliably. */
    fun detectGameActive(run: UsageCollectorRun): Boolean? {
        if (!run.successful) return null
        val packageName = run.lastForegroundPackage() ?: return null
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            info.category == ApplicationInfo.CATEGORY_GAME
        }.getOrNull()
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
                add(RawUsageEvent(event.timeStamp, event.packageName?.takeIf { it.isNotBlank() }, event.eventType, eventName(event.eventType, Build.VERSION.SDK_INT), event.className?.takeIf { it.isNotBlank() }))
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
                        add(RawUsageEvent(event.timeStamp, event.packageName?.takeIf(String::isNotBlank), event.eventType, eventName(event.eventType, Build.VERSION.SDK_INT), event.className?.takeIf(String::isNotBlank)))
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
        val androidApiLevel = Build.VERSION.SDK_INT
        val manufacturer = Build.MANUFACTURER
        val collectedAt = System.currentTimeMillis()
        val usagePermissionAvailable = try {
            hasUsageAccess()
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to inspect usage permission for collectorRunId=$collectorRunId", exception)
            return UsageCollectorRunFactory.exception(
                collectorRunId = collectorRunId,
                requestedStartEpochMillis = requestedStartEpochMillis,
                requestedEndEpochMillis = requestedEndEpochMillis,
                collectedAtEpochMillis = collectedAt,
                androidVersion = androidVersion,
                deviceModel = deviceModel,
                androidApiLevel = androidApiLevel,
                manufacturer = manufacturer,
                throwable = exception,
                usagePermissionAvailable = false,
            )
        }
        if (!usagePermissionAvailable) {
            return UsageCollectorRunFactory.permissionUnavailable(
                collectorRunId = collectorRunId,
                requestedStartEpochMillis = requestedStartEpochMillis,
                requestedEndEpochMillis = requestedEndEpochMillis,
                collectedAtEpochMillis = collectedAt,
                androidVersion = androidVersion,
                deviceModel = deviceModel,
                androidApiLevel = androidApiLevel,
                manufacturer = manufacturer,
            )
        }
        return try {
            val stream = manager.queryEvents(requestedStartEpochMillis, requestedEndEpochMillis)
                ?: return UsageCollectorRunFactory.queryReturnedNull(
                    collectorRunId = collectorRunId,
                    requestedStartEpochMillis = requestedStartEpochMillis,
                    requestedEndEpochMillis = requestedEndEpochMillis,
                    collectedAtEpochMillis = collectedAt,
                    androidVersion = androidVersion,
                    deviceModel = deviceModel,
                    androidApiLevel = androidApiLevel,
                    manufacturer = manufacturer,
                )
            val events = buildList {
                val event = UsageEvents.Event()
                while (stream.hasNextEvent()) {
                    check(stream.getNextEvent(event)) {
                        "UsageEvents.hasNextEvent was true but getNextEvent returned false"
                    }
                    add(
                        CollectedUsageEvent(
                            timestampEpochMillis = event.timeStamp,
                            eventTypeCode = event.eventType,
                            eventTypeName = eventName(event.eventType, androidApiLevel),
                            packageName = event.packageName,
                            className = event.className,
                        )
                    )
                }
            }
            UsageCollectorRunFactory.success(
                collectorRunId = collectorRunId,
                requestedStartEpochMillis = requestedStartEpochMillis,
                requestedEndEpochMillis = requestedEndEpochMillis,
                collectedAtEpochMillis = collectedAt,
                androidVersion = androidVersion,
                deviceModel = deviceModel,
                eventsInQueryOrder = events,
                androidApiLevel = androidApiLevel,
                manufacturer = manufacturer,
            )
        } catch (exception: SecurityException) {
            Log.e(TAG, "queryEvents denied for collectorRunId=$collectorRunId", exception)
            UsageCollectorRunFactory.exception(
                collectorRunId = collectorRunId,
                requestedStartEpochMillis = requestedStartEpochMillis,
                requestedEndEpochMillis = requestedEndEpochMillis,
                collectedAtEpochMillis = collectedAt,
                androidVersion = androidVersion,
                deviceModel = deviceModel,
                throwable = exception,
                androidApiLevel = androidApiLevel,
                manufacturer = manufacturer,
            )
        } catch (exception: RuntimeException) {
            Log.e(TAG, "queryEvents failed for collectorRunId=$collectorRunId", exception)
            UsageCollectorRunFactory.exception(
                collectorRunId = collectorRunId,
                requestedStartEpochMillis = requestedStartEpochMillis,
                requestedEndEpochMillis = requestedEndEpochMillis,
                collectedAtEpochMillis = collectedAt,
                androidVersion = androidVersion,
                deviceModel = deviceModel,
                throwable = exception,
                androidApiLevel = androidApiLevel,
                manufacturer = manufacturer,
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

        fun eventName(type: Int, apiLevel: Int = 29): String = UsageEventTypeNameMapper.nameFor(type, apiLevel)
    }
}

object UsageEventTypeNameMapper {
    fun nameFor(type: Int, apiLevel: Int): String {
        if (apiLevel < 29) {
            return when (type) {
                0 -> "NONE"
                1 -> "MOVE_TO_FOREGROUND"
                2 -> "MOVE_TO_BACKGROUND"
                5 -> "CONFIGURATION_CHANGE"
                7 -> "USER_INTERACTION"
                8 -> "SHORTCUT_INVOCATION"
                11 -> "STANDBY_BUCKET_CHANGED"
                15 -> "SCREEN_INTERACTIVE"
                16 -> "SCREEN_NON_INTERACTIVE"
                17 -> "KEYGUARD_SHOWN"
                18 -> "KEYGUARD_HIDDEN"
                else -> "EVENT_TYPE_$type"
            }
        }
        return when (type) {
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
            else -> "EVENT_TYPE_$type"
        }
    }
}
