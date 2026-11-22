package com.juan.habitos.ui.state

import com.juan.habitos.data.remote.AdultHistoricalEvaluationDto
import com.juan.habitos.data.remote.AdultHistoricalEvaluationsRepository
import com.juan.habitos.data.remote.AdultHistoricalSubmissionDto
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.repository.AdultAuthenticationRequiredException
import com.juan.habitos.domain.repository.AdultAuthorizationException
import com.juan.habitos.domain.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AdultHistoricalEvaluationsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    private class Profiles : ProfileRepository {
        override val profile = MutableStateFlow<Profile?>(Profile("Child", "fox", 9, id = "profile-one"))
        override val onboardingComplete = MutableStateFlow(true)
        override suspend fun save(profile: Profile) { this.profile.value = profile }
        override suspend fun completeOnboarding() = Unit
        override suspend fun create(profile: Profile) = profile
        override suspend fun refresh() = profile.value
        override suspend fun update(profile: Profile) = profile
        override suspend fun refreshSettings() = profile.value
        override suspend fun updateSettings(profile: Profile) = profile
    }

    @Test fun `presentation differentiates all supported C1 states without technical details`() {
        fun item(
            status: String,
            outcome: String? = null,
            reason: String? = null,
            retryable: Boolean = false,
        ) = historicalEvaluationPresentation(
            AdultHistoricalSubmissionDto(
                localDate = "2026-11-20",
                status = status,
                analyticsEvaluation = outcome?.let { AdultHistoricalEvaluationDto(it, reason) },
                retryable = retryable,
            ),
        )

        assertEquals("Historial insuficiente", item("PROCESSED", "ABSTAIN", "INSUFFICIENT_HISTORY").title)
        assertEquals("Comparación no realizada", item("PROCESSED", "ABSTAIN", "OTHER_REASON").title)
        assertEquals("Sin cambios detectados", item("PROCESSED", "NO_CHANGE").title)
        val change = item("PROCESSED", "CHANGE")
        assertEquals("Cambio detectado", change.title)
        assertTrue(change.description.contains("no es un riesgo"))
        assertEquals("Evaluación en proceso", item("PENDING").title)
        assertEquals("Evaluación en proceso", item("SENDING").title)
        assertEquals("No se pudo completar la evaluación", item("TECHNICAL_ERROR", retryable = true).title)
        assertEquals("No se pudo completar la evaluación", item("CONFLICT").title)
        assertEquals("Resultado no disponible", item("PROCESSED").title)
        assertEquals("Resultado no disponible", item("FUTURE_STATUS").title)
        assertEquals("20/11/2026", change.date)
        assertFalse(item("PROCESSED", "ABSTAIN", "OTHER_REASON").description.contains("OTHER_REASON"))
    }

    @Test fun `empty query is represented as loaded and refresh requests the same authorized profile`() = runTest(dispatcher) {
        var calls = 0
        val requestedProfiles = mutableListOf<String>()
        val viewModel = AdultHistoricalEvaluationsViewModel(
            Profiles(),
            AdultHistoricalEvaluationsRepository { profileId ->
                calls++
                requestedProfiles += profileId
                emptyList()
            },
        )

        advanceUntilIdle()
        assertTrue(viewModel.state.value.loaded)
        assertTrue(viewModel.state.value.items.isEmpty())

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2, calls)
        assertEquals(listOf("profile-one", "profile-one"), requestedProfiles)
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.refreshing)
    }

    @Test fun `authentication and profile permission failures remain distinct`() = runTest(dispatcher) {
        val authentication = AdultHistoricalEvaluationsViewModel(
            Profiles(),
            AdultHistoricalEvaluationsRepository { throw AdultAuthenticationRequiredException() },
        )
        advanceUntilIdle()
        assertTrue(authentication.state.value.authenticationRequired)
        assertFalse(authentication.state.value.forbidden)
        assertNotNull(authentication.state.value.error)

        val forbidden = AdultHistoricalEvaluationsViewModel(
            Profiles(),
            AdultHistoricalEvaluationsRepository { throw AdultAuthorizationException() },
        )
        advanceUntilIdle()
        assertFalse(forbidden.state.value.authenticationRequired)
        assertTrue(forbidden.state.value.forbidden)
        assertTrue(forbidden.state.value.error!!.contains("no tiene permiso"))
    }

    @Test fun `profile change clears results and queries only the selected profile`() = runTest(dispatcher) {
        val profiles = Profiles()
        val viewModel = AdultHistoricalEvaluationsViewModel(
            profiles,
            AdultHistoricalEvaluationsRepository { profileId ->
                listOf(
                    AdultHistoricalSubmissionDto(
                        localDate = if (profileId == "profile-one") "2026-11-20" else "2026-11-21",
                        status = "PROCESSED",
                        analyticsEvaluation = AdultHistoricalEvaluationDto("NO_CHANGE"),
                    ),
                )
            },
        )
        advanceUntilIdle()
        assertEquals("20/11/2026", viewModel.state.value.items.single().date)

        profiles.profile.value = Profile("Other", "owl", 10, id = "profile-two")
        advanceUntilIdle()

        assertEquals("profile-two", viewModel.state.value.profileId)
        assertEquals("21/11/2026", viewModel.state.value.items.single().date)
    }

    @Test fun `connectivity failure on refresh preserves the last real result`() = runTest(dispatcher) {
        var calls = 0
        val viewModel = AdultHistoricalEvaluationsViewModel(
            Profiles(),
            AdultHistoricalEvaluationsRepository {
                if (++calls > 1) throw IOException("offline")
                listOf(
                    AdultHistoricalSubmissionDto(
                        localDate = "2026-11-20",
                        status = "PROCESSED",
                        analyticsEvaluation = AdultHistoricalEvaluationDto("NO_CHANGE"),
                    ),
                )
            },
        )
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals("Sin cambios detectados", viewModel.state.value.items.single().title)
        assertTrue(viewModel.state.value.loaded)
        assertTrue(viewModel.state.value.error!!.contains("conexión"))
        assertFalse(viewModel.state.value.authenticationRequired)
        assertFalse(viewModel.state.value.forbidden)
    }
}
