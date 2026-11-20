package com.juan.habitos.data.device

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

const val F12_EXPORT_ADAPTER_VERSION = "f12-android-export-adapter-v1-candidate"
const val F12_RAW_EVENT_SCHEMA_VERSION = "raw-usage-event-v1"
const val F12_MAX_EXPORT_BYTES = 32 * 1024 * 1024
const val F12_MAX_EVENTS_PER_RUN = 100_000
const val F12_MAX_RUNS = 10_000

data class F12RawExportPair(
    val collectorRunId: String,
    val summaryBytes: ByteArray,
    val eventsBytes: ByteArray,
    val summarySha256: String,
    val eventsSha256: String,
) {
    override fun equals(other: Any?): Boolean = other is F12RawExportPair &&
        collectorRunId == other.collectorRunId &&
        summaryBytes.contentEquals(other.summaryBytes) &&
        eventsBytes.contentEquals(other.eventsBytes) &&
        summarySha256 == other.summarySha256 &&
        eventsSha256 == other.eventsSha256

    override fun hashCode(): Int = 31 * collectorRunId.hashCode() + summaryBytes.contentHashCode()
}

enum class F12CaptureAssessment {
    CAPTURE_OBSERVED_UNVERIFIED,
    NO_EVENTS_OBSERVED,
    PARTIAL_CAPTURE,
    CAPTURE_FAILED,
}

data class F12CaptureQuality(
    val assessment: F12CaptureAssessment,
    val successfulRunCount: Int,
    val failedRunCount: Int,
    val eventCount: Int,
    val dayQuerySucceeded: Boolean,
    val priorScreenStateObserved: Boolean,
    val deviceRestartObserved: Boolean,
    val qualityFlags: List<String>,
)

data class F12RawEvidenceRecord(
    val envelopeVersion: Int = 1,
    val captureId: String,
    val adapterVersion: String = F12_EXPORT_ADAPTER_VERSION,
    val profileId: String,
    val deviceId: String,
    val localDate: String,
    val timezoneId: String,
    val revision: Int,
    val createdAtEpochMillis: Long,
    val contentSha256: String,
    val quality: F12CaptureQuality,
    val exportPairs: List<F12RawExportPair>,
)

/** Produces only the exact summary JSON and event JSONL accepted by Analytics. */
object F12RawEvidenceContract {
    private val summaryGson: Gson = GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create()
    private val eventGson: Gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    fun export(run: UsageCollectorRun): F12RawExportPair {
        validate(run)
        val summary = ContractSummary(
            collectorRunId = run.collectorRunId,
            requestedBeginEpochMs = run.requestedStartEpochMillis,
            requestedEndEpochMs = run.requestedEndEpochMillis,
            collectedAtEpochMs = run.collectedAtEpochMillis,
            usageAccessAvailable = run.usagePermissionAvailable,
            queryReturnedNull = run.queryReturnedNull,
            eventCount = run.eventCount,
            errorCode = run.errorCode,
            errorMessage = run.errorMessage,
        )
        val summaryBytes = summaryGson.toJson(summary).toByteArray(Charsets.UTF_8)
        val eventsBytes = run.events.joinToString(separator = "\n", postfix = if (run.events.isEmpty()) "" else "\n") { event ->
            eventGson.toJson(
                ContractEvent(
                    schemaVersion = F12_RAW_EVENT_SCHEMA_VERSION,
                    collectorRunId = event.collectorRunId,
                    queryBeginEpochMs = event.requestedStartEpochMillis,
                    queryEndEpochMs = event.requestedEndEpochMillis,
                    collectedAtEpochMs = event.collectedAtEpochMillis,
                    eventTimeEpochMs = event.timestampEpochMillis,
                    eventTypeCode = event.eventTypeCode,
                    eventTypeName = event.eventTypeName,
                    packageName = event.packageName,
                    className = event.className,
                    androidApiLevel = run.androidApiLevel,
                    manufacturer = run.manufacturer,
                    model = run.deviceModel,
                    queryOrdinal = event.queryOrdinal,
                )
            )
        }.toByteArray(Charsets.UTF_8)
        require(summaryBytes.size + eventsBytes.size <= F12_MAX_EXPORT_BYTES) { "F12 export pair exceeds 32 MiB" }
        return F12RawExportPair(
            collectorRunId = run.collectorRunId,
            summaryBytes = summaryBytes,
            eventsBytes = eventsBytes,
            summarySha256 = summaryBytes.sha256(),
            eventsSha256 = eventsBytes.sha256(),
        )
    }

