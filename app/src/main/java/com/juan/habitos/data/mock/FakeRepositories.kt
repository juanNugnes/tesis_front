package com.juan.habitos.data.mock

import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.*

class FakeUsageRepository : UsageRepository, DeviceUsageDataSource {
    override suspend fun daily(period: DashboardPeriod) = if (period == DashboardPeriod.TODAY) DailyUsage(165, 180, 8) else DailyUsage(990, 1260, 42)
    override suspend fun apps() = listOf(
        AppUsage("YouTube Kids", "com.google.android.apps.youtube.kids", 80, "Video", "▶️"),
        AppUsage("Roblox", "com.roblox.client", 45, "Juegos", "🎮"),
        AppUsage("Khan Academy", "org.khanacademy.android", 35, "Educación", "📚")
    )
    override suspend fun recommendation() = Recommendation("¿Querés hacer algo diferente?", "", "🎨 Dibujá algo que te guste")
    override suspend fun getDailyUsage() = daily()
    override suspend fun getApplicationUsage() = apps()
}

/** Demo/preview-only suggestions. Non-demo builds always use the backend repository. */
class PreviewActivitySuggestionRepository : ActivitySuggestionRepository {
    override suspend fun suggestions() = ActivitySuggestionOffer(
        offerId = null,
        activities = listOf(
            ActivitySuggestion("demo-draw", "Dibujá algo que te guste", "Elegí colores y creá un dibujo libre.", "CREATIVE", "LOW", false, false, false),
            ActivitySuggestion("demo-move", "Bailá una canción", "Poné una canción y movete a tu manera.", "MOVEMENT", "MEDIUM", false, false, true),
            ActivitySuggestion("demo-story", "Inventá una historia", "Imaginá un personaje y contá una aventura.", "STORIES", "LOW", true, false, false),
        ),
    )
    override suspend fun registerSelection(selection: ActivitySelection) = Unit
    override suspend fun selectionCount(startEpochMillis: Long, endEpochMillis: Long) = 0
}
class FakeAlertRepository : AlertRepository {
    override suspend fun alerts() = demoAlertFixtures()
}

class EmptyAlertRepository : AlertRepository {
    override suspend fun alerts(): List<Alert> = emptyList()
}

internal fun alertsDemoEnabled(isDebug: Boolean, alertsDemoMode: Boolean): Boolean =
    isDebug && alertsDemoMode

internal fun alertRepositoryForMode(isDebug: Boolean, alertsDemoMode: Boolean): AlertRepository =
    if (alertsDemoEnabled(isDebug, alertsDemoMode)) FakeAlertRepository() else EmptyAlertRepository()

class PreviewAdultDeviceRepository : AdultDeviceRepository {
    override suspend fun devices(profileId: String) = listOf(
        AdultManagedDevice(
            id = "demo-device",
            profileId = profileId,
            platform = "ANDROID",
            active = true,
            lastSeenAt = "2026-10-16T18:30:00Z",
        )
    )

    override suspend fun deactivate(profileId: String, deviceId: String): AdultManagedDevice =
        throw UnsupportedOperationException("Demo device data is read-only")
}

class MockAuthRepository : AuthRepository {
    private companion object { const val VERIFICATION_CODE = "123456" }
    override suspend fun requestVerificationCode(email: String) = AdultOtpChallenge("Código demo generado", VERIFICATION_CODE)
    override suspend fun verifyCode(email: String, code: String) = code == VERIFICATION_CODE
    override suspend fun validateSession() = AdultAccount("demo-adult", "adulto@demo.local", true)
}

class MockLinkRepository(private val profileRepository: ProfileRepository, private val sessionRepository: DeviceSessionRepository) : LinkRepository {
    private companion object { const val LINK_CODE = "654321" }
    private val mockProfile = Profile("Juana", "🦊", 9, schoolSchedules = setOf(SchoolSchedule.MORNING), bedtime = BedtimeRange.FROM_22_TO_23, schoolStart = "08:00", schoolEnd = "12:30", sleepStart = "22:00", sleepEnd = "07:00")
    override suspend fun profiles(): List<Profile> = emptyList()
    override suspend fun findProfile(code: String): Profile? = mockProfile.takeIf { code == LINK_CODE }
    override suspend fun link(profile: Profile) { profileRepository.save(profile); sessionRepository.addLinkedProfile(profile.name) }
}
