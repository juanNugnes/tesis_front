package com.juan.habitos.data.remote

import com.juan.habitos.domain.model.AdultManagedDevice
import com.juan.habitos.domain.repository.AdultDeviceNotFoundException
import com.juan.habitos.domain.repository.AdultDeviceRepository
import retrofit2.HttpException

class RemoteAdultDeviceRepository(
    private val api: HabitsApi,
    private val authorization: RequestAuthorization,
) : AdultDeviceRepository {
    init {
        require(authorization.principal == RequestPrincipal.ADULT) {
            "Device administration requires Adult authorization"
        }
    }

    override suspend fun devices(profileId: String): List<AdultManagedDevice> {
        require(profileId.isNotBlank()) { "profileId is required" }
        val devices = authorization.execute { header -> api.getProfileDevices(profileId, header) }
            .map { dto -> dto.toManagedDevice(profileId) }
        require(devices.map(AdultManagedDevice::id).distinct().size == devices.size) {
            "Backend returned duplicate devices"
        }
        return devices
    }

    override suspend fun deactivate(profileId: String, deviceId: String): AdultManagedDevice {
        require(profileId.isNotBlank()) { "profileId is required" }
        require(deviceId.isNotBlank()) { "deviceId is required" }
        val response = try {
            authorization.execute { header -> api.deactivateProfileDevice(profileId, deviceId, header) }
        } catch (exception: HttpException) {
            if (exception.code() == 404) throw AdultDeviceNotFoundException()
            throw exception
        }
        require(response.id == deviceId) { "Backend returned another device" }
        return response.toManagedDevice(profileId)
    }
}

private fun DeviceDto.toManagedDevice(expectedProfileId: String): AdultManagedDevice {
    require(profileId == expectedProfileId) { "Backend returned a device from another profile" }
    return AdultManagedDevice(
        id = id,
        profileId = profileId,
        platform = platform,
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastSeenAt = lastSeenAt,
    )
}
