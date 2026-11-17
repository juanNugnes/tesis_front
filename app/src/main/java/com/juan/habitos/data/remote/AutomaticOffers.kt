package com.juan.habitos.data.remote

import com.google.gson.annotations.SerializedName
import com.google.gson.JsonParser
import com.juan.habitos.data.device.InteractiveOpportunityEvidence
import com.juan.habitos.data.local.AutomaticOfferOpportunityStore
import com.juan.habitos.domain.model.AutomaticOfferActivity
import com.juan.habitos.domain.model.PendingAutomaticOffer
import com.juan.habitos.domain.model.AutomaticOfferSettings
import com.juan.habitos.domain.model.ActivitySelection
import com.juan.habitos.domain.repository.ActivitySuggestionRepository
import com.juan.habitos.domain.repository.AutomaticOfferRepository
import com.juan.habitos.domain.repository.AutomaticOfferSettingsRepository
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import retrofit2.HttpException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AutomaticOpportunityRequest(
    @SerializedName("request_id") val requestId: String,
    @SerializedName("series_id") val seriesId: String,
    @SerializedName("interval_id") val intervalId: String,
    @SerializedName("observed_at") val observedAt: String,
    @SerializedName("interval_started_at") val intervalStartedAt: String,
    val timezone: String,
    @SerializedName("capture_verified") val captureVerified: Boolean,
    @SerializedName("screen_interactive") val screenInteractive: Boolean,
    @SerializedName("game_active") val gameActive: Boolean?,
)

data class AutomaticOfferActivityDto(
    @SerializedName("activity_id") val activityId: String,
    val title: String,
    val description: String,
    val category: String,
    val materials: List<String>? = null,
    val steps: List<String>? = null,
)
data class AutomaticOfferSelectionDto(
    val activities: List<AutomaticOfferActivityDto>,
    // Catalog selector state is metadata, never evidence of a child's selection.
    val status: String? = null,
)
data class CoordinatorOfferDto(
    @SerializedName("offer_id") val offerId: String,
    @SerializedName("activities_offered") val activitiesOffered: List<String> = emptyList(),
    val selection: AutomaticOfferSelectionDto? = null,
)
data class AutomaticOpportunityResponseDto(
    val outcome: String,
    @SerializedName("request_id") val requestId: String,
    val reason: String? = null,
    val replayed: Boolean = false,
    @SerializedName("coordinator_offer_id") val coordinatorOfferId: String? = null,
    @SerializedName("activity_offer_id") val activityOfferId: String? = null,
    val channel: String? = null,
    val offer: CoordinatorOfferDto? = null,
)

data class AutomaticOfferEventRequest(
    @SerializedName("request_id") val requestId: String,
    @SerializedName("profile_id") val profileId: String,
    @SerializedName("offer_id") val offerId: String,
    val event: String,
    @SerializedName("occurred_at") val occurredAt: String,
    val now: String,
    val channel: String? = null,
    @SerializedName("selected_activity_id") val selectedActivityId: String? = null,
)
data class AutomaticOfferEventReceiptDto(val status: String, val replayed: Boolean = false)
data class AutomaticOfferSettingsRequest(
    @SerializedName("automatic_enabled") val automaticEnabled: Boolean,
    @SerializedName("shared_phone") val sharedPhone: Boolean,
)
data class AutomaticOfferSettingsDto(
    @SerializedName("profile_id") val profileId: String,
    @SerializedName("automatic_enabled") val automaticEnabled: Boolean,
    @SerializedName("shared_phone") val sharedPhone: Boolean,
    @SerializedName("updated_at") val updatedAt: String,
)

