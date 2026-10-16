package com.juan.habitos.data.device

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.pm.PackageManager
import com.juan.habitos.data.remote.AppUsageCreateDto
import com.juan.habitos.data.remote.NightWindowDto
import com.juan.habitos.data.remote.UsageMetricStatusesDto
import com.juan.habitos.data.remote.UsageSnapshotRequest
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.model.UsageMetricStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

fun interface UsageSnapshotCollector {
    fun capture(profile: Profile, capturedAt: Instant): UsageSnapshotRequest
}

data class AndroidAppAggregate(
    val packageName: String,
    val appName: String?,
    val foregroundMillis: Long,
)

data class AndroidAggregateEvidence(
    val applications: List<AndroidAppAggregate>?,
    val events: List<RawUsageEvent>?,
    val nightForegroundMillis: Long?,
    val baseFailureStatus: UsageMetricStatus? = null,
    val nightFailureStatus: UsageMetricStatus? = null,
)

object AggregatedUsageSnapshotMapper {
    const val CALCULATION_VERSION = "android-collector-1.0"

    fun map(
        evidence: AndroidAggregateEvidence,
        profile: Profile,
        capturedAt: Instant,
        zoneId: ZoneId,
        snapshotId: String = UUID.randomUUID().toString(),
    ): UsageSnapshotRequest {
        val captured = capturedAt.atZone(zoneId)
        val date = captured.toLocalDate()
        val start = date.atStartOfDay(zoneId)
        val end = captured.coerceAtMost(date.plusDays(1).atStartOfDay(zoneId))
        val baseFailure = evidence.baseFailureStatus
        val appStatus = baseFailure ?: if (evidence.applications == null) UsageMetricStatus.CAPTURE_ERROR else UsageMetricStatus.MEASURED
        val usableApplications = evidence.applications.takeIf { appStatus == UsageMetricStatus.MEASURED || appStatus == UsageMetricStatus.INCOMPLETE }
        val applications = usableApplications?.map { app ->
            AppUsageCreateDto(
                packageName = app.packageName,
                appName = app.appName,
                category = null,
                usageMinutes = minutes(app.foregroundMillis),
            )
        }
        val totalMinutes = usableApplications?.sumOf(AndroidAppAggregate::foregroundMillis)?.let(::minutes)

        val usableEvents = evidence.events.takeIf { baseFailure == null }
        val analysis = usableEvents?.let(UsageDiagnosticsCalculator::analyzeSessions)
        val eventStatus = when {
            baseFailure != null -> baseFailure
            usableEvents == null -> UsageMetricStatus.CAPTURE_ERROR
            analysis?.incomplete?.isNotEmpty() == true -> UsageMetricStatus.INCOMPLETE
            else -> UsageMetricStatus.MEASURED
        }
        val sessions = analysis?.completed
        val sessionCount = sessions?.size
        val computedMaxSessionMinutes = sessions?.maxOfOrNull { it.durationMillis }?.let(::minutes) ?: sessions?.let { 0 }
        val maxSessionConsistent = computedMaxSessionMinutes == null || totalMinutes == null || computedMaxSessionMinutes <= totalMinutes
        val maxSessionMinutes = computedMaxSessionMinutes.takeIf { maxSessionConsistent }
        val maxSessionStatus = if (maxSessionConsistent) eventStatus else UsageMetricStatus.INCOMPLETE
        val unlockCount = usableEvents?.count { it.eventType == UsageEvents.Event.KEYGUARD_HIDDEN }

        val nightWindow = parseNightWindow(profile)
        val nightStatus = when {
            nightWindow == null -> UsageMetricStatus.UNAVAILABLE
            baseFailure != null -> baseFailure
            evidence.nightFailureStatus != null -> evidence.nightFailureStatus
            evidence.nightForegroundMillis == null -> UsageMetricStatus.CAPTURE_ERROR
            else -> UsageMetricStatus.MEASURED
        }
        val computedNightMinutes = evidence.nightForegroundMillis
            ?.takeIf { nightStatus == UsageMetricStatus.MEASURED || nightStatus == UsageMetricStatus.INCOMPLETE }
            ?.let(::minutes)
        val nightConsistent = computedNightMinutes == null || totalMinutes == null || computedNightMinutes <= totalMinutes
        val nightMinutes = computedNightMinutes.takeIf { nightConsistent }
        val effectiveNightStatus = if (nightConsistent) nightStatus else UsageMetricStatus.INCOMPLETE

        return UsageSnapshotRequest(
            snapshotId = snapshotId,
            capturedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(captured),
            date = date.toString(),
            periodStartAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(start),
            periodEndAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(end),
            timezone = zoneId.id,
            coverageStatus = if (analysis?.incomplete?.isNotEmpty() == true) "PARTIAL" else "UNKNOWN",
            totalUsageMinutes = totalMinutes,
            sessionCount = sessionCount,
            nightUsageMinutes = nightMinutes,
            maxSessionMinutes = maxSessionMinutes,
            unlockCount = unlockCount,
            applications = applications,
            metricStatuses = UsageMetricStatusesDto(
                totalUsageMinutes = appStatus.name,
                sessionCount = eventStatus.name,
                nightUsageMinutes = effectiveNightStatus.name,
                maxSessionMinutes = maxSessionStatus.name,
                unlockCount = eventStatus.name,
                applications = appStatus.name,
            ),
            nightWindow = nightWindow,
        )
    }

