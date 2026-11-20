package com.juan.habitos.data.device

import com.google.gson.GsonBuilder
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

const val HISTORICAL_DIAGNOSTIC_FORMAT_VERSION = 1

object CollectorErrorCode {
    const val USAGE_ACCESS_NOT_GRANTED = "USAGE_ACCESS_NOT_GRANTED"
    const val QUERY_RETURNED_NULL = "QUERY_RETURNED_NULL"
    const val SECURITY_EXCEPTION = "SECURITY_EXCEPTION"
    const val QUERY_RUNTIME_EXCEPTION = "QUERY_RUNTIME_EXCEPTION"

    // Source-compatible aliases for the existing diagnostics callers.
    const val USAGE_PERMISSION_NOT_AVAILABLE = USAGE_ACCESS_NOT_GRANTED
    const val QUERY_EVENTS_RETURNED_NULL = QUERY_RETURNED_NULL
    const val QUERY_EVENTS_EXCEPTION = QUERY_RUNTIME_EXCEPTION
}

data class CollectedUsageEvent(
    val timestampEpochMillis: Long,
    val eventTypeCode: Int,
    val eventTypeName: String,
    val packageName: String?,
    val className: String?,
)

data class RawCollectorEvent(
    val formatVersion: Int = HISTORICAL_DIAGNOSTIC_FORMAT_VERSION,
    val collectorRunId: String,
    val requestedStartEpochMillis: Long,
    val requestedEndEpochMillis: Long,
    val collectedAtEpochMillis: Long,
    val timestampEpochMillis: Long,
    val eventTypeCode: Int,
    val eventTypeName: String,
    val queryOrdinal: Int,
    val packageName: String?,
    val className: String?,
)

data class UsageCollectorRun(
    val collectorRunId: String,
    val requestedStartEpochMillis: Long,
    val requestedEndEpochMillis: Long,
    val collectedAtEpochMillis: Long,
    val usagePermissionAvailable: Boolean,
    val queryReturnedNull: Boolean,
    val eventCount: Int,
    val errorCode: String?,
    val errorMessage: String?,
    val androidVersion: String,
    val deviceModel: String,
    val events: List<RawCollectorEvent>,
    val androidApiLevel: Int = 28,
    val manufacturer: String = "",
) {
    val successful: Boolean
        get() = usagePermissionAvailable && !queryReturnedNull && errorCode == null
}

data class PreviousScreenStateEvidence(
    val collectorRunId: String,
    val event: RawCollectorEvent,
)

data class DailyHistoricalUsageDiagnostic(
    val formatVersion: Int = HISTORICAL_DIAGNOSTIC_FORMAT_VERSION,
    val profileId: String?,
    val deviceId: String?,
    val localDate: String,
    val timezoneId: String,
    val dayStartEpochMillis: Long,
    val dayEndEpochMillis: Long,
    val previousScreenState: PreviousScreenStateEvidence?,
    val collectorRuns: List<UsageCollectorRun>,
)

data class DiagnosticDayWindow(
    val localDate: LocalDate,
    val timezoneId: String,
    val dayStartEpochMillis: Long,
    val dayEndEpochMillis: Long,
)

object UsageCollectorRunFactory {
    fun success(
        collectorRunId: String = UUID.randomUUID().toString(),
        requestedStartEpochMillis: Long,
        requestedEndEpochMillis: Long,
        collectedAtEpochMillis: Long,
        androidVersion: String,
        deviceModel: String,
        eventsInQueryOrder: List<CollectedUsageEvent>,
        androidApiLevel: Int = 28,
        manufacturer: String = "",
    ): UsageCollectorRun {
        val exportedEvents = eventsInQueryOrder.mapIndexed { ordinal, event ->
            RawCollectorEvent(
                collectorRunId = collectorRunId,
                requestedStartEpochMillis = requestedStartEpochMillis,
                requestedEndEpochMillis = requestedEndEpochMillis,
                collectedAtEpochMillis = collectedAtEpochMillis,
                timestampEpochMillis = event.timestampEpochMillis,
                eventTypeCode = event.eventTypeCode,
                eventTypeName = event.eventTypeName,
                queryOrdinal = ordinal,
                packageName = event.packageName,
                className = event.className,
            )
        }.sortedWith(compareBy<RawCollectorEvent> { it.timestampEpochMillis }.thenBy { it.queryOrdinal })
        return baseRun(
            collectorRunId = collectorRunId,
            requestedStartEpochMillis = requestedStartEpochMillis,
            requestedEndEpochMillis = requestedEndEpochMillis,
            collectedAtEpochMillis = collectedAtEpochMillis,
            usagePermissionAvailable = true,
            queryReturnedNull = false,
            errorCode = null,
            errorMessage = null,
            androidVersion = androidVersion,
            deviceModel = deviceModel,
            events = exportedEvents,
            androidApiLevel = androidApiLevel,
            manufacturer = manufacturer,
        )
    }

