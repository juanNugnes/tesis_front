package com.juan.habitos.data.remote

import com.juan.habitos.domain.model.DeviceCredentials
import com.juan.habitos.domain.repository.DeviceCredentialStore
import com.juan.habitos.domain.repository.AdultCredentialStore
import kotlinx.coroutines.flow.MutableStateFlow

internal class TestDeviceCredentialStore(
    initial: DeviceCredentials = DeviceCredentials(accessToken = "device-token"),
) : DeviceCredentialStore {
    override val credentials = MutableStateFlow(initial)
    var clearAccessTokenCalls = 0

    override suspend fun saveBootstrap(token: String, expiresAt: String) {
        credentials.value = credentials.value.copy(bootstrapToken = token, bootstrapExpiresAt = expiresAt)
    }

    override suspend fun clearBootstrap() {
        credentials.value = credentials.value.copy(bootstrapToken = null, bootstrapExpiresAt = null)
    }

    override suspend fun saveAuthenticatedLink(profileId: String, deviceId: String, accessToken: String) {
        credentials.value = DeviceCredentials(accessToken = accessToken)
    }

    override suspend fun clearDeviceAccessToken() {
        clearAccessTokenCalls++
        credentials.value = credentials.value.copy(accessToken = null)
    }
}

internal class TestAdultCredentialStore(initialToken: String? = "adult-token") : AdultCredentialStore {
    override val adultAccessToken = MutableStateFlow(initialToken)
    var clearCalls = 0
    override suspend fun saveAdultAccessToken(token: String) { adultAccessToken.value = token }
    override suspend fun clearAdultAuthentication() { clearCalls++; adultAccessToken.value = null }
}
