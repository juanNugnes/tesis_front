package com.juan.habitos.data.remote

import com.google.gson.JsonParser
import com.juan.habitos.domain.repository.*
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

enum class ProfileFormOperation(val method: String, val route: String) {
    PROFILE_READ("GET", "/api/v1/profiles/{profile_id}"),
    PREFERENCES_READ("GET", "/api/v1/profiles/{profile_id}/activity-preferences"),
    PREFERENCES_SAVE("PUT", "/api/v1/profiles/{profile_id}/activity-preferences"),
    PMUM_LATEST("GET", "/api/v1/profiles/{profile_id}/experimental-pmum/latest"),
    PMUM_SAVE("POST", "/api/v1/profiles/{profile_id}/experimental-pmum/responses"),
}

/** Temporary opt-in debug diagnostics. Accepts metadata only: never URLs, IDs, bodies or headers. */
class ProfileFormDiagnostics(private val write: (String) -> Unit = {}) {
    fun http(operation: ProfileFormOperation, code: Int?, outcome: String) {
        write("operation=${operation.name} method=${operation.method} route=${operation.route} http=${code ?: "none"} outcome=$outcome")
    }
    fun profileMatch(matches: Boolean) { write("operation=PROFILE_BINDING profile_matches_expected=$matches") }
}

internal suspend fun <T> profileFormRequest(
    operation: ProfileFormOperation, diagnostics: ProfileFormDiagnostics, request: suspend () -> T,
): T = try {
    request() // DTO-returning Retrofit methods do not expose the success HTTP status; do not invent it.
} catch (exception: CancellationException) { throw exception }
catch (exception: HttpException) {
    diagnostics.http(operation, exception.code(), "failed")
    if (exception.code() != 404) throw exception // Preserve existing 401 token invalidation / 403 handling.
    val detail = runCatching {
        JsonParser.parseString(exception.response()?.errorBody()?.string()).asJsonObject["detail"]
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }.getOrNull()
    // Caddy text/plain Not Found and FastAPI's generic router 404 are NOT an empty questionnaire.
    val reason = when {
        detail == null || detail.trim().equals("Not Found", ignoreCase = true) -> ProfileFormFailure.ENDPOINT_UNAVAILABLE
        operation == ProfileFormOperation.PROFILE_READ -> ProfileFormFailure.PROFILE_NOT_FOUND
        else -> ProfileFormFailure.UNCLASSIFIED_NOT_FOUND
    }
    throw ProfileFormException(reason, exception)
} catch (exception: Exception) {
    diagnostics.http(operation, null, "transport_or_decode_failure")
    throw exception
}

internal suspend fun verifyFormProfile(api: HabitsApi, authorization: RequestAuthorization, profileId: String, diagnostics: ProfileFormDiagnostics) {
    val profile = authorization.execute { header ->
        profileFormRequest(ProfileFormOperation.PROFILE_READ, diagnostics) { api.getProfile(profileId, header) }
    }
    val matches = profile.id == profileId
    diagnostics.profileMatch(matches)
    if (!matches) throw ProfileFormException(ProfileFormFailure.PROFILE_CHANGED)
}
