package com.juan.habitos.data.remote

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.*
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.lang.reflect.Proxy

class ParentalScreenTimeRepositoryTest {
    private val calls = mutableListOf<Pair<String, List<Any?>>>()
    private var failure: Exception? = null
    private var profileFailure: Exception? = null
    private var dto = ParentalScreenTimeReviewDto(ParentalScreenTimeResponseDto("response", "child", 1, 9, 1, 2, 3, "parent_or_caregiver", 30))
    private val api = Proxy.newProxyInstance(HabitsApi::class.java.classLoader, arrayOf(HabitsApi::class.java)) { _, method, args ->
        calls += method.name to args.orEmpty().toList().dropLast(1)
        if (method.name == "getProfile") {
            profileFailure?.let { throw it }
            return@newProxyInstance ProfileDetailDto("child", "Niño", 9, "🌿", true)
        }
        failure?.let { throw it }
        dto
    } as HabitsApi
    private val store = TestAdultCredentialStore("adult-token")
    private fun repository() = RemoteParentalScreenTimeRepository(api, AdultRequestAuthorization(store))
    @Suppress("DEPRECATION") private fun http(code: Int, body: String = "{}") = HttpException(Response.error<Any>(code, ResponseBody.create(MediaType.parse("application/json"), body)))

    @Test fun `both operations use linked adult bearer and exact profile with unchanged contract`() = runTest {
        val repo = repository()
        assertEquals(9, repo.latest("child")!!.age)
        repo.save("child", ParentalScreenTimeAnswers(1, 2, 3))
        assertEquals(listOf("child", "Bearer adult-token"), calls[0].second)
        assertEquals(listOf("child", "Bearer adult-token"), calls[1].second.take(2))
        assertEquals(ParentalScreenTimeRequest(1, 2, 3), calls[1].second[2])
    }

    @Test fun `null fields are explicit JSON nulls and request never contains age or estimate`() {
        val json = JsonParser.parseString(GsonBuilder().serializeNulls().create().toJson(ParentalScreenTimeRequest(null, null, null))).asJsonObject
        assertEquals(setOf("sex_code", "weekday_category", "weekend_category", "respondent", "reference_days"), json.keySet())
        listOf("sex_code", "weekday_category", "weekend_category").forEach { assertTrue(json[it].isJsonNull) }
        assertEquals("parent_or_caregiver", json["respondent"].asString)
        assertEquals(30, json["reference_days"].asInt)
    }

    @Test fun `latest 404 is empty form but POST 404 is not a saved answer`() = runTest {
        failure = http(404, """{"detail":"No previous parental responses"}""")
        val repo = repository()
        assertNull(repo.latest("child"))
        failure = http(404, """{"detail":"No previous parental responses"}""")
        var threw = false
        try { repo.save("child", ParentalScreenTimeAnswers(null, null, null)) } catch (error: ProfileFormException) {
            assertEquals(ProfileFormFailure.UNCLASSIFIED_NOT_FOUND, error.reason); threw = true
        }
        assertTrue(threw)
        assertTrue(calls.filter { it.first == "getProfile" }.all { it.second == listOf("child", "Bearer adult-token") })
    }

    @Test fun `Caddy and FastAPI routing 404 never become an empty questionnaire or deleted profile`() = runTest {
        for (body in listOf("Not Found", """{"detail":"Not Found"}""", "{}")) {
            failure = http(404, body)
            try { repository().latest("child"); fail() } catch (error: ProfileFormException) {
                assertEquals(ProfileFormFailure.ENDPOINT_UNAVAILABLE, error.reason)
            }
            try { repository().save("child", ParentalScreenTimeAnswers(1, 2, 3)); fail() } catch (error: ProfileFormException) {
                assertEquals(ProfileFormFailure.ENDPOINT_UNAVAILABLE, error.reason)
            }
        }
        assertFalse(calls.any { it.first == "getProfile" })
    }