    fun permissionUnavailable(
        collectorRunId: String = UUID.randomUUID().toString(),
        requestedStartEpochMillis: Long,
        requestedEndEpochMillis: Long,
        collectedAtEpochMillis: Long,
        androidVersion: String,
        deviceModel: String,
        androidApiLevel: Int = 28,
        manufacturer: String = "",
    ) = failedRun(
        collectorRunId, requestedStartEpochMillis, requestedEndEpochMillis, collectedAtEpochMillis,
        usagePermissionAvailable = false,
        queryReturnedNull = false,
        errorCode = CollectorErrorCode.USAGE_PERMISSION_NOT_AVAILABLE,
        errorMessage = "Usage access is not available",
        androidVersion = androidVersion,
        deviceModel = deviceModel,
        androidApiLevel = androidApiLevel,
        manufacturer = manufacturer,
    )

    fun queryReturnedNull(
        collectorRunId: String = UUID.randomUUID().toString(),
        requestedStartEpochMillis: Long,
        requestedEndEpochMillis: Long,
        collectedAtEpochMillis: Long,
        androidVersion: String,
        deviceModel: String,
        androidApiLevel: Int = 28,
        manufacturer: String = "",
    ) = failedRun(
        collectorRunId, requestedStartEpochMillis, requestedEndEpochMillis, collectedAtEpochMillis,
        usagePermissionAvailable = true,
        queryReturnedNull = true,
        errorCode = CollectorErrorCode.QUERY_EVENTS_RETURNED_NULL,
        errorMessage = "UsageStatsManager.queryEvents returned null",
        androidVersion = androidVersion,
        deviceModel = deviceModel,
        androidApiLevel = androidApiLevel,
        manufacturer = manufacturer,
    )

    fun exception(
        collectorRunId: String = UUID.randomUUID().toString(),
        requestedStartEpochMillis: Long,
        requestedEndEpochMillis: Long,
        collectedAtEpochMillis: Long,
        androidVersion: String,
        deviceModel: String,
        throwable: Throwable,
        usagePermissionAvailable: Boolean = true,
        androidApiLevel: Int = 28,
        manufacturer: String = "",
    ) = failedRun(
        collectorRunId, requestedStartEpochMillis, requestedEndEpochMillis, collectedAtEpochMillis,
        usagePermissionAvailable = usagePermissionAvailable,
        queryReturnedNull = false,
        errorCode = if (throwable is SecurityException) {
            CollectorErrorCode.SECURITY_EXCEPTION
        } else {
            CollectorErrorCode.QUERY_RUNTIME_EXCEPTION
        },
        errorMessage = (throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName).take(500),
        androidVersion = androidVersion,
        deviceModel = deviceModel,
        androidApiLevel = androidApiLevel,
        manufacturer = manufacturer,
    )

    private fun failedRun(
        collectorRunId: String,
        requestedStartEpochMillis: Long,
        requestedEndEpochMillis: Long,
        collectedAtEpochMillis: Long,
        usagePermissionAvailable: Boolean,
        queryReturnedNull: Boolean,
        errorCode: String,
        errorMessage: String,
        androidVersion: String,
        deviceModel: String,
        androidApiLevel: Int,
        manufacturer: String,
    ) = baseRun(
        collectorRunId, requestedStartEpochMillis, requestedEndEpochMillis, collectedAtEpochMillis,
        usagePermissionAvailable, queryReturnedNull, errorCode, errorMessage, androidVersion, deviceModel,
        events = emptyList(),
        androidApiLevel = androidApiLevel,
        manufacturer = manufacturer,
    )

