package com.juan.habitos.data.device

import com.google.gson.GsonBuilder
import com.juan.habitos.data.remote.HistoricalCoverageDto
import com.juan.habitos.data.remote.HistoricalProvenanceDto
import com.juan.habitos.data.remote.HistoricalRepresentationDto
import com.juan.habitos.data.remote.HistoricalSubmissionRequest
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun interface F12HistoricalRequestCollector {
    fun collect(profileId: String, deviceId: String, localDate: LocalDate, zoneId: ZoneId): HistoricalSubmissionRequest
}

class AndroidF12HistoricalRequestCollector(
    eventCollector: UsageEventRunCollector,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : F12HistoricalRequestCollector {
    private val diagnostics = DailyHistoricalUsageDiagnosticCollector(eventCollector, nowEpochMillis)

    override fun collect(
        profileId: String,
        deviceId: String,
        localDate: LocalDate,
        zoneId: ZoneId,
    ): HistoricalSubmissionRequest {
        val diagnostic = diagnostics.collect(localDate, zoneId, profileId, deviceId, includeOverlappingRuns = false)
        val observation = InteractiveScreenObservationCalculator.calculate(diagnostic, nowEpochMillis())
        return F12HistoricalRequestFactory.create(diagnostic, observation)
    }
}

object F12HistoricalRequestFactory {
    const val MEASUREMENT_VERSION = "capture-test-v1"
    const val REPRESENTATION_SPEC_ID = "daily_use_duration_minutes_v2"
    const val SOURCE_VERSION = "f12-backend-bridge-v1-candidate"
    private const val SOURCE_ID = "android-usage-events"
    private const val SOURCE_SEMANTICS = "Interactive screen duration on assigned device; user attribution unverified"
    private const val COVERAGE_BASIS = "daily_capture_completeness_not_quantified"
    private val gson = GsonBuilder().serializeNulls().create()

    fun create(
        diagnostic: DailyHistoricalUsageDiagnostic,
        observation: DailyDeviceInteractiveScreenObservation,
    ): HistoricalSubmissionRequest {
        require(diagnostic.profileId != null && diagnostic.deviceId != null)
        require(observation.localDate == diagnostic.localDate)
        require(observation.timezoneId == diagnostic.timezoneId)
        require(observation.dayStartEpochMillis == diagnostic.dayStartEpochMillis)
        require(observation.dayEndEpochMillis == diagnostic.dayEndEpochMillis)
        require(observation.calculatedAtEpochMillis >= diagnostic.dayEndEpochMillis) { "F12 day must be completely closed" }

        val binding = "${diagnostic.profileId}|${diagnostic.deviceId}|${diagnostic.timezoneId}|$MEASUREMENT_VERSION|$SOURCE_VERSION"
        val seriesHash = sha256(binding)
        val recordHash = sha256("$seriesHash|${diagnostic.dayStartEpochMillis}|${diagnostic.dayEndEpochMillis}|v2")
        val evidenceHash = sha256(gson.toJson(mapOf(
            "calculation_version" to observation.calculationVersion,
            "diagnostic" to diagnostic,
            "observation" to observation,
        )))
        val requestId = "f12-${sha256("$binding|${diagnostic.localDate}")}".take(128)
        val qualityFlags = buildList {
            if (observation.status == InteractiveScreenObservationStatus.MISSING) add("INCOMPLETE_DAILY_CAPTURE")
            if (observation.qualityEvidence.restartInDay) add("DEVICE_LIFECYCLE_REVIEW_REQUIRED")
        }.distinct().sorted()

        return HistoricalSubmissionRequest(
            requestId = requestId,
            timeZone = diagnostic.timezoneId,
            measurementVersion = MEASUREMENT_VERSION,
            representation = HistoricalRepresentationDto(
                representationRecordId = "rep-$recordHash",
                representationSpecId = REPRESENTATION_SPEC_ID,
                subjectId = "series-$seriesHash",
                phenomenon = "DAILY_USE_DURATION",
                intervalStart = Instant.ofEpochMilli(diagnostic.dayStartEpochMillis).toString(),
                intervalEnd = Instant.ofEpochMilli(diagnostic.dayEndEpochMillis).toString(),
                value = observation.interactiveScreenMinutes,
                unit = "minutes",
                status = observation.status.name,
                coverage = HistoricalCoverageDto(null, COVERAGE_BASIS),
                qualityFlags = qualityFlags,
                provenance = HistoricalProvenanceDto(
                    sourceId = SOURCE_ID,
                    sourceVersion = SOURCE_VERSION,
                    sourceSemantics = SOURCE_SEMANTICS,
                    adapterVersion = "v2",
                    inputFingerprint = evidenceHash,
                ),
                computedAt = Instant.ofEpochMilli(observation.calculatedAtEpochMillis).toString(),
            ),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