    @Test fun `domain latest 404 verifies existence and adult permission before returning empty`() = runTest {
        failure = http(404, """{"detail":"No previous parental responses"}""")
        for (code in listOf(401, 403, 404)) {
            failure = http(404, """{"detail":"No previous parental responses"}""")
            val credentials = TestAdultCredentialStore("valid-adult")
            val repo = RemoteParentalScreenTimeRepository(api, AdultRequestAuthorization(credentials))
            profileFailure = http(code, """{"detail":"Profile not found"}""")
            try { repo.latest("child"); fail() } catch (error: Exception) {
                when (code) {
                    401 -> { assertTrue(error is AdultAuthenticationRequiredException); assertNull(credentials.adultAccessToken.value) }
                    403 -> assertTrue(error is AdultAuthorizationException)
                    404 -> assertEquals(ProfileFormFailure.PROFILE_NOT_FOUND, (error as ProfileFormException).reason)
                }
            }
        }
    }

    @Test fun `POST domain 404 is a real missing profile only after profile lookup confirms it`() = runTest {
        failure = http(404, """{"detail":"Profile not found"}""")
        profileFailure = http(404, """{"detail":"Profile not found"}""")
        try { repository().save("child", ParentalScreenTimeAnswers(null, null, null)); fail() }
        catch (error: ProfileFormException) { assertEquals(ProfileFormFailure.PROFILE_NOT_FOUND, error.reason) }
        assertEquals(1, calls.count { it.first == "saveParentalScreenTime" })
    }

    @Test fun `401 clears adult token and 403 preserves it and rejects operation`() = runTest {
        failure = http(401)
        try { repository().latest("child"); fail() } catch (_: AdultAuthenticationRequiredException) { }
        assertNull(store.adultAccessToken.value)
        val newStore = TestAdultCredentialStore("adult")
        failure = http(403)
        try { RemoteParentalScreenTimeRepository(api, AdultRequestAuthorization(newStore)).save("child", ParentalScreenTimeAnswers(1, 2, 3)); fail() } catch (_: AdultAuthorizationException) { }
        assertEquals("adult", newStore.adultAccessToken.value)
    }

    @Test fun `device credentials cannot be used and invalid choices never send requests`() = runTest {
        assertThrows(IllegalArgumentException::class.java) { RemoteParentalScreenTimeRepository(api, DeviceRequestAuthorization(TestDeviceCredentialStore())) }
        try { repository().save("child", ParentalScreenTimeAnswers(4, 2, 3)); fail() } catch (_: IllegalArgumentException) { }
        try { repository().save("child", ParentalScreenTimeAnswers(1, 6, 3)); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(calls.isEmpty())
    }

    @Test fun `technical estimation errors and unavailable model do not hide persisted response`() = runTest {
        for (status in listOf("TECHNICAL_ERROR", "UNAVAILABLE", "ESTIMATED")) {
            val json = """{"response":{"id":"saved","profile_id":"child","revision":7,"age":9,"sex_code":2,"weekday_category":2,"weekend_category":3,"respondent":"parent_or_caregiver","reference_days":30},"estimate":{"status":"$status","estimated_pmum_sf_mean":3.1,"technical_error_code":"HTTP_ERROR"}}"""
            dto = GsonBuilder().serializeNulls().create().fromJson(json, ParentalScreenTimeReviewDto::class.java)
            val saved = repository().save("child", ParentalScreenTimeAnswers(2, 2, 3))
            assertEquals("saved", saved.id)
            assertEquals(7, saved.revision)
        }
    }

    @Test fun `different profile and unsupported stored values fail without inventing defaults`() = runTest {
        dto = dto.copy(response = dto.response.copy(profileId = "other-child"))
        try { repository().latest("child"); fail() } catch (_: IllegalArgumentException) { }
        dto = dto.copy(response = dto.response.copy(profileId = "child", sexCode = 4))
        try { repository().latest("child"); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun `network failure propagates and repository does not retry non idempotent save`() = runTest {
        failure = java.io.IOException("offline")
        var received: Exception? = null
        try { repository().save("child", ParentalScreenTimeAnswers(null, 2, null)) } catch (exception: Exception) { received = exception }
        // The Java proxy wraps checked exceptions; real Retrofit exposes IOException directly.
        assertTrue(received is java.io.IOException || received?.cause is java.io.IOException)
        assertEquals(1, calls.size)
    }
}
