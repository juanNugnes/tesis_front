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
interface UsageRepository { suspend fun daily(period: DashboardPeriod = DashboardPeriod.TODAY): DailyUsage; suspend fun apps(): List<AppUsage>; suspend fun recommendation(): Recommendation }
interface AlertRepository { suspend fun alerts(): List<Alert> }
interface PinStore { suspend fun save(pin: String); suspend fun verify(pin: String): Boolean }
interface DeviceUsageDataSource { suspend fun getDailyUsage(): DailyUsage; suspend fun getApplicationUsage(): List<AppUsage> }
interface DeviceSessionRepository { val session: Flow<DeviceSession>; suspend fun setMode(mode: DeviceMode); suspend fun authenticateAdult(email: String); suspend fun addLinkedProfile(profileKey: String) }
interface AuthRepository { suspend fun requestVerificationCode(email: String); suspend fun verifyCode(email: String, code: String): Boolean }
interface LinkRepository { suspend fun findProfile(code: String): Profile?; suspend fun link(profile: Profile) }
