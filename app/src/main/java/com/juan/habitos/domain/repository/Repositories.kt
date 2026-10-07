package com.juan.habitos.domain.repository

import com.juan.habitos.domain.model.*
import kotlinx.coroutines.flow.Flow

interface ProfileLocalStore {
    val profile: Flow<Profile?>
    val onboardingComplete: Flow<Boolean>
    suspend fun save(profile: Profile)
    suspend fun completeOnboarding()
}
interface ProfileRepository : ProfileLocalStore {
    suspend fun create(profile: Profile): Profile
    suspend fun refresh(): Profile?
    suspend fun update(profile: Profile): Profile
    suspend fun refreshSettings(): Profile?
    suspend fun updateSettings(profile: Profile): Profile
}
interface ChildPreferencesLocalStore { val preferences: Flow<ChildPreferences>; suspend fun save(preferences: ChildPreferences) }
interface ChildPreferencesRepository : ChildPreferencesLocalStore {
    suspend fun refresh(): ChildPreferences
    suspend fun update(preferences: ChildPreferences): ChildPreferences
}
interface ProfileContextLocalStore {
    val contexts: Flow<List<ProfileContext>>
    suspend fun save(contexts: List<ProfileContext>)
}
interface ProfileContextRepository {
    val contexts: Flow<List<ProfileContext>>
    suspend fun refresh(): List<ProfileContext>
    suspend fun getActiveContexts(): List<ProfileContext>
    suspend fun create(context: ProfileContext): ProfileContext
    suspend fun update(context: ProfileContext): ProfileContext
    suspend fun delete(contextId: String)
}
interface UsageRepository { suspend fun daily(period: DashboardPeriod = DashboardPeriod.TODAY): DailyUsage; suspend fun apps(): List<AppUsage>; suspend fun recommendation(): Recommendation }
interface AlertRepository { suspend fun alerts(): List<Alert> }
interface PinStore { suspend fun save(pin: String); suspend fun verify(pin: String): Boolean }
interface DeviceUsageDataSource { suspend fun getDailyUsage(): DailyUsage; suspend fun getApplicationUsage(): List<AppUsage> }
interface AdultCredentialStore {
    val adultAccessToken: Flow<String?>
    suspend fun saveAdultAccessToken(token: String)
    suspend fun clearAdultAuthentication()
}
interface DeviceSessionRepository : AdultCredentialStore { val session: Flow<DeviceSession>; suspend fun setMode(mode: DeviceMode); suspend fun authenticateAdult(email: String); suspend fun addLinkedProfile(profileKey: String) }
interface DeviceCredentialStore {
    val credentials: Flow<DeviceCredentials>
    suspend fun saveBootstrap(token: String, expiresAt: String)
    suspend fun clearBootstrap()
    suspend fun saveAuthenticatedLink(profileId: String, deviceId: String, accessToken: String)
    suspend fun clearDeviceAccessToken()
}
interface DeviceIdentityLocalStore : DeviceCredentialStore {
    val identity: Flow<DeviceIdentity>
    suspend fun getOrCreateInstallationId(): String
}
interface DeviceRepository {
    val identity: Flow<DeviceIdentity>
    suspend fun getOrCreateInstallationId(): String
    suspend fun getStoredDeviceId(): String?
    suspend fun registerDevice(profileId: String): RegisteredDevice
    suspend fun claimProfile(code: String): DeviceProfileClaim
    suspend fun createAdultProfileLinkCode(): AdultProfileLinkCode
}
enum class DeviceLinkFailureReason { INVALID_OR_EXPIRED_CODE, INSTALLATION_CONFLICT, BOOTSTRAP_EXPIRED, SERVER_ERROR }
class DeviceLinkException(val reason: DeviceLinkFailureReason, message: String) : Exception(message)
class DeviceAuthenticationRequiredException : Exception("Device authentication required")
class AdultAuthenticationRequiredException : Exception("Adult authentication required")
class AdultAuthorizationException : Exception("Adult operation forbidden")
enum class AdultLinkFailureReason { INVALID_OR_EXPIRED_CODE, REVOKED_LINK }
class AdultLinkException(val reason: AdultLinkFailureReason, message: String) : Exception(message)
interface AuthRepository {
    suspend fun requestVerificationCode(email: String): AdultOtpChallenge
    suspend fun verifyCode(email: String, code: String): Boolean
    suspend fun validateSession(): AdultAccount
}
interface LinkRepository {
    suspend fun profiles(): List<Profile>
    suspend fun findProfile(code: String): Profile?
    suspend fun link(profile: Profile)
}
