package com.juan.habitos.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

data class AdultMessageStatistics(
    val recent: Double, val reference: Double, val delta: Double,
    val minimum: Double, val maximum: Double, val above: Double, val below: Double,
    @SerializedName("percent_change") val percentChange: Double?,
    @SerializedName("observed_days_recent") val observedDaysRecent: Int,
    @SerializedName("observed_days_reference") val observedDaysReference: Int,
)

data class AdultMessageCard(
    @SerializedName("card_id") val cardId: String,
    @SerializedName("profile_id") val profileId: String,
    @SerializedName("series_id") val seriesId: String,
    @SerializedName("time_zone") val timeZone: String,
    val direction: String, val status: String,
    val title: String, val text: String, val recommendation: String,
    @SerializedName("evaluation_date") val evaluationDate: String,
    @SerializedName("opening_reference_start") val openingReferenceStart: String,
    @SerializedName("opening_recent_start") val openingRecentStart: String,
    @SerializedName("comparison_recent_start") val comparisonRecentStart: String,
    val statistics: AdultMessageStatistics?,
    val measurement: String,
    @SerializedName("measurement_detail") val measurementDetail: String,
    @SerializedName("configuration_id") val configurationId: String,
    @SerializedName("configuration_fingerprint") val configurationFingerprint: String,
    val scope: String,
    @SerializedName("delivery_status") val deliveryStatus: String,
    @SerializedName("corrected_dates") val correctedDates: List<String>? = null,
    @SerializedName("updated_date") val updatedDate: String? = null,
)

data class AdultMessageVersion(val version: Int, val card: AdultMessageCard)
data class AdultMessageHistory(val versions: List<AdultMessageVersion>)

interface AdultMessagesApi {
    @GET("api/v1/profiles/{profileId}/adult-messages/history")
    suspend fun history(@Path("profileId") profileId: String, @Header("Authorization") authorization: String): AdultMessageHistory
}

fun interface AdultMessagesRepository {
    suspend fun history(profileId: String): AdultMessageHistory
}

internal fun adultMessagesRepositoryForMode(
    demo: Boolean, api: AdultMessagesApi, authorization: RequestAuthorization,
): AdultMessagesRepository = if (demo) {
    AdultMessagesRepository { AdultMessageHistory(emptyList()) }
} else RemoteAdultMessagesRepository(api, authorization)

class RemoteAdultMessagesRepository(
    private val api: AdultMessagesApi,
    private val authorization: RequestAuthorization,
) : AdultMessagesRepository {
    init { require(authorization.principal == RequestPrincipal.ADULT) }
    override suspend fun history(profileId: String): AdultMessageHistory = authorization.execute { bearer ->
        api.history(profileId, bearer).also { history ->
            require(history.versions.all { it.card.profileId == profileId && it.version > 0 })
            require(history.versions.map { it.card.cardId to it.version }.distinct().size == history.versions.size)
        }
    }
}

internal val currentMessageStatuses = setOf("ACTIVE", "INCONCLUSIVE", "INSUFFICIENT_DATA", "CONTEXT_REVIEW")

internal fun AdultMessageHistory.latestCards(): List<AdultMessageVersion> = versions
    .groupBy { it.card.cardId }.values.map { it.maxBy(AdultMessageVersion::version) }
    .sortedByDescending { it.card.updatedDate ?: it.card.evaluationDate }

internal fun messageStatusLabel(status: String): String = when (status) {
    "ACTIVE" -> "En seguimiento"
    "INCONCLUSIVE" -> "Seguimos observando"
    "INSUFFICIENT_DATA" -> "Información insuficiente"
    "CONTEXT_REVIEW" -> "Revisión de contexto"
    "RESOLVED" -> "Seguimiento cerrado"
    "EXPIRED" -> "Seguimiento finalizado"
    "SERIES_CHANGED" -> "Medición anterior"
    "WITHDRAWN" -> "Mensaje retirado"
    "CORRECTED_HISTORY" -> "Historial corregido"
    else -> "Estado no disponible"
}