    fun nightIntervals(
        date: LocalDate,
        periodStart: Instant,
        periodEnd: Instant,
        zoneId: ZoneId,
        startTime: LocalTime,
        endTime: LocalTime,
    ): List<Pair<Instant, Instant>> = listOf(date.minusDays(1), date).mapNotNull { anchor ->
        val windowStart = ZonedDateTime.of(anchor, startTime, zoneId)
        val windowEndDate = if (endTime <= startTime) anchor.plusDays(1) else anchor
        val windowEnd = ZonedDateTime.of(windowEndDate, endTime, zoneId)
        val clippedStart = maxOf(windowStart.toInstant(), periodStart)
        val clippedEnd = minOf(windowEnd.toInstant(), periodEnd)
        (clippedStart to clippedEnd).takeIf { clippedEnd > clippedStart }
    }

    private fun parseNightWindow(profile: Profile): NightWindowDto? {
        val start = profile.sleepStart?.let(::parseTime) ?: return null
        val end = profile.sleepEnd?.let(::parseTime) ?: return null
        if (start == end) return null
        return NightWindowDto(startLocalTime = start.toString(), endLocalTime = end.toString())
    }

    internal fun parseTime(value: String): LocalTime? = runCatching { LocalTime.parse(value) }.getOrNull()
    internal fun minutes(millis: Long): Int = (millis.coerceAtLeast(0) / 60_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

class AndroidAggregatedUsageCollector(
    private val dataSource: AndroidDeviceUsageDataSource,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val snapshotId: () -> String = { UUID.randomUUID().toString() },
) : UsageSnapshotCollector {
    override fun capture(profile: Profile, capturedAt: Instant): UsageSnapshotRequest {
        val zone = zoneId()
        val date = capturedAt.atZone(zone).toLocalDate()
        val start = date.atStartOfDay(zone).toInstant()
        val end = capturedAt.coerceAtMost(date.plusDays(1).atStartOfDay(zone).toInstant())
        val evidence = dataSource.queryAggregates(
            start = start,
            end = end,
            nightIntervals = nightIntervals(profile, date, start, end, zone),
        )
        return AggregatedUsageSnapshotMapper.map(evidence, profile, capturedAt, zone, snapshotId())
    }

    private fun nightIntervals(
        profile: Profile,
        date: LocalDate,
        start: Instant,
        end: Instant,
        zone: ZoneId,
    ): List<Pair<Instant, Instant>> {
        val sleepStart = profile.sleepStart?.let(AggregatedUsageSnapshotMapper::parseTime) ?: return emptyList()
        val sleepEnd = profile.sleepEnd?.let(AggregatedUsageSnapshotMapper::parseTime) ?: return emptyList()
        if (sleepStart == sleepEnd) return emptyList()
        return AggregatedUsageSnapshotMapper.nightIntervals(date, start, end, zone, sleepStart, sleepEnd)
    }
}

internal fun UsageStatsManager.readApplicationAggregates(
    packageManager: PackageManager,
    fromMillis: Long,
    toMillis: Long,
): List<AndroidAppAggregate>? = queryUsageStats(UsageStatsManager.INTERVAL_DAILY, fromMillis, toMillis)?.orEmpty()
    ?.filter { it.totalTimeInForeground > 0L && !it.packageName.isNullOrBlank() }
    ?.groupBy { it.packageName }
    ?.map { (packageName, values) ->
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
        AndroidAppAggregate(packageName, label, values.sumOf { it.totalTimeInForeground })
    }
    ?.sortedByDescending(AndroidAppAggregate::foregroundMillis)
