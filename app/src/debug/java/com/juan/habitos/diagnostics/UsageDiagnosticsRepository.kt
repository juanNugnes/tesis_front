package com.juan.habitos.diagnostics

import android.os.Build
import android.util.Log
import com.juan.habitos.data.device.AndroidDeviceUsageDataSource
import com.juan.habitos.data.device.UsageDiagnosticSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter

enum class DiagnosticRange(val label: String) { LAST_30_MINUTES("Últimos 30 minutos"), TODAY("Hoy"), LAST_24_HOURS("Últimas 24 horas") }

class UsageDiagnosticsRepository(private val dataSource: AndroidDeviceUsageDataSource) {
    fun hasAccess(): Boolean = dataSource.hasUsageAccess()

    suspend fun load(range: DiagnosticRange): UsageDiagnosticSnapshot = withContext(Dispatchers.IO) {
        val to = System.currentTimeMillis()
        val from = when (range) {
            DiagnosticRange.LAST_30_MINUTES -> to - 30 * 60_000L
            DiagnosticRange.TODAY -> LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            DiagnosticRange.LAST_24_HOURS -> to - 24 * 60 * 60_000L
        }
        dataSource.query(from, to).also { snapshot ->
            Log.d(TAG, "range=${range.name} apps=${snapshot.applications.size} events=${snapshot.events.size} foregroundMs=${snapshot.summary.totalForegroundMillis}")
            snapshot.applications.forEach { app -> Log.d(TAG, "app=${app.packageName} foregroundMs=${app.foregroundMillis} lastUsed=${app.lastTimeUsed}") }
            snapshot.events.forEach { event -> Log.v(TAG, "event=${event.timestamp} package=${event.packageName ?: "Android/System"} type=${event.eventName}(${event.eventType})") }
        }
    }

    fun export(range: DiagnosticRange, snapshot: UsageDiagnosticSnapshot): String = buildString {
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        appendLine("USAGE DIAGNOSTICS")
        appendLine("Device Android version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Range: ${range.label}")
        appendLine("Interval: ${date.format(Instant.ofEpochMilli(snapshot.fromMillis))} - ${date.format(Instant.ofEpochMilli(snapshot.toMillis))}")
        appendLine()
        appendLine("DEVICE SESSIONS")
        snapshot.deviceSessions.forEachIndexed { index, session -> appendLine("${index + 1}. ${date.format(Instant.ofEpochMilli(session.startMillis))} -> ${date.format(Instant.ofEpochMilli(session.endMillis))}  ${formatDuration(session.durationMillis)}") }
        snapshot.incompleteDeviceSessions.forEach { session -> appendLine("Incomplete: ${session.startMillis?.let { date.format(Instant.ofEpochMilli(it)) } ?: "?"} -> ${session.endMillis?.let { date.format(Instant.ofEpochMilli(it)) } ?: "?"}") }
        appendLine()
        appendLine("UNLOCK EVENTS")
        if (snapshot.unlockTimestamps.isEmpty()) appendLine("Not available") else snapshot.unlockTimestamps.forEach { appendLine(date.format(Instant.ofEpochMilli(it))) }
        appendLine()
        appendLine("APP USAGE RECONSTRUCTED")
        snapshot.reconstructedApplications.forEach { app ->
            appendLine(app.packageName)
            app.intervals.forEach { interval -> appendLine("${date.format(Instant.ofEpochMilli(interval.startMillis))} -> ${date.format(Instant.ofEpochMilli(interval.endMillis))}  ${formatDuration(interval.durationMillis)}") }
            appendLine("Total: ${formatDuration(app.totalMillis)}")
        }
        appendLine()
        appendLine("SUMMARY")
        appendLine("Foreground reconstructed from events: ${snapshot.summary.eventForegroundMillis?.let(::formatDuration) ?: "Not available"}")
        appendLine("UsageStats totalTimeInForeground reference: ${formatDuration(snapshot.summary.totalForegroundMillis)}")
        appendLine("Apps used: ${snapshot.summary.appCount}")
        appendLine("Unlock events: ${snapshot.summary.unlockCount ?: "Not available"}")
        appendLine("Sessions: ${snapshot.summary.sessionCount ?: "Not available"}")
        appendLine("Average session: ${snapshot.summary.averageSessionMillis?.let(::formatDuration) ?: "Not available"}")
        appendLine("Max session: ${snapshot.summary.maxSessionMillis?.let(::formatDuration) ?: "Not available"}")
        appendLine()
        appendLine("AGGREGATED APP USAGE REFERENCE")
        snapshot.applications.forEach { appendLine("${it.packageName}: ${formatDuration(it.foregroundMillis)}; last=${date.format(Instant.ofEpochMilli(it.lastTimeUsed))}") }
        appendLine()
        appendLine("RAW EVENTS")
        snapshot.events.forEach { appendLine("${date.format(Instant.ofEpochMilli(it.timestamp))} | ${it.packageName ?: "Android/System"} | ${it.eventName}") }
    }

    companion object {
        const val TAG = "UsageDiagnostics"
        fun formatDuration(millis: Long): String { val seconds = millis / 1_000; return "%02d:%02d:%02d".format(seconds / 3_600, (seconds % 3_600) / 60, seconds % 60) }
    }
}
