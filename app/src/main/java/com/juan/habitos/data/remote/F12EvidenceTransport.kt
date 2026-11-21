package com.juan.habitos.data.remote

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.juan.habitos.data.device.F12EvidenceDelivery
import com.juan.habitos.data.device.F12EvidenceDeliveryState
import com.juan.habitos.data.device.F12EvidenceReceipt
import com.juan.habitos.data.device.F12EvidenceState
import com.juan.habitos.data.device.F12RawEvidenceRecord
import com.juan.habitos.data.local.F12RawEvidenceStore
import java.io.IOException
import java.time.Clock
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

private const val F12_MAX_HTTP_BODY_BYTES = 48L * 1024 * 1024
private const val F12_MAX_MANIFEST_BYTES = 64 * 1024

interface F12EvidenceApi {
    @POST("api/v1/profiles/{profileId}/f12-evidence/v1")
    suspend fun postEvidence(
        @Path("profileId") profileId: String,
        @Header("Authorization") authorization: String,
        @Body body: MultipartBody,
    ): Response<F12EvidenceReceiptDto>
}

data class F12EvidenceReceiptDto(
    @SerializedName("receipt_id") val receiptId: String,
    @SerializedName("capture_id") val captureId: String,
    val status: String,
    @SerializedName("evidence_state") val evidenceState: String,
    @SerializedName("local_date") val localDate: String,
    val revision: Int,
    @SerializedName("content_sha256") val contentSha256: String,
    @SerializedName("pair_count") val pairCount: Int,
    @SerializedName("total_bytes") val totalBytes: Long,
    @SerializedName("received_at") val receivedAt: String,
    @SerializedName("expires_at") val expiresAt: String?,
) {
    fun toDomain(): F12EvidenceReceipt = F12EvidenceReceipt(
        receiptId = receiptId,
        captureId = captureId,
        status = status,
        evidenceState = F12EvidenceState.valueOf(evidenceState),
        localDate = localDate,
        revision = revision,
        contentSha256 = contentSha256,
        pairCount = pairCount,
        totalBytes = totalBytes,
        receivedAt = receivedAt,
        expiresAt = expiresAt,
    )
}

internal data class F12EvidencePairManifestDto(
    @SerializedName("collector_run_id") val collectorRunId: String,
    @SerializedName("summary_sha256") val summarySha256: String,
    @SerializedName("events_sha256") val eventsSha256: String,
)

internal data class F12EvidenceManifestDto(
    @SerializedName("envelope_version") val envelopeVersion: Int,
    @SerializedName("capture_id") val captureId: String,
    @SerializedName("adapter_version") val adapterVersion: String,
    @SerializedName("local_date") val localDate: String,
    @SerializedName("time_zone") val timeZone: String,
    val revision: Int,
    @SerializedName("created_at_epoch_millis") val createdAtEpochMillis: Long,
    @SerializedName("content_sha256") val contentSha256: String,
    @SerializedName("evidence_state") val evidenceState: String,
    @SerializedName("unavailable_reason") val unavailableReason: String?,
    val pairs: List<F12EvidencePairManifestDto>,
)

class F12EvidenceMultipartFactory(
    private val gson: Gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create(),
    private val maxHttpBodyBytes: Long = F12_MAX_HTTP_BODY_BYTES,
) {
    @Suppress("DEPRECATION")
    fun create(record: F12RawEvidenceRecord): MultipartBody {
        val manifest = F12EvidenceManifestDto(
            envelopeVersion = record.envelopeVersion,
            captureId = record.captureId,
            adapterVersion = record.adapterVersion,
            localDate = record.localDate,
            timeZone = record.timezoneId,
            revision = record.revision,
            createdAtEpochMillis = record.createdAtEpochMillis,
            contentSha256 = record.contentSha256,
            evidenceState = record.evidenceState.name,
            unavailableReason = record.unavailableReason,
            pairs = record.exportPairs.map {
                F12EvidencePairManifestDto(it.collectorRunId, it.summarySha256, it.eventsSha256)
            },
        )
        val manifestBytes = gson.toJson(manifest).toByteArray(Charsets.UTF_8)
        require(manifestBytes.size <= F12_MAX_MANIFEST_BYTES) { "F12 manifest exceeds 64 KiB" }
        val json = requireNotNull(MediaType.parse("application/json; charset=utf-8"))
        val ndjson = requireNotNull(MediaType.parse("application/x-ndjson"))
        val manifestHeaders = Headers.Builder()
            .add("Content-Disposition", "form-data; name=\"manifest\"")
            .build()
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addPart(manifestHeaders, RequestBody.create(json, manifestBytes))
        record.exportPairs.forEachIndexed { index, pair ->
            builder.addFormDataPart(
                "summary",
                "summary_${index.toString().padStart(4, '0')}.json",
                RequestBody.create(json, pair.summaryBytes),
            )
            builder.addFormDataPart(
                "events",
                "events_${index.toString().padStart(4, '0')}.jsonl",
                RequestBody.create(ndjson, pair.eventsBytes),
            )
        }
        val body = builder.build()
        val contentLength = body.contentLength()
        require(contentLength >= 0) { "F12 multipart must have a known Content-Length" }
        require(contentLength <= maxHttpBodyBytes) { "F12 multipart exceeds 48 MiB" }
        return body
    }
}

