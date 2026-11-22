package com.juan.habitos.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

/** Minimal adult-facing projection of a persisted F12/C1 evaluation. */
data class AdultHistoricalEvaluationDto(
    val outcome: String,
    @SerializedName("abstention_reason") val abstentionReason: String? = null,
)

data class AdultHistoricalSubmissionDto(
    @SerializedName("local_date") val localDate: String,
    val status: String,
    @SerializedName("analytics_evaluation") val analyticsEvaluation: AdultHistoricalEvaluationDto? = null,
    val retryable: Boolean = false,
)

interface AdultHistoricalEvaluationsApi {
    @GET("api/v1/profiles/{profileId}/historical-submissions")
    suspend fun list(
        @Path("profileId") profileId: String,
        @Header("Authorization") authorization: String,
    ): List<AdultHistoricalSubmissionDto>
}

fun interface AdultHistoricalEvaluationsRepository {
    suspend fun list(profileId: String): List<AdultHistoricalSubmissionDto>
}

class RemoteAdultHistoricalEvaluationsRepository(
    private val api: AdultHistoricalEvaluationsApi,
    private val authorization: RequestAuthorization,
) : AdultHistoricalEvaluationsRepository {
    init {
        require(authorization.principal == RequestPrincipal.ADULT) {
            "Historical C1 results require adult authorization"
        }
    }

    override suspend fun list(profileId: String): List<AdultHistoricalSubmissionDto> =
        authorization.execute { bearer ->
            api.list(profileId, bearer).also { submissions ->
                submissions.forEach { submission ->
                    require(runCatching { java.time.LocalDate.parse(submission.localDate) }.isSuccess)
                    require(submission.status.isNotBlank())
                }
            }
        }
}