class AutomaticOfferSyncCoordinator(
    private val api: HabitsApi,
    private val authorization: RequestAuthorization,
    private val store: AutomaticOfferOpportunityStore,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val onOfferAvailable: suspend () -> Unit = {},
) {
    init { require(authorization.principal == RequestPrincipal.DEVICE) }

    suspend fun synchronize() {
        if (store.pendingOffer() != null) {
            onOfferAvailable()
            return
        }
        for (evidence in store.pending()) {
            synchronize(evidence)
            if (store.pendingOffer() != null) break
        }
    }

    private suspend fun synchronize(evidence: InteractiveOpportunityEvidence) {
        val intervalId = evidence.intervalId ?: return
        val startedAt = evidence.intervalStartEpochMillis ?: return
        val zone = zoneId()
        val request = AutomaticOpportunityRequest(
            requestId = stableRequestId(intervalId), seriesId = evidence.seriesId, intervalId = intervalId,
            observedAt = Instant.ofEpochMilli(evidence.evaluatedAtEpochMillis).atZone(zone).toOffsetDateTime().toString(),
            intervalStartedAt = Instant.ofEpochMilli(startedAt).atZone(zone).toOffsetDateTime().toString(),
            timezone = zone.id, captureVerified = evidence.quality.usagePermissionAvailable &&
                !evidence.quality.queryReturnedNull && evidence.quality.errorCode == null,
            screenInteractive = evidence.interactiveState == "INTERACTIVE", gameActive = evidence.gameActive,
        )
        try {
            val response = authorization.execute { header ->
                api.postAutomaticOpportunity(evidence.profileId, header, request)
            }
            when (response.outcome) {
                "discarded" -> store.complete(intervalId)
                "offer_available" -> {
                    val coordinatorId = requireNotNull(response.coordinatorOfferId)
                    val activityOfferId = requireNotNull(response.activityOfferId)
                    val channel = requireNotNull(response.channel)
                    val activities = requireNotNull(response.offer?.selection).activities
                    require(activities.size in 1..3)
                    store.savePendingOffer(PendingAutomaticOffer(
                        evidence.profileId, response.requestId, coordinatorId, activityOfferId, channel,
                        activities.map { activity -> AutomaticOfferActivity(
                            activity.idOrThrow(), activity.title, activity.description, activity.category,
                            activity.materials, activity.steps,
                        ) },
                    ))
                    onOfferAvailable()
                    store.complete(intervalId)
                }
                else -> error("Unsupported automatic opportunity outcome")
            }
        } catch (error: HttpException) {
            val outcome = runCatching {
                JsonParser.parseString(error.response()?.errorBody()?.string()).asJsonObject
                    .getAsJsonObject("detail")?.get("outcome")?.asString
            }.getOrNull()
            if (outcome == "definitive_error" ||
                (outcome == null && error.code() in 400..499 && error.code() !in setOf(408, 409, 429))) {
                store.complete(intervalId)
            } else throw error
        }
    }

    private fun stableRequestId(intervalId: String) = "opportunity-${UUID.nameUUIDFromBytes(intervalId.toByteArray(Charsets.UTF_8))}"

    private fun AutomaticOfferActivityDto.idOrThrow(): String = activityId.also { require(it.isNotBlank()) }
}

class RemoteAutomaticOfferRepository(
    private val api: HabitsApi,
    private val deviceAuthorization: RequestAuthorization,
    private val store: AutomaticOfferOpportunityStore,
    private val activities: ActivitySuggestionRepository,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) : AutomaticOfferRepository {
    private val mutablePendingOffer = MutableStateFlow(store.pendingOffer())
    override val pendingOffer = mutablePendingOffer.asStateFlow()

    override fun refreshPendingOffer() { mutablePendingOffer.value = store.pendingOffer() }

    override suspend fun confirmDelivered(channel: String) {
        val offer = requireNotNull(store.pendingOffer())
        if (offer.delivered) return
        require(channel == offer.channel)
        if (offer.presentedChannel == null) {
            store.updatePendingOffer { current -> current?.copy(presentedChannel = channel) }
            refreshPendingOffer()
        }
        sendEvent(offer, "delivered", channel = channel)
        store.updatePendingOffer { current -> current?.copy(delivered = true) }
        refreshPendingOffer()
    }

    override suspend fun registerSelection(selection: ActivitySelection) {
        var offer = requireNotNull(store.pendingOffer())
        require(selection.offerId == offer.activityOfferId)
        if (!offer.delivered) {
            confirmDelivered(requireNotNull(offer.presentedChannel) { "Automatic offer was not presented" })
            offer = requireNotNull(store.pendingOffer())
        }
        activities.registerSelection(selection)
        sendEvent(offer, "selected", selectedActivityId = selection.activityId)
        store.updatePendingOffer { null }
        refreshPendingOffer()
    }

    private suspend fun sendEvent(
        offer: PendingAutomaticOffer,
        event: String,
        channel: String? = null,
        selectedActivityId: String? = null,
    ) {
        val now = Instant.ofEpochMilli(nowEpochMillis()).atZone(zoneId()).toOffsetDateTime().toString()
        val seed = "${offer.coordinatorOfferId}|$event|${selectedActivityId.orEmpty()}"
        val request = AutomaticOfferEventRequest(
            requestId = "$event-${UUID.nameUUIDFromBytes(seed.toByteArray())}",
            profileId = offer.profileId, offerId = offer.coordinatorOfferId, event = event,
            occurredAt = now, now = now, channel = channel, selectedActivityId = selectedActivityId,
        )
        deviceAuthorization.execute { header -> api.postAutomaticOfferEvent(offer.profileId, header, request) }
    }
}

class RemoteAutomaticOfferSettingsRepository(
    private val api: HabitsApi,
    private val adultAuthorization: RequestAuthorization,
) : AutomaticOfferSettingsRepository {
    init { require(adultAuthorization.principal == RequestPrincipal.ADULT) }
    override suspend fun load(profileId: String): AutomaticOfferSettings = adultAuthorization.execute { header ->
        api.getAutomaticOfferSettings(profileId, header)
    }.toDomain()
    override suspend fun save(profileId: String, settings: AutomaticOfferSettings): AutomaticOfferSettings =
        adultAuthorization.execute { header ->
            api.putAutomaticOfferSettings(profileId, header, AutomaticOfferSettingsRequest(settings.automaticEnabled, settings.sharedPhone))
        }.toDomain()
    private fun AutomaticOfferSettingsDto.toDomain() = AutomaticOfferSettings(automaticEnabled, sharedPhone)
}
