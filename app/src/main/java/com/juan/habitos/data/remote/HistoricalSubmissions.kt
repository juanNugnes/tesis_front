package com.juan.habitos.data.remote

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.IOException

data class HistoricalCoverageDto(val value: Double?, val basis: String?)

data class HistoricalProvenanceDto(
    @SerializedName("source_id") val sourceId: String,
    @SerializedName("source_version") val sourceVersion: String,
    @SerializedName("source_semantics") val sourceSemantics: String,
    @SerializedName("adapter_version") val adapterVersion: String,
    @SerializedName("input_fingerprint") val inputFingerprint: String,
)

data class HistoricalRepresentationDto(
    @SerializedName("representation_record_id") val representationRecordId: String,
    @SerializedName("representation_spec_id") val representationSpecId: String,
    @SerializedName("subject_id") val subjectId: String,
    val phenomenon: String,
    @SerializedName("interval_start") val intervalStart: String,
    @SerializedName("interval_end") val intervalEnd: String,
    val value: Double?,
    val unit: String,
    val status: String,
    val coverage: HistoricalCoverageDto,
    @SerializedName("quality_flags") val qualityFlags: List<String>,
    val provenance: HistoricalProvenanceDto,
    @SerializedName("computed_at") val computedAt: String,
)

data class HistoricalSubmissionRequest(
    @SerializedName("request_id") val requestId: String,
    @SerializedName("time_zone") val timeZone: String,
    @SerializedName("measurement_version") val measurementVersion: String,
    val representation: HistoricalRepresentationDto,
)

enum class HistoricalSubmissionStatus { PENDING, SENDING, PROCESSED, CONFLICT, TECHNICAL_ERROR }

data class HistoricalSubmissionResponse(
    val id: String,
    @SerializedName("request_id") val requestId: String,
    @SerializedName("profile_id") val profileId: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("assignment_id") val assignmentId: String,
    @SerializedName("local_date") val localDate: String,
    @SerializedName("time_zone") val timeZone: String,
    @SerializedName("measurement_version") val measurementVersion: String,
    val status: HistoricalSubmissionStatus,
    val attempts: Int,
    @SerializedName("last_attempt_at") val lastAttemptAt: String?,
    @SerializedName("analytics_evaluation_id") val analyticsEvaluationId: String?,
    @SerializedName("analytics_replayed") val analyticsReplayed: Boolean?,
    val retryable: Boolean,
    @SerializedName("last_error_code") val lastErrorCode: String?,
    @SerializedName("last_error_message") val lastErrorMessage: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
)

enum class HistoricalOutboxState { PENDING, AUTHORIZATION_BLOCKED, PROCESSED, CONFLICT, PERMANENT_FAILURE }

data class HistoricalOutboxEntry(
    val profileId: String,
    val localDate: String,
    val request: HistoricalSubmissionRequest,
    val state: HistoricalOutboxState = HistoricalOutboxState.PENDING,
    val submissionId: String? = null,
    val response: HistoricalSubmissionResponse? = null,
    val attemptCount: Int = 0,
    val lastAttemptAtEpochMillis: Long? = null,
)

data class HistoricalOutbox(val entries: List<HistoricalOutboxEntry> = emptyList())

interface HistoricalOutboxStore {
    suspend fun load(profileId: String): HistoricalOutbox
    suspend fun update(profileId: String, transform: (HistoricalOutbox) -> HistoricalOutbox): HistoricalOutbox
}

sealed interface HistoricalDeliveryResult {
    data class Received(val response: HistoricalSubmissionResponse) : HistoricalDeliveryResult
    data object AuthenticationRequired : HistoricalDeliveryResult
    data object Forbidden : HistoricalDeliveryResult
    data class ContractRejected(val code: Int) : HistoricalDeliveryResult
    data class RetryableTransport(val code: Int? = null) : HistoricalDeliveryResult
}

class HistoricalSubmissionRepository(
    private val api: HabitsApi,
    private val authorization: RequestAuthorization,
    private val gson: Gson = Gson(),
) {
    init { require(authorization.principal == RequestPrincipal.DEVICE) }

    suspend fun deliver(entry: HistoricalOutboxEntry): HistoricalDeliveryResult = try {
        authorization.execute { bearer ->
            val response = if (
                entry.response?.status == HistoricalSubmissionStatus.TECHNICAL_ERROR &&
                entry.response.retryable && entry.submissionId != null
            ) {
                api.retryHistoricalSubmission(entry.profileId, entry.submissionId, bearer)
            } else {
                api.postHistoricalSubmission(entry.profileId, bearer, entry.request)
            }
            val body = response.body() ?: response.errorBody()?.charStream()?.use {
                runCatching { gson.fromJson(it, HistoricalSubmissionResponse::class.java) }.getOrNull()
            }
            if (body != null && body.requestId == entry.request.requestId) {
                HistoricalDeliveryResult.Received(body)
            } else when (response.code()) {
                401 -> HistoricalDeliveryResult.AuthenticationRequired
                403 -> HistoricalDeliveryResult.Forbidden
                408, 429, in 500..599 -> HistoricalDeliveryResult.RetryableTransport(response.code())
                else -> HistoricalDeliveryResult.ContractRejected(response.code())
            }
        }
    } catch (_: com.juan.habitos.domain.repository.DeviceAuthenticationRequiredException) {
        HistoricalDeliveryResult.AuthenticationRequired
    } catch (_: IOException) {
        HistoricalDeliveryResult.RetryableTransport()
    } catch (exception: Exception) {
        if (generateSequence(exception as Throwable?) { it.cause }.any { it is IOException }) {
            HistoricalDeliveryResult.RetryableTransport()
        } else throw exception
    }
}
