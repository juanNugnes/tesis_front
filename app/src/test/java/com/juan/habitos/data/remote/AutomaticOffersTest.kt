package com.juan.habitos.data.remote

import com.juan.habitos.data.device.*
import com.juan.habitos.data.local.AutomaticOfferOpportunityStore
import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.ActivitySuggestionRepository
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import okhttp3.MediaType
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response

class AutomaticOffersTest {
    @Test fun `definitive response is removed while retryable response remains queued`() = runTest {
        val definitiveStore = Store(evidence())
        val definitive = api { name, _ ->
            if (name != "postAutomaticOpportunity") error(name)
            throw httpError(409, "definitive_error")
        }
        AutomaticOfferSyncCoordinator(definitive, DeviceAuth(), definitiveStore).synchronize()
        assertTrue(definitiveStore.pending().isEmpty())

        val retryStore = Store(evidence())
        val retryable = api { name, _ ->
            if (name != "postAutomaticOpportunity") error(name)
            throw httpError(409, "retryable_error")
        }
        try { AutomaticOfferSyncCoordinator(retryable, DeviceAuth(), retryStore).synchronize() }
        catch (_: HttpException) { }
        assertEquals(1, retryStore.pending().size)
    }
    @Test fun `retry preserves request id exact payload and Device bearer`() = runTest {
        val store = Store(evidence())
        val calls = mutableListOf<Array<out Any?>>()
        var fail = true
        val api = api { name, args ->
            if (name == "postAutomaticOpportunity") {
                calls += args
                if (fail) throw IllegalStateException("offline")
                AutomaticOpportunityResponseDto("discarded", (args[2] as AutomaticOpportunityRequest).requestId, "game_state_missing")
            } else error(name)
        }
        val coordinator = AutomaticOfferSyncCoordinator(api, DeviceAuth(), store, { ZoneId.of("America/Argentina/Buenos_Aires") })

        try {
            coordinator.synchronize()
            fail("Expected the transient network error")
        } catch (_: IllegalStateException) { }
        fail = false
        coordinator.synchronize()

        val first = calls[0][2] as AutomaticOpportunityRequest
        val second = calls[1][2] as AutomaticOpportunityRequest
        assertEquals(first, second)
        assertEquals("Bearer device", calls[0][1])
        assertEquals("America/Argentina/Buenos_Aires", first.timezone)
        assertTrue(first.observedAt.endsWith("-03:00"))
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `offer response is persisted without inventing missing presentation fields`() = runTest {
        val store = Store(evidence(gameActive = true))
        val api = api { name, args ->
            if (name != "postAutomaticOpportunity") error(name)
            AutomaticOpportunityResponseDto(
                "offer_available", (args[2] as AutomaticOpportunityRequest).requestId,
                coordinatorOfferId = "coordinator-1", activityOfferId = "activity-offer-1", channel = "overlay",
                offer = CoordinatorOfferDto("coordinator-1", listOf("activity-1"), AutomaticOfferSelectionDto(
                    listOf(AutomaticOfferActivityDto("activity-1", "Respirar")),
                )),
            )
        }
        AutomaticOfferSyncCoordinator(api, DeviceAuth(), store).synchronize()

        val offer = requireNotNull(store.pendingOffer())
        assertEquals("Respirar", offer.activities.single().title)
        assertNull(offer.activities.single().description)
        assertEquals("overlay", offer.channel)
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `delivered is sent only on confirmation and duplicate confirmation is ignored`() = runTest {
        val store = Store().apply { savePendingOffer(offerFixture()) }
        val events = mutableListOf<AutomaticOfferEventRequest>()
        val api = api { name, args ->
            if (name != "postAutomaticOfferEvent") error(name)
            events += args[2] as AutomaticOfferEventRequest
            AutomaticOfferEventReceiptDto("recorded")
        }
        val repository = RemoteAutomaticOfferRepository(api, DeviceAuth(), store, Activities())
        assertTrue(events.isEmpty())

        repository.confirmDelivered("overlay")
        repository.confirmDelivered("overlay")

        assertEquals(1, events.size)
        assertEquals("delivered", events.single().event)
        assertEquals("overlay", events.single().channel)
        assertTrue(requireNotNull(store.pendingOffer()).delivered)
    }

    @Test fun `selection is registered before selected event and clears pending offer only after both`() = runTest {
        val store = Store().apply { savePendingOffer(offerFixture().copy(presentedChannel = "overlay", delivered = true)) }
        val order = mutableListOf<String>()
        val activities = Activities { order += "selection" }
        val api = api { name, args ->
            if (name != "postAutomaticOfferEvent") error(name)
            order += "event"
            val request = args[2] as AutomaticOfferEventRequest
            assertEquals("selected", request.event)
            assertEquals("activity-1", request.selectedActivityId)
            AutomaticOfferEventReceiptDto("recorded")
        }
        val repository = RemoteAutomaticOfferRepository(api, DeviceAuth(), store, activities)
        repository.registerSelection(ActivitySelection("selection-1", "activity-offer-1", "activity-1", 1L))

        assertEquals(listOf("selection", "event"), order)
        assertNull(store.pendingOffer())
    }

    private fun evidence(gameActive: Boolean? = null) = InteractiveOpportunityEvidence(
        profileId = "profile", deviceId = "device", seriesId = "series", intervalId = "interval",
        intervalStartEpochMillis = 1_000_000L, evaluatedAtEpochMillis = 4_000_000L,
        continuousInteractiveMillis = 3_000_000L, interactiveState = "INTERACTIVE", gameActive = gameActive,
        state = InteractiveOpportunityState.ELIGIBLE,
        quality = InteractiveOpportunityQuality("run", 0, 4_000_000, 4_000_000, true, false, null, 1, false, null),
    )
    private fun offerFixture() = PendingAutomaticOffer(
        "profile", "request", "coordinator-1", "activity-offer-1", "overlay",
        listOf(AutomaticOfferActivity("activity-1", "Respirar", null, null)),
    )

    private class Store(vararg initial: InteractiveOpportunityEvidence) : AutomaticOfferOpportunityStore {
        private val evidence = initial.toMutableList()
        private var offer: PendingAutomaticOffer? = null
        override fun recordIfAbsent(evidence: InteractiveOpportunityEvidence) = if (this.evidence.any { it.intervalId == evidence.intervalId }) false else true.also { this.evidence += evidence }
        override fun pending() = evidence.toList()
        override fun complete(intervalId: String) { evidence.removeAll { it.intervalId == intervalId } }
        override fun savePendingOffer(offer: PendingAutomaticOffer) { this.offer = offer }
        override fun pendingOffer() = offer
        override fun updatePendingOffer(transform: (PendingAutomaticOffer?) -> PendingAutomaticOffer?) { offer = transform(offer) }
    }
    private class DeviceAuth : RequestAuthorization {
        override val principal = RequestPrincipal.DEVICE
        override suspend fun <T> execute(request: suspend (String) -> T) = request("Bearer device")
    }
    private class Activities(private val onSelection: () -> Unit = {}) : ActivitySuggestionRepository {
        override suspend fun suggestions() = ActivitySuggestionOffer(null, emptyList())
        override suspend fun registerSelection(selection: ActivitySelection) = onSelection()
        override suspend fun selectionCount(startEpochMillis: Long, endEpochMillis: Long) = 0
    }
    @Suppress("UNCHECKED_CAST")
    private fun api(handler: (String, Array<out Any?>) -> Any?): HabitsApi = Proxy.newProxyInstance(
        HabitsApi::class.java.classLoader, arrayOf(HabitsApi::class.java),
    ) { _, method, args -> handler(method.name, args.orEmpty()) } as HabitsApi

    private fun httpError(code: Int, outcome: String) = HttpException(Response.error<Any>(
        code,
        ResponseBody.create(MediaType.parse("application/json"), "{\"detail\":{\"outcome\":\"$outcome\",\"code\":\"TEST\"}}"),
    ))
}
