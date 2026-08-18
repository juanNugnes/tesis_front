package com.juan.habitos.domain.repository

import com.juan.habitos.domain.model.*
import kotlinx.coroutines.flow.Flow

interface ProfileRepository { val profile: Flow<Profile?>; val onboardingComplete: Flow<Boolean>; suspend fun save(profile: Profile); suspend fun completeOnboarding() }
interface UsageRepository { suspend fun daily(period: DashboardPeriod = DashboardPeriod.TODAY): DailyUsage; suspend fun apps(): List<AppUsage>; suspend fun recommendation(): Recommendation }
interface AlertRepository { suspend fun alerts(): List<Alert> }
interface PinStore { suspend fun save(pin: String); suspend fun verify(pin: String): Boolean }
interface DeviceUsageDataSource { suspend fun getDailyUsage(): DailyUsage; suspend fun getApplicationUsage(): List<AppUsage> }
