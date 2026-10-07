package com.juan.habitos.data.remote

import com.google.gson.Gson
import com.juan.habitos.domain.model.DeviceCredentials
import com.juan.habitos.domain.model.MonitoringConsentStatus
import com.juan.habitos.domain.model.RepresentativeVerificationStatus
import com.juan.habitos.domain.repository.AdultAuthenticationRequiredException
import com.juan.habitos.domain.repository.AdultAuthorizationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

private class ConsentApiHandler : InvocationHandler {
    var verification = verificationDto("APPROVED")
    var current = consentDto("ACTIVE")
    var granted = consentDto("ACTIVE")
    var revoked = consentDto("REVOKED", revokedAt = "2026-10-07T11:00:00Z")
    val calls = mutableListOf<Pair<String, List<Any?>>>()
    val failures = mutableMapOf<String, Exception>()

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        failures[method.name]?.let { throw it }
        calls += method.name to args.orEmpty().toList().dropLast(1)
        return when (method.name) {
            "getRepresentativeVerification" -> verification
            "getCurrentConsent" -> current
            "grantConsent" -> granted
            "revokeConsent" -> revoked
            "toString" -> "ConsentApi"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected API call: ${method.name}")
        }
    }

    fun api(): HabitsApi = Proxy.newProxyInstance(
        HabitsApi::class.java.classLoader,
        arrayOf(HabitsApi::class.java),
        this,
    ) as HabitsApi
}

private fun verificationDto(status: String) = RepresentativeVerificationDto(
    id = "verification-1",
    profileId = "profile-1",
    status = status,
    verifiedAt = if (status == "APPROVED") "2026-10-07T10:00:00Z" else null,
    createdAt = "2026-10-06T10:00:00Z",
    updatedAt = "2026-10-07T10:00:00Z",
)

private fun consentDto(status: String, revokedAt: String? = null) = ConsentDto(
    id = "consent-1",
    profileId = "profile-1",
    status = status,
    privacyNoticeVersion = "1.0",
    purposes = listOf("USAGE_MONITORING"),
    grantedAt = "2026-10-07T10:15:00Z",
    revokedAt = revokedAt,
    createdAt = "2026-10-07T10:15:00Z",
    updatedAt = revokedAt ?: "2026-10-07T10:15:00Z",
)

@Suppress("DEPRECATION")
private fun httpException(code: Int) = HttpException(
    Response.error<Any>(code, ResponseBody.create(MediaType.parse("application/json"), "{}")),
)

class MonitoringConsentRepositoryTest {
    @Test fun `verification and current consent use adult bearer and map exact backend states`() = runTest {
        val handler = ConsentApiHandler()
        val repository = RemoteMonitoringConsentRepository(
            handler.api(),
            AdultRequestAuthorization(TestAdultCredentialStore("adult-jwt")),
        )

        val verification = repository.getRepresentativeVerification("profile-1")
        val consent = repository.getCurrentConsent("profile-1")

        assertEquals(RepresentativeVerificationStatus.APPROVED, verification?.status)
        assertEquals(MonitoringConsentStatus.ACTIVE, consent?.status)
        assertEquals(listOf("profile-1", "Bearer adult-jwt"), handler.calls[0].second)
        assertEquals(listOf("profile-1", "Bearer adult-jwt"), handler.calls[1].second)
    }

    @Test fun `404 means no verification or no active consent without inventing records`() = runTest {
        val handler = ConsentApiHandler()
        val repository = RemoteMonitoringConsentRepository(handler.api(), AdultRequestAuthorization(TestAdultCredentialStore()))
        handler.failures["getRepresentativeVerification"] = httpException(404)
        assertNull(repository.getRepresentativeVerification("profile-1"))
        handler.failures.clear()
        handler.failures["getCurrentConsent"] = httpException(404)
        assertNull(repository.getCurrentConsent("profile-1"))
    }

    @Test fun `grant sends version and only usage monitoring purpose with adult bearer`() = runTest {
        val handler = ConsentApiHandler()
        val repository = RemoteMonitoringConsentRepository(handler.api(), AdultRequestAuthorization(TestAdultCredentialStore()))

        val result = repository.grantConsent("profile-1", "1.0", setOf("USAGE_MONITORING"))

        val args = handler.calls.single().second
        val request = args[2] as ConsentGrantRequest
        assertEquals(listOf("profile-1", "Bearer adult-token"), args.take(2))
        assertEquals("1.0", request.privacyNoticeVersion)
        assertEquals(listOf("USAGE_MONITORING"), request.purposes)
        assertEquals(MonitoringConsentStatus.ACTIVE, result.status)
        val json = Gson().toJson(request)
        assertTrue(json.contains("\"privacy_notice_version\":\"1.0\""))
        assertFalse(json.contains("adult_id"))
        assertFalse(json.contains("profile_id"))
    }

    @Test fun `revoke uses profile consent and adult bearer and maps revoked response`() = runTest {
        val handler = ConsentApiHandler()
        val repository = RemoteMonitoringConsentRepository(handler.api(), AdultRequestAuthorization(TestAdultCredentialStore()))

        val result = repository.revokeConsent("profile-1", "consent-1")

        assertEquals(listOf("profile-1", "consent-1", "Bearer adult-token"), handler.calls.single().second)
        assertEquals(MonitoringConsentStatus.REVOKED, result.status)
        assertEquals("2026-10-07T11:00:00Z", result.revokedAt)
    }

    @Test fun `repository rejects device authorization before any request`() {
        val handler = ConsentApiHandler()
        assertThrows(IllegalArgumentException::class.java) {
            RemoteMonitoringConsentRepository(
                handler.api(),
                DeviceRequestAuthorization(TestDeviceCredentialStore()),
            )
        }
        assertTrue(handler.calls.isEmpty())
    }

    @Test fun `adult 401 clears only adult credential and preserves device state`() = runTest {
        val handler = ConsentApiHandler().apply { failures["getRepresentativeVerification"] = httpException(401) }
        val adultStore = TestAdultCredentialStore()
        val deviceStore = TestDeviceCredentialStore(DeviceCredentials(accessToken = "device-jwt"))
        val repository = RemoteMonitoringConsentRepository(handler.api(), AdultRequestAuthorization(adultStore))

        try {
            repository.getRepresentativeVerification("profile-1")
        } catch (_: AdultAuthenticationRequiredException) {
        }

        assertNull(adultStore.adultAccessToken.value)
        assertEquals(1, adultStore.clearCalls)
        assertEquals("device-jwt", deviceStore.credentials.value.accessToken)
        assertEquals(0, deviceStore.clearAccessTokenCalls)
    }

    @Test fun `adult 403 preserves credential and becomes controlled authorization error`() = runTest {
        val handler = ConsentApiHandler().apply { failures["getRepresentativeVerification"] = httpException(403) }
        val adultStore = TestAdultCredentialStore()
        val repository = RemoteMonitoringConsentRepository(handler.api(), AdultRequestAuthorization(adultStore))

        var forbidden = false
        try {
            repository.getRepresentativeVerification("profile-1")
        } catch (_: AdultAuthorizationException) {
            forbidden = true
        }
        assertTrue(forbidden)
        assertEquals("adult-token", adultStore.adultAccessToken.value)
        assertEquals(0, adultStore.clearCalls)
    }
}
