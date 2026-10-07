package com.juan.habitos.data.remote

import com.juan.habitos.domain.repository.AdultCredentialStore
import com.juan.habitos.domain.repository.DeviceCredentialStore

enum class RequestPrincipal { DEVICE, ADULT }

interface RequestAuthorization {
    val principal: RequestPrincipal
    suspend fun <T> execute(request: suspend (authorization: String) -> T): T
}

internal class DeviceRequestAuthorization(
    private val credentialStore: DeviceCredentialStore,
) : RequestAuthorization {
    override val principal = RequestPrincipal.DEVICE
    override suspend fun <T> execute(request: suspend (authorization: String) -> T): T =
        deviceAuthenticatedRequest(credentialStore, request)
}

internal class AdultRequestAuthorization(
    private val credentialStore: AdultCredentialStore,
) : RequestAuthorization {
    override val principal = RequestPrincipal.ADULT
    override suspend fun <T> execute(request: suspend (authorization: String) -> T): T =
        adultAuthenticatedRequest(credentialStore, request)
}