    fun record(
        diagnostic: DailyHistoricalUsageDiagnostic,
        revision: Int,
        createdAt: Instant = Instant.now(),
    ): F12RawEvidenceRecord {
        val profileId = requireNotNull(diagnostic.profileId?.takeIf(String::isNotBlank)) { "profileId is required" }
        val deviceId = requireNotNull(diagnostic.deviceId?.takeIf(String::isNotBlank)) { "deviceId is required" }
        require(revision > 0) { "revision must be positive" }
        require(diagnostic.collectorRuns.size <= F12_MAX_RUNS) { "Too many F12 collector runs" }
        require(diagnostic.collectorRuns.map(UsageCollectorRun::collectorRunId).distinct().size == diagnostic.collectorRuns.size) {
            "Duplicate collectorRunId"
        }
        val pairs = diagnostic.collectorRuns.map(::export)
        require(pairs.sumOf { it.summaryBytes.size.toLong() + it.eventsBytes.size } <= F12_MAX_EXPORT_BYTES) {
            "F12 evidence exceeds 32 MiB"
        }
        val dayRun = diagnostic.collectorRuns.firstOrNull {
            it.requestedStartEpochMillis == diagnostic.dayStartEpochMillis &&
                it.requestedEndEpochMillis == diagnostic.dayEndEpochMillis
        }
        val successful = diagnostic.collectorRuns.count(UsageCollectorRun::successful)
        val failed = diagnostic.collectorRuns.size - successful
        val eventCount = diagnostic.collectorRuns.sumOf(UsageCollectorRun::eventCount)
        val restartObserved = diagnostic.collectorRuns.any { run ->
            run.events.any { it.eventTypeCode == 26 || it.eventTypeCode == 27 }
        }
        val flags = buildList {
            add("DAY_COMPLETENESS_NOT_ASSERTED")
            if (dayRun == null) add("DAY_QUERY_MISSING")
            if (dayRun != null && !dayRun.successful) add("DAY_QUERY_FAILED")
            if (diagnostic.previousScreenState == null) add("PRIOR_SCREEN_STATE_NOT_OBSERVED")
            if (restartObserved) add("DEVICE_LIFECYCLE_REVIEW_REQUIRED")
            if (eventCount == 0) add("NO_EVENTS_OBSERVED")
        }
        val assessment = when {
            dayRun == null || !dayRun.successful -> F12CaptureAssessment.CAPTURE_FAILED
            failed > 0 -> F12CaptureAssessment.PARTIAL_CAPTURE
            eventCount == 0 -> F12CaptureAssessment.NO_EVENTS_OBSERVED
            else -> F12CaptureAssessment.CAPTURE_OBSERVED_UNVERIFIED
        }
        val contentDigest = MessageDigest.getInstance("SHA-256").apply {
            pairs.forEach {
                update(it.summaryBytes)
                update(byteArrayOf(0))
                update(it.eventsBytes)
                update(byteArrayOf(0))
            }
        }.digest().hex()
        val captureId = listOf(profileId, deviceId, diagnostic.localDate, revision.toString(), contentDigest)
            .joinToString("\u0000").toByteArray(Charsets.UTF_8).sha256()
        return F12RawEvidenceRecord(
            captureId = captureId,
            profileId = profileId,
            deviceId = deviceId,
            localDate = diagnostic.localDate,
            timezoneId = diagnostic.timezoneId,
            revision = revision,
            createdAtEpochMillis = createdAt.toEpochMilli(),
            contentSha256 = contentDigest,
            quality = F12CaptureQuality(
                assessment = assessment,
                successfulRunCount = successful,
                failedRunCount = failed,
                eventCount = eventCount,
                dayQuerySucceeded = dayRun?.successful == true,
                priorScreenStateObserved = diagnostic.previousScreenState != null,
                deviceRestartObserved = restartObserved,
                qualityFlags = flags,
            ),
            exportPairs = pairs,
        )
    }