    private fun baseRun(
        collectorRunId: String,
        requestedStartEpochMillis: Long,
        requestedEndEpochMillis: Long,
        collectedAtEpochMillis: Long,
        usagePermissionAvailable: Boolean,
        queryReturnedNull: Boolean,
        errorCode: String?,
        errorMessage: String?,
        androidVersion: String,
        deviceModel: String,
        events: List<RawCollectorEvent>,
        androidApiLevel: Int = 28,
        manufacturer: String = "",
    ) = UsageCollectorRun(
        collectorRunId = collectorRunId,
        requestedStartEpochMillis = requestedStartEpochMillis,
        requestedEndEpochMillis = requestedEndEpochMillis,
        collectedAtEpochMillis = collectedAtEpochMillis,
        usagePermissionAvailable = usagePermissionAvailable,
        queryReturnedNull = queryReturnedNull,
        eventCount = events.size,
        errorCode = errorCode,
        errorMessage = errorMessage,
        androidVersion = androidVersion,
        deviceModel = deviceModel,
        events = events,
        androidApiLevel = androidApiLevel,
        manufacturer = manufacturer,
    )
}

interface UsageEventRunCollector {
    fun collectRun(requestedStartEpochMillis: Long, requestedEndEpochMillis: Long): UsageCollectorRun
}

class DailyHistoricalUsageDiagnosticCollector(
    private val collector: UsageEventRunCollector,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    fun collect(
        date: LocalDate,
        zoneId: ZoneId,
        profileId: String?,
        deviceId: String?,
        includeOverlappingRuns: Boolean,
    ): DailyHistoricalUsageDiagnostic {
        val window = dayWindow(date, zoneId)
        val previousStart = date.minusDays(PREVIOUS_STATE_LOOKBACK_DAYS)
            .atStartOfDay(zoneId).toInstant().toEpochMilli()
        val previousRun = collector.collectRun(previousStart, window.dayStartEpochMillis)
        val dayRun = collector.collectRun(window.dayStartEpochMillis, window.dayEndEpochMillis)
        val runs = mutableListOf(previousRun, dayRun)
        if (includeOverlappingRuns) {
            overlappingWindows(window, nowEpochMillis()).forEach { (start, end) ->
                runs += collector.collectRun(start, end)
            }
        }
        val previousEvent = previousRun.takeIf { it.successful }?.events
            ?.asSequence()
            ?.filter { it.timestampEpochMillis < window.dayStartEpochMillis && it.isScreenStateEvent() }
            ?.maxWithOrNull(compareBy<RawCollectorEvent> { it.timestampEpochMillis }.thenBy { it.queryOrdinal })
        return DailyHistoricalUsageDiagnostic(
            profileId = profileId,
            deviceId = deviceId,
            localDate = date.toString(),
            timezoneId = zoneId.id,
            dayStartEpochMillis = window.dayStartEpochMillis,
            dayEndEpochMillis = window.dayEndEpochMillis,
            previousScreenState = previousEvent?.let { PreviousScreenStateEvidence(previousRun.collectorRunId, it) },
            collectorRuns = runs,
        )
    }

    companion object {
        private const val PREVIOUS_STATE_LOOKBACK_DAYS = 7L
        private const val MINUTE_MILLIS = 60_000L

        fun dayWindow(date: LocalDate, zoneId: ZoneId): DiagnosticDayWindow = DiagnosticDayWindow(
            localDate = date,
            timezoneId = zoneId.id,
            dayStartEpochMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            dayEndEpochMillis = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
        )

        internal fun overlappingWindows(window: DiagnosticDayWindow, now: Long): List<Pair<Long, Long>> {
            val effectiveEnd = minOf(now, window.dayEndEpochMillis)
            val available = effectiveEnd - window.dayStartEpochMillis
            if (available < 2L) return emptyList()
            if (available >= 90 * MINUTE_MILLIS) {
                return listOf(
                    (effectiveEnd - 90 * MINUTE_MILLIS) to (effectiveEnd - 30 * MINUTE_MILLIS),
                    (effectiveEnd - 60 * MINUTE_MILLIS) to effectiveEnd,
                )
            }
            return listOf(
                window.dayStartEpochMillis to (window.dayStartEpochMillis + available * 2 / 3),
                (window.dayStartEpochMillis + available / 3) to effectiveEnd,
            )
        }
    }
}

object HistoricalUsageDiagnosticJson {
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    fun encode(value: DailyHistoricalUsageDiagnostic): String = gson.toJson(value)
}

private fun RawCollectorEvent.isScreenStateEvent(): Boolean =
    eventTypeName == "SCREEN_INTERACTIVE" || eventTypeName == "SCREEN_NON_INTERACTIVE"