sealed interface F12EvidenceDeliveryResult {
    data class Received(val httpCode: Int, val receipt: F12EvidenceReceipt) : F12EvidenceDeliveryResult
    data object AuthenticationRequired : F12EvidenceDeliveryResult
    data object Forbidden : F12EvidenceDeliveryResult
    data class Conflict(val errorCode: String?) : F12EvidenceDeliveryResult
    data class Retryable(val httpCode: Int? = null, val errorCode: String? = null) : F12EvidenceDeliveryResult
    data class PermanentFailure(val httpCode: Int, val errorCode: String? = null) : F12EvidenceDeliveryResult
}

fun interface F12EvidenceDeliverer {
    suspend fun deliver(record: F12RawEvidenceRecord): F12EvidenceDeliveryResult
}

class F12EvidenceRepository(
    private val api: F12EvidenceApi,
    private val authorization: RequestAuthorization,
    private val multipartFactory: F12EvidenceMultipartFactory = F12EvidenceMultipartFactory(),
    private val gson: Gson = Gson(),
) : F12EvidenceDeliverer {
    init { require(authorization.principal == RequestPrincipal.DEVICE) }

    override suspend fun deliver(record: F12RawEvidenceRecord): F12EvidenceDeliveryResult = try {
        val body = multipartFactory.create(record)
        authorization.execute { bearer ->
            val response = api.postEvidence(record.profileId, bearer, body)
            val receipt = response.body()?.let { runCatching { it.toDomain() }.getOrNull() }
            if (response.code() in setOf(200, 201) && receipt != null) {
                F12EvidenceDeliveryResult.Received(response.code(), receipt)
            } else {
                val errorCode = response.errorBody()?.charStream()?.use { reader ->
                    runCatching { gson.fromJson(reader, F12ErrorEnvelope::class.java).detail?.code }.getOrNull()
                }
                when (response.code()) {
                    401 -> F12EvidenceDeliveryResult.AuthenticationRequired
                    403 -> F12EvidenceDeliveryResult.Forbidden
                    409 -> F12EvidenceDeliveryResult.Conflict(errorCode)
                    408, 429, in 500..599 -> F12EvidenceDeliveryResult.Retryable(response.code(), errorCode)
                    else -> F12EvidenceDeliveryResult.PermanentFailure(response.code(), errorCode)
                }
            }
        }
    } catch (_: com.juan.habitos.domain.repository.DeviceAuthenticationRequiredException) {
        F12EvidenceDeliveryResult.AuthenticationRequired
    } catch (_: IOException) {
        F12EvidenceDeliveryResult.Retryable()
    } catch (exception: Exception) {
        if (generateSequence(exception as Throwable?) { it.cause }.any { it is IOException }) {
            F12EvidenceDeliveryResult.Retryable()
        } else throw exception
    }

    private data class F12ErrorEnvelope(val detail: F12ErrorDetail?)
    private data class F12ErrorDetail(val code: String?)
}

enum class F12EvidenceTransportStatus {
    DISABLED,
    DELIVERED,
    RETRYABLE_FAILURE,
    AUTHENTICATION_REQUIRED,
    FORBIDDEN,
    CONFLICT,
    PERMANENT_FAILURE,
    EVIDENCE_NOT_FOUND,
}

data class F12EvidenceTransportOutcome(
    val status: F12EvidenceTransportStatus,
    val captureId: String? = null,
    val receipt: F12EvidenceReceipt? = null,
    val errorCode: String? = null,
)

