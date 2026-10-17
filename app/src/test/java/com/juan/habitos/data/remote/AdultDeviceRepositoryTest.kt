package com.juan.habitos.data.remote

import com.juan.habitos.domain.model.DeviceCredentials
import com.juan.habitos.domain.repository.AdultAuthenticationRequiredException
import com.juan.habitos.domain.repository.AdultAuthorizationException
import com.juan.habitos.domain.repository.AdultDeviceNotFoundException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

private class AdultDeviceApiHandler : InvocationHandler {
    var devices = listOf(deviceDto("device-1", active = true), deviceDto("device-2", active = false))
    var deactivated = deviceDto("device-1", active = false)
    var failure: Exception? = null
    val calls = mutableListOf<Pair<String, List<Any?>>>()

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        failure?.let { throw it }
        calls += method.name to args.orEmpty().toList().dropLast(1)
        return when (method.name) {
            "getProfileDevices" -> devices
            "deactivateProfileDevice" -> deactivated
            "toString" -> "AdultDeviceApi"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected API call: ${method.name}")
        }
    }

    fun api(): HabitsApi = Proxy.newProxyInstance(HabitsApi::class.java.classLoader, arrayOf(HabitsApi::class.java), this) as HabitsApi
}

private fun deviceDto(id: String, active: Boolean, profileId: String = "profile-1") = DeviceDto(
    id = id,
    profileId = profileId,
    installationId = "sensitive-installation-$id",
    platform = "ANDROID",
    active = active,
    createdAt = "2026-10-10T10:00:00Z",
    updatedAt = "2026-10-16T18:30:00Z",
    lastSeenAt = "2026-10-16T18:25:00Z",
)

@Suppress("DEPRECATION")
private fun deviceHttpException(code: Int) = HttpException(
    Response.error<Any>(code, ResponseBody.create(MediaType.parse("application/json"), "{}")),
)

class AdultDeviceRepositoryTest {
    @Test fun `list maps active and inactive devices and uses AdultBearer`() = runTest {
        val handler = AdultDeviceApiHandler()
        val repository = RemoteAdultDeviceRepository(handler.api(), AdultRequestAuthorization(TestAdultCredentialStore("adult-jwt")))

        val result = repository.devices("profile-1")

        assertEquals(listOf(true, false), result.map { it.active })
        assertEquals("2026-10-16T18:25:00Z", result.first().lastSeenAt)
        assertEquals("getProfileDevices", handler.calls.single().first)
        assertEquals(listOf("profile-1", "Bearer adult-jwt"), handler.calls.single().second)
    }

    @Test fun `empty backend list remains empty`() = runTest {
        val handler = AdultDeviceApiHandler().apply { devices = emptyList() }
        val repository = RemoteAdultDeviceRepository(handler.api(), AdultRequestAuthorization(TestAdultCredentialStore()))
        assertTrue(repository.devices("profile-1").isEmpty())
    }

    @Test fun `deactivate uses selected profile device and keeps returned record visible`() = runTest {
        val handler = AdultDeviceApiHandler()
        val repository = RemoteAdultDeviceRepository(handler.api(), AdultRequestAuthorization(TestAdultCredentialStore()))

        val result = repository.deactivate("profile-1", "device-1")

        assertFalse(result.active)
        assertEquals(listOf("profile-1", "device-1", "Bearer adult-token"), handler.calls.single().second)
    }

    @Test fun `404 becomes device not found`() = runTest {
        val handler = AdultDeviceApiHandler().apply { failure = deviceHttpException(404) }
        val repository = RemoteAdultDeviceRepository(handler.api(), AdultRequestAuthorization(TestAdultCredentialStore()))
        assertThrows(AdultDeviceNotFoundException::class.java) {
            kotlinx.coroutines.runBlocking { repository.deactivate("profile-1", "device-1") }
        }
    }

    @Test fun `401 expires adult session and 403 becomes authorization error`() = runTest {
        val handler = AdultDeviceApiHandler()
        val store = TestAdultCredentialStore()
        val repository = RemoteAdultDeviceRepository(handler.api(), AdultRequestAuthorization(store))

        handler.failure = deviceHttpException(401)
        assertThrows(AdultAuthenticationRequiredException::class.java) { kotlinx.coroutines.runBlocking { repository.devices("profile-1") } }
        assertNull(store.adultAccessToken.value)

        val forbidden = RemoteAdultDeviceRepository(
            AdultDeviceApiHandler().apply { failure = deviceHttpException(403) }.api(),
            AdultRequestAuthorization(TestAdultCredentialStore()),
        )
        assertThrows(AdultAuthorizationException::class.java) { kotlinx.coroutines.runBlocking { forbidden.devices("profile-1") } }
    }

    @Test fun `repository rejects DeviceBearer before any API request`() {
        val handler = AdultDeviceApiHandler()
        assertThrows(IllegalArgumentException::class.java) {
            RemoteAdultDeviceRepository(
                handler.api(),
                DeviceRequestAuthorization(TestDeviceCredentialStore(DeviceCredentials(accessToken = "device-jwt"))),
            )
        }
        assertTrue(handler.calls.isEmpty())
    }

    @Test fun `backend cannot inject a device from another profile`() = runTest {
        val handler = AdultDeviceApiHandler().apply { devices = listOf(deviceDto("device-x", true, "profile-other")) }
        val repository = RemoteAdultDeviceRepository(handler.api(), AdultRequestAuthorization(TestAdultCredentialStore()))
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repository.devices("profile-1") } }
    }
}