    private fun validate(run: UsageCollectorRun) {
        val runId = runCatching { UUID.fromString(run.collectorRunId) }.getOrNull()
        require(runId != null && runId.version() == 4 && runId.toString() == run.collectorRunId) {
            "collectorRunId must be a canonical UUID v4"
        }
        require(run.requestedStartEpochMillis < run.requestedEndEpochMillis) { "Invalid query range" }
        require(run.collectedAtEpochMillis >= run.requestedEndEpochMillis) { "Collection predates query end" }
        require(run.eventCount == run.events.size && run.eventCount <= F12_MAX_EVENTS_PER_RUN) { "Invalid event count" }
        require(run.androidApiLevel >= 28) { "F12 export requires Android API 28 or newer" }
        require(run.manufacturer.isNotBlank() && run.deviceModel.isNotBlank()) { "Hardware metadata is required" }
        val expectedState = when (run.errorCode) {
            null -> true to false
            CollectorErrorCode.USAGE_ACCESS_NOT_GRANTED -> false to false
            CollectorErrorCode.QUERY_RETURNED_NULL -> true to true
            CollectorErrorCode.SECURITY_EXCEPTION, CollectorErrorCode.QUERY_RUNTIME_EXCEPTION -> true to false
            else -> throw IllegalArgumentException("Unsupported collector errorCode: ${run.errorCode}")
        }
        require(run.usagePermissionAvailable == expectedState.first && run.queryReturnedNull == expectedState.second) {
            "Collector state does not match errorCode"
        }
        require(run.errorCode != null || run.errorMessage == null) { "Successful query cannot contain errorMessage" }
        require(run.errorCode == null || run.events.isEmpty()) { "Failed query cannot contain events" }
        require(run.events.map(RawCollectorEvent::queryOrdinal).toSet() == (0 until run.events.size).toSet()) {
            "queryOrdinal must contain exactly 0..eventCount-1"
        }
        val ordered = run.events.sortedWith(compareBy<RawCollectorEvent> { it.timestampEpochMillis }.thenBy { it.queryOrdinal })
        require(run.events == ordered) { "Events must be ordered by timestamp and queryOrdinal" }
        run.events.forEach { event ->
            require(event.collectorRunId == run.collectorRunId) { "Event belongs to another collector run" }
            require(event.requestedStartEpochMillis == run.requestedStartEpochMillis &&
                event.requestedEndEpochMillis == run.requestedEndEpochMillis &&
                event.collectedAtEpochMillis == run.collectedAtEpochMillis
            ) { "Event query metadata differs from summary" }
            require(event.timestampEpochMillis in run.requestedStartEpochMillis until run.requestedEndEpochMillis) {
                "Event timestamp is outside [begin, end)"
            }
            require(event.eventTypeCode >= 0) { "Negative event type" }
            require(event.eventTypeName == UsageEventTypeNameMapper.nameFor(event.eventTypeCode, run.androidApiLevel)) {
                "eventTypeName is incompatible with Android API level"
            }
        }
    }

    private data class ContractSummary(
        val collectorRunId: String,
        val requestedBeginEpochMs: Long,
        val requestedEndEpochMs: Long,
        val collectedAtEpochMs: Long,
        val usageAccessAvailable: Boolean,
        val queryReturnedNull: Boolean,
        val eventCount: Int,
        val errorCode: String?,
        val errorMessage: String?,
    )

    private data class ContractEvent(
        val schemaVersion: String,
        val collectorRunId: String,
        val queryBeginEpochMs: Long,
        val queryEndEpochMs: Long,
        val collectedAtEpochMs: Long,
        val eventTimeEpochMs: Long,
        val eventTypeCode: Int,
        val eventTypeName: String,
        val packageName: String?,
        val className: String?,
        val androidApiLevel: Int,
        val manufacturer: String,
        val model: String,
        val queryOrdinal: Int,
    )
}

internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).hex()
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
