package com.juan.habitos.data.remote

import com.juan.habitos.domain.repository.*
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ProfileFormDiagnosticsTest {
    @Suppress("DEPRECATION") private fun http(code: Int, body: String) = HttpException(Response.error<Any>(code,
        ResponseBody.create(MediaType.parse("application/json"), body)))

    @Test fun `metadata contains operation method template and actual failed status without private data`() = runTest {
        val output = mutableListOf<String>()
        val diagnostic = ProfileFormDiagnostics(output::add)
        val body = """{"detail":"secret-email@example.com Bearer secret-token profile-secret"}"""
        try { profileFormRequest(ProfileFormOperation.PMUM_SAVE, diagnostic) { throw http(404, body) } }
        catch (_: ProfileFormException) { }
        diagnostic.profileMatch(true)
        assertEquals("operation=PMUM_SAVE method=POST route=/api/v1/profiles/{profile_id}/experimental-pmum/responses http=404 outcome=failed", output.first())
        assertTrue(output.last().contains("profile_matches_expected=true"))
        assertFalse(output.joinToString().contains("secret"))
        assertFalse(output.joinToString().contains("Bearer"))
    }

    @Test fun `401 and 403 reach existing authorization handler unchanged`() = runTest {
        for (code in listOf(401, 403)) {
            val exception = http(code, "{}")
            try { profileFormRequest(ProfileFormOperation.PREFERENCES_SAVE, ProfileFormDiagnostics()) { throw exception }; fail() }
            catch (actual: HttpException) { assertSame(exception, actual) }
        }
    }

    @Test fun `only profile GET domain 404 can establish a missing profile`() = runTest {
        for (operation in ProfileFormOperation.entries) {
            try { profileFormRequest(operation, ProfileFormDiagnostics()) { throw http(404, """{"detail":"Profile not found"}""") } }
            catch (error: ProfileFormException) { assertEquals(
                if (operation == ProfileFormOperation.PROFILE_READ) ProfileFormFailure.PROFILE_NOT_FOUND else ProfileFormFailure.UNCLASSIFIED_NOT_FOUND,
                error.reason,
            ) }
        }
    }
}
