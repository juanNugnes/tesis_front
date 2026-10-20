package com.juan.habitos.data.mock

import com.juan.habitos.data.remote.*
import com.juan.habitos.ui.adult.*
import com.juan.habitos.ui.state.AdultMessagesUiState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AdultMessagesDemoTest {
    @Test fun `examples have stable profile scoped identities and updates without duplicates`() = runTest {
        val repository = createAdultMessagesDemoRepository()
        val first = repository.history("child-one")
        assertEquals(first, repository.history("child-one"))
        assertEquals(4, first.versions.size)
        assertEquals(2, first.latestCards().size)
        assertEquals(4, first.versions.map { it.card.cardId to it.version }.distinct().size)
        val second = repository.history("child-two")
        assertTrue(second.versions.all { it.card.profileId == "child-two" })
        assertTrue(first.versions.map { it.card.cardId }.intersect(second.versions.map { it.card.cardId }.toSet()).isEmpty())
    }

    @Test fun `opening and follow up share identity and decrease belongs to older archived episode`() = runTest {
        val history = createAdultMessagesDemoRepository().history("child")
        val state = AdultMessagesUiState(profileId = "child", history = history, demo = true)
        val latest = state.current.single()
        assertEquals("INCONCLUSIVE", latest.card.status)
        assertEquals("Seguimiento del tiempo de uso", latest.card.title)
        assertEquals(2, latest.version)
        val opening = history.versions.single { it.card.cardId == latest.card.cardId && it.version == 1 }
        assertEquals("Más uso registrado en este teléfono", opening.card.title)
        assertEquals(40.0, opening.card.statistics!!.delta, 0.0)
        assertEquals(opening.card.openingReferenceStart, latest.card.openingReferenceStart)
        val archived = state.archived.single()
        assertEquals("DECREASE", archived.card.direction)
        assertEquals("EXPIRED", archived.card.status)
        assertEquals("Menos uso registrado en este teléfono", archived.card.title)
        assertTrue(LocalDate.parse(archived.card.evaluationDate) < LocalDate.parse(opening.card.openingReferenceStart))
    }

    @Test fun `demo alert targets exact opening version and stays unique across repeated visits`() = runTest {
        val history = createAdultMessagesDemoRepository().history("child")
        val state = AdultMessagesUiState(profileId = "child", history = history, demo = true)
        val alert = adultMessageDemoAlert(state)!!
        assertEquals("Cambio en el tiempo de uso", alert.alert.title)
        assertEquals(ADULT_MESSAGES_DEMO_LABEL, alert.alert.type)
        val target = history.versions.single { it.card.cardId == alert.cardId && it.version == alert.version }
        assertEquals("Más uso registrado en este teléfono", target.card.title)
        assertEquals(1, target.version)
        var selectedMessage: Pair<String, Int>? = null
        var selectedTab: Int? = null
        openAdultMessageDemoAlert(alert,
            selectMessage = { id, version -> selectedMessage = id to version },
            selectTab = { tab ->
                assertEquals(alert.cardId to alert.version, selectedMessage)
                selectedTab = tab
            },
        )
        assertEquals(3, selectedTab)
        repeat(3) { assertEquals(alert, adultMessageDemoAlert(state)) }
        assertNull(adultMessageDemoAlert(state.copy(demo = false)))
        assertNull(adultMessageDemoAlert(state.copy(loading = true)))
        assertNull(adultMessageDemoAlert(state.copy(profileId = null)))
        assertNull(adultMessageDemoAlert(state.copy(error = "offline")))
    }

    @Test fun `mock repository never calls remote client or changes its history`() = runTest {
        var apiCalls = 0
        val original = AdultMessageHistory(emptyList())
        val api = object : AdultMessagesApi {
            override suspend fun history(profileId: String, authorization: String): AdultMessageHistory {
                apiCalls++
                return original
            }
        }
        val authorization = object : RequestAuthorization {
            override val principal = RequestPrincipal.ADULT
            override suspend fun <T> execute(request: suspend (String) -> T): T = error("mock must not authenticate remotely")
        }
        val repository = adultMessagesRepositoryForMode(false, api, authorization,
            adultMessagesDemo = true, isDebug = true, demoMessages = ::createAdultMessagesDemoRepository)
        repeat(3) { assertEquals(4, repository.history("child").versions.size) }
        assertEquals(0, apiCalls)
        assertTrue(original.versions.isEmpty())
    }
}
