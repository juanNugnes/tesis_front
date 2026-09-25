package com.juan.habitos

import android.app.Application
import com.juan.habitos.data.local.*
import com.juan.habitos.data.mock.*
import com.juan.habitos.data.remote.HabitsApiClient
import com.juan.habitos.domain.model.*
import kotlinx.coroutines.flow.first

class HabitosApplication : Application() {
    val profileRepository by lazy { DataStoreProfileRepository(this) }
    val pinStore by lazy { HashedPinStore(this) }
    val usageRepository by lazy { FakeUsageRepository() }
    val alertRepository by lazy { FakeAlertRepository() }
    val habitsApi by lazy { HabitsApiClient.api }

    suspend fun initializeDemoIfNeeded() {
        if (!BuildConfig.DEMO_MODE) return
        val hasProfile = profileRepository.profile.first() != null
        val onboardingDone = profileRepository.onboardingComplete.first()
        if (hasProfile && onboardingDone) return
        profileRepository.save(
            Profile(
                name = "Juana",
                avatar = "🦊",
                age = 7,
                schoolSchedules = setOf(SchoolSchedule.MORNING),
                bedtime = BedtimeRange.FROM_22_TO_23,
                schoolStart = "08:00",
                schoolEnd = "12:30",
                sleepStart = "22:00",
                sleepEnd = "07:00",
            )
        )
        pinStore.save("1234")
        profileRepository.completeOnboarding()
    }
}
