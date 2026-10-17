package com.juan.habitos.data.remote

import com.juan.habitos.domain.repository.DeviceAuthenticationRequiredException
import com.juan.habitos.domain.repository.DeviceAuthenticationFailureReason
import com.juan.habitos.domain.repository.DeviceCredentialStore
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

internal suspend fun <T> deviceAuthenticatedRequest(
    credentialStore: DeviceCredentialStore,
    request: suspend (authorization: String) -> T,
): T {
    val token = credentialStore.credentials.first().accessToken
        ?: throw DeviceAuthenticationRequiredException(DeviceAuthenticationFailureReason.MISSING_CREDENTIAL)
    return try {
        request("Bearer $token")
    } catch (exception: HttpException) {
        if (exception.code() == 401) {
            credentialStore.clearDeviceAccessToken()
            throw DeviceAuthenticationRequiredException(DeviceAuthenticationFailureReason.EXPIRED_OR_INVALID)
        }
        throw exception
    }
}