class F12EvidenceTransportCoordinator(
    private val enabled: Boolean,
    private val store: F12RawEvidenceStore,
    private val repository: F12EvidenceDeliverer,
    private val onAuthenticationRequired: suspend () -> Unit = {},
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun deliver(captureId: String): F12EvidenceTransportOutcome {
        if (!enabled) return F12EvidenceTransportOutcome(F12EvidenceTransportStatus.DISABLED, captureId)
        val record = store.read(captureId)
            ?: return F12EvidenceTransportOutcome(F12EvidenceTransportStatus.EVIDENCE_NOT_FOUND, captureId)
        if (record.delivery.state == F12EvidenceDeliveryState.DELIVERED && record.delivery.receipt != null) {
            return F12EvidenceTransportOutcome(F12EvidenceTransportStatus.DELIVERED, captureId, record.delivery.receipt)
        }
        if (record.delivery.state == F12EvidenceDeliveryState.CONFLICT) {
            return F12EvidenceTransportOutcome(F12EvidenceTransportStatus.CONFLICT, captureId, errorCode = record.delivery.lastErrorCode)
        }
        if (record.delivery.state == F12EvidenceDeliveryState.PERMANENT_FAILURE) {
            return F12EvidenceTransportOutcome(F12EvidenceTransportStatus.PERMANENT_FAILURE, captureId, errorCode = record.delivery.lastErrorCode)
        }
        store.updateDelivery(captureId) { current ->
            current.copy(
                state = F12EvidenceDeliveryState.RETRY_PENDING,
                attemptCount = current.attemptCount + 1,
                lastAttemptAtEpochMillis = clock.millis(),
                lastHttpCode = null,
                lastErrorCode = null,
            )
        }
        return when (val result = repository.deliver(record)) {
            is F12EvidenceDeliveryResult.Received -> handleReceipt(record, result)
            F12EvidenceDeliveryResult.AuthenticationRequired -> {
                store.updateDelivery(captureId) {
                    it.copy(state = F12EvidenceDeliveryState.AUTHENTICATION_BLOCKED, lastHttpCode = 401, lastErrorCode = "authentication_required")
                }
                onAuthenticationRequired()
                F12EvidenceTransportOutcome(F12EvidenceTransportStatus.AUTHENTICATION_REQUIRED, captureId, errorCode = "authentication_required")
            }
            F12EvidenceDeliveryResult.Forbidden -> {
                store.updateDelivery(captureId) {
                    it.copy(state = F12EvidenceDeliveryState.AUTHENTICATION_BLOCKED, lastHttpCode = 403, lastErrorCode = "forbidden")
                }
                F12EvidenceTransportOutcome(F12EvidenceTransportStatus.FORBIDDEN, captureId, errorCode = "forbidden")
            }
            is F12EvidenceDeliveryResult.Conflict -> {
                store.updateDelivery(captureId) {
                    it.copy(state = F12EvidenceDeliveryState.CONFLICT, lastHttpCode = 409, lastErrorCode = result.errorCode)
                }
                F12EvidenceTransportOutcome(F12EvidenceTransportStatus.CONFLICT, captureId, errorCode = result.errorCode)
            }
            is F12EvidenceDeliveryResult.Retryable -> {
                store.updateDelivery(captureId) {
                    it.copy(state = F12EvidenceDeliveryState.RETRY_PENDING, lastHttpCode = result.httpCode, lastErrorCode = result.errorCode)
                }
                F12EvidenceTransportOutcome(F12EvidenceTransportStatus.RETRYABLE_FAILURE, captureId, errorCode = result.errorCode)
            }
            is F12EvidenceDeliveryResult.PermanentFailure -> {
                store.updateDelivery(captureId) {
                    it.copy(state = F12EvidenceDeliveryState.PERMANENT_FAILURE, lastHttpCode = result.httpCode, lastErrorCode = result.errorCode)
                }
                F12EvidenceTransportOutcome(F12EvidenceTransportStatus.PERMANENT_FAILURE, captureId, errorCode = result.errorCode)
            }
        }
    }

    private suspend fun handleReceipt(
        record: F12RawEvidenceRecord,
        result: F12EvidenceDeliveryResult.Received,
    ): F12EvidenceTransportOutcome {
        val receipt = result.receipt
        val sourceBytes = record.exportPairs.sumOf { it.summaryBytes.size.toLong() + it.eventsBytes.size }
        val valid = receipt.status == "RECEIVED" &&
            receipt.captureId == record.captureId &&
            receipt.evidenceState == record.evidenceState &&
            receipt.localDate == record.localDate &&
            receipt.revision == record.revision &&
            receipt.contentSha256 == record.contentSha256 &&
            receipt.pairCount == record.exportPairs.size &&
            receipt.totalBytes == sourceBytes &&
            receipt.receiptId.isNotBlank() && receipt.receivedAt.isNotBlank()
        if (!valid) {
            store.updateDelivery(record.captureId) {
                it.copy(
                    state = F12EvidenceDeliveryState.PERMANENT_FAILURE,
                    lastHttpCode = result.httpCode,
                    lastErrorCode = "invalid_receipt",
                )
            }
            return F12EvidenceTransportOutcome(
                F12EvidenceTransportStatus.PERMANENT_FAILURE,
                record.captureId,
                errorCode = "invalid_receipt",
            )
        }
        store.updateDelivery(record.captureId) {
            it.copy(
                state = F12EvidenceDeliveryState.DELIVERED,
                lastHttpCode = result.httpCode,
                lastErrorCode = null,
                receipt = receipt,
            )
        }
        return F12EvidenceTransportOutcome(F12EvidenceTransportStatus.DELIVERED, record.captureId, receipt)
    }
}
