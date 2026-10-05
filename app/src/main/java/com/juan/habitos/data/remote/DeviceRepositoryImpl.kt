package com.juan.habitos.data.remote

import com.juan.habitos.domain.model.DeviceProfileClaim
import com.juan.habitos.domain.model.RegisteredDevice
import com.juan.habitos.domain.repository.DeviceIdentityLocalStore
import com.juan.habitos.domain.repository.DeviceLinkException
import com.juan.habitos.domain.repository.DeviceLinkFailureReason
import com.juan.habitos.domain.repository.DeviceRepository
import com.juan.habitos.domain.repository.ProfileLocalStore
import kotlinx.coroutines.flow.first
import retrofit2.Response

class RemoteDeviceRepository(
    private val local: DeviceIdentityLocalStore,
    private val profileStore: ProfileLocalStore,
    private val api: HabitsApi,
) : DeviceRepository {
    override val identity = local.identity

    override suspend fun getOrCreateInstallationId(): String = local.getOrCreateInstallationId()

    override suspend fun getStoredDeviceId(): String? = local.identity.first().deviceId

    override suspend fun registerDevice(profileId: String): RegisteredDevice {
        val installationId = getOrCreateInstallationId()
        val response = api.registerDevice(profileId, DeviceCreateRequest(installationId))
        val device = response.bodyOrDeviceLinkFailure().toDomainDevice()
        require(device.profileId == profileId) { "Registered device belongs to another profile" }
        require(device.installationId == installationId) { "Registered device belongs to another installation" }
        local.saveLink(profileId, device.id)
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
        local.saveLink(requireNotNull(profile.id), device.id)
        return DeviceProfileClaim(device, profile)
    }
}

internal fun DeviceDto.toDomainDevice() = RegisteredDevice(
    id = id,
    profileId = profileId,
    installationId = installationId,
    platform = platform,
    active = active,
)

private fun <T> Response<T>.bodyOrDeviceLinkFailure(): T {
    if (isSuccessful) return body() ?: throw DeviceLinkException(DeviceLinkFailureReason.SERVER_ERROR, "Empty device-link response")
    val reason = when (code()) {
        410 -> DeviceLinkFailureReason.INVALID_OR_EXPIRED_CODE
        409 -> DeviceLinkFailureReason.INSTALLATION_CONFLICT
        else -> DeviceLinkFailureReason.SERVER_ERROR
    }
    throw DeviceLinkException(reason, "Device-link request failed with HTTP ${code()}")
}
