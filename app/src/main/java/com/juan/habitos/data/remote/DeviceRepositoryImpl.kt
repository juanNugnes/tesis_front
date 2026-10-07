package com.juan.habitos.data.remote

import com.juan.habitos.domain.model.DeviceProfileClaim
import com.juan.habitos.domain.model.AdultProfileLinkCode
import com.juan.habitos.domain.model.RegisteredDevice
import com.juan.habitos.domain.repository.DeviceIdentityLocalStore
import com.juan.habitos.domain.repository.DeviceLinkException
import com.juan.habitos.domain.repository.DeviceLinkFailureReason
import com.juan.habitos.domain.repository.DeviceRepository
import com.juan.habitos.domain.repository.ProfileLocalStore
import kotlinx.coroutines.flow.first
import retrofit2.Response
import java.time.Instant

class RemoteDeviceRepository(
    private val local: DeviceIdentityLocalStore,
    private val profileStore: ProfileLocalStore,
    private val api: HabitsApi,
    private val now: () -> Instant = Instant::now,
) : DeviceRepository {
    override val identity = local.identity

    override suspend fun getOrCreateInstallationId(): String = local.getOrCreateInstallationId()

    override suspend fun getStoredDeviceId(): String? = local.identity.first().deviceId

    override suspend fun registerDevice(profileId: String): RegisteredDevice {
        val installationId = getOrCreateInstallationId()
        val bootstrap = local.credentials.first()
        val bootstrapToken = bootstrap.bootstrapToken
        if (bootstrapToken == null || bootstrap.bootstrapExpiresAt.isExpired(now())) {
            local.clearBootstrap()
            throw DeviceLinkException(DeviceLinkFailureReason.BOOTSTRAP_EXPIRED, "Device bootstrap credential is missing or expired")
        }
        val response = api.registerDevice(profileId, "Bearer $bootstrapToken", DeviceCreateRequest(installationId))
        if (response.code() == 401) {
            local.clearBootstrap()
            throw DeviceLinkException(DeviceLinkFailureReason.BOOTSTRAP_EXPIRED, "Device bootstrap credential was rejected")
        }
        val registration = response.bodyOrDeviceLinkFailure()
        val device = registration.toDomainDevice()
        require(device.profileId == profileId) { "Registered device belongs to another profile" }
        require(device.installationId == installationId) { "Registered device belongs to another installation" }
        local.saveAuthenticatedLink(profileId, device.id, registration.accessToken)
        return device
    }

    override suspend fun claimProfile(code: String): DeviceProfileClaim {
        require(code.matches(Regex("\\d{6}"))) { "Device link code must contain exactly 6 digits" }
        val installationId = getOrCreateInstallationId()
        val response = api.claimDeviceLink(DeviceLinkClaimRequest(code, installationId))
        val claimed = response.bodyOrDeviceLinkFailure()
        val profile = claimed.profile.toDomain()
        val device = claimed.device.toDomainDevice()
        require(device.profileId == profile.id) { "Claimed device and profile do not match" }
        require(device.installationId == installationId) { "Claimed device belongs to another installation" }
        profileStore.save(profile)
        local.saveAuthenticatedLink(requireNotNull(profile.id), device.id, claimed.accessToken)
        return DeviceProfileClaim(device, profile)
    }

    override suspend fun createAdultProfileLinkCode(): AdultProfileLinkCode {
        val profileId = local.identity.first().profileId
            ?: profileStore.profile.first()?.id
            ?: throw IllegalStateException("A linked profile is required")
        return deviceAuthenticatedRequest(local) { authorization ->
            api.createAdultProfileLinkCode(profileId, authorization)
        }.let { response -> AdultProfileLinkCode(response.code, response.expiresAt, response.purpose) }
    }
}

internal fun DeviceDto.toDomainDevice() = RegisteredDevice(
    id = id,
    profileId = profileId,
    installationId = installationId,
    platform = platform,
    active = active,
)

internal fun DeviceRegistrationDto.toDomainDevice() = RegisteredDevice(
    id = id,
    profileId = profileId,
    installationId = installationId,
    platform = platform,
    active = active,
)

private fun String?.isExpired(now: Instant): Boolean = this?.let { value ->
    runCatching { !Instant.parse(value).isAfter(now) }.getOrDefault(false)
} ?: true

private fun <T> Response<T>.bodyOrDeviceLinkFailure(): T {
    if (isSuccessful) return body() ?: throw DeviceLinkException(DeviceLinkFailureReason.SERVER_ERROR, "Empty device-link response")
    val reason = when (code()) {
        410 -> DeviceLinkFailureReason.INVALID_OR_EXPIRED_CODE
        409 -> DeviceLinkFailureReason.INSTALLATION_CONFLICT
        else -> DeviceLinkFailureReason.SERVER_ERROR
    }
    throw DeviceLinkException(reason, "Device-link request failed with HTTP ${code()}")
}
