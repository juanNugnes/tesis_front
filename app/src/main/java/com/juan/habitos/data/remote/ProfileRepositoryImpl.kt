package com.juan.habitos.data.remote

import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.model.SchoolSchedule
import com.juan.habitos.domain.repository.ProfileLocalStore
import com.juan.habitos.domain.repository.ProfileRepository
import com.juan.habitos.domain.repository.DeviceCredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class CachedProfileRepository(
    private val local: ProfileLocalStore,
    private val api: HabitsApi,
    private val credentialStore: DeviceCredentialStore,
) : ProfileRepository {
    override val profile: Flow<Profile?> = local.profile
    override val onboardingComplete: Flow<Boolean> = local.onboardingComplete

    override suspend fun save(profile: Profile) = local.save(profile)
    override suspend fun completeOnboarding() = local.completeOnboarding()

    override suspend fun create(profile: Profile): Profile {
        val response = api.createProfile(profile.toCreateProfileRequest())
        val remote = response.toDomain(profile)
        local.save(remote)
        credentialStore.saveBootstrap(response.bootstrapToken, response.bootstrapExpiresAt)
        return remote
    }

    override suspend fun refresh(): Profile? {
        val cached = local.profile.first() ?: return null
        val profileId = cached.id ?: return cached
        val remote = deviceAuthenticatedRequest(credentialStore) { authorization ->
            api.getProfile(profileId, authorization)
        }.toDomain(cached)
        local.save(remote)
        return remote
    }

    override suspend fun update(profile: Profile): Profile {
        val cached = local.profile.first()
        val profileId = profile.id ?: cached?.id
        if (profileId == null) {
            local.save(profile)
            return profile
        }
        val candidate = profile.copy(id = profileId)
        val remote = deviceAuthenticatedRequest(credentialStore) { authorization ->
            api.updateProfile(profileId, authorization, candidate.toUpdateProfileRequest())
        }.toDomain(candidate)
        local.save(remote)
        return remote
    }

    override suspend fun refreshSettings(): Profile? {
        val cached = local.profile.first() ?: return null
        val profileId = cached.id ?: return cached
        val refreshed = cached.withSettings(deviceAuthenticatedRequest(credentialStore) { authorization ->
            api.getProfileSettings(profileId, authorization)
        })
        local.save(refreshed)
        return refreshed
    }

    override suspend fun updateSettings(profile: Profile): Profile {
        val cached = local.profile.first()
        val profileId = cached?.id
        val candidate = (cached ?: profile).copy(
            schoolStart = profile.schoolStart,
            schoolEnd = profile.schoolEnd,
            sleepStart = profile.sleepStart,
            sleepEnd = profile.sleepEnd,
            id = profileId,
        )
        if (profileId == null) {
            local.save(candidate)
            return candidate
        }
        val remote = candidate.withSettings(deviceAuthenticatedRequest(credentialStore) { authorization ->
            api.updateProfileSettings(profileId, authorization, candidate.toSettingsRequest())
        })
        local.save(remote)
        return remote
    }
}

internal fun Profile.toCreateProfileRequest(): CreateProfileRequest {
    val schoolRange = schoolSchedules.toSchoolRange()
    val settings = ProfileSettingsRequest(
        schoolStart = normalizeProfileTime(schoolStart) ?: schoolRange?.first,
        schoolEnd = normalizeProfileTime(schoolEnd) ?: schoolRange?.second,
        sleepStart = normalizeProfileTime(sleepStart),
        sleepEnd = normalizeProfileTime(sleepEnd),
    ).takeUnless { it.schoolStart == null && it.schoolEnd == null && it.sleepStart == null && it.sleepEnd == null }
    return CreateProfileRequest(
        alias = name,
        age = age,
        avatar = avatar.toRemoteAvatar(),
        active = active,
        settings = settings,
    )
}

internal fun Profile.toUpdateProfileRequest() = ProfileUpdateRequest(
    alias = name,
    age = age,
    avatar = avatar.toRemoteAvatar(),
    active = active,
)

internal fun ProfileDetailDto.toDomain(localFallback: Profile? = null) = Profile(
    name = alias,
    avatar = avatar.toLocalAvatar(),
    age = age,
    schoolSchedules = localFallback?.schoolSchedules.orEmpty(),
    bedtime = localFallback?.bedtime,
    schoolStart = normalizeProfileTime(settings?.schoolStart) ?: localFallback?.schoolStart,
    schoolEnd = normalizeProfileTime(settings?.schoolEnd) ?: localFallback?.schoolEnd,
    sleepStart = normalizeProfileTime(settings?.sleepStart) ?: localFallback?.sleepStart,
    sleepEnd = normalizeProfileTime(settings?.sleepEnd) ?: localFallback?.sleepEnd,
    id = id,
    active = active,
)

internal fun ProfileBootstrapDto.toDomain(localFallback: Profile? = null) = ProfileDetailDto(
    id = id,
    alias = alias,
    age = age,
    avatar = avatar,
    active = active,
    createdAt = createdAt,
    updatedAt = updatedAt,
    settings = settings,
).toDomain(localFallback)

internal fun Profile.toSettingsRequest(): ProfileSettingsRequest {
    val schoolStart = requireNotNull(normalizeProfileTime(schoolStart)) { "schoolStart must use HH:mm" }
    val schoolEnd = requireNotNull(normalizeProfileTime(schoolEnd)) { "schoolEnd must use HH:mm" }
    val sleepStart = requireNotNull(normalizeProfileTime(sleepStart)) { "sleepStart must use HH:mm" }
    val sleepEnd = requireNotNull(normalizeProfileTime(sleepEnd)) { "sleepEnd must use HH:mm" }
    require(schoolStart != schoolEnd) { "School start and end must be different" }
    require(sleepStart != sleepEnd) { "Sleep start and end must be different" }
    return ProfileSettingsRequest(schoolStart, schoolEnd, sleepStart, sleepEnd)
}

internal fun Profile.withSettings(settings: ProfileSettingsDto): Profile = copy(
    schoolStart = normalizeProfileTime(settings.schoolStart) ?: schoolStart,
    schoolEnd = normalizeProfileTime(settings.schoolEnd) ?: schoolEnd,
    sleepStart = normalizeProfileTime(settings.sleepStart) ?: sleepStart,
    sleepEnd = normalizeProfileTime(settings.sleepEnd) ?: sleepEnd,
)

internal fun normalizeProfileTime(value: String?): String? {
    val parts = value?.trim()?.split(":") ?: return null
    if (parts.size !in 2..3 || parts[0].length != 2 || parts[1].length != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    val second = parts.getOrNull(2)?.substringBefore('.')?.toIntOrNull()
    if (hour !in 0..23 || minute !in 0..59 || (parts.size == 3 && (second == null || second !in 0..59))) return null
    return "%02d:%02d".format(hour, minute)
}

// The onboarding selects school shifts, not exact times. Their displayed bounds are
// converted to one interval spanning every selected shift.
private fun Set<SchoolSchedule>.toSchoolRange(): Pair<String, String>? {
    if (isEmpty()) return null
    val bounds = map {
        when (it) {
            SchoolSchedule.MORNING -> "07:00" to "12:00"
            SchoolSchedule.AFTERNOON -> "12:00" to "18:00"
            SchoolSchedule.EVENING -> "18:00" to "22:00"
        }
    }
    return bounds.minOf { it.first } to bounds.maxOf { it.second }
}

private val avatarIds = mapOf(
    "🦊" to "fox",
    "🐸" to "frog",
    "🐶" to "dog",
    "🐱" to "cat",
    "🐧" to "penguin",
    "🦁" to "lion",
    "🐨" to "koala",
    "🦋" to "butterfly",
)

internal fun String.toRemoteAvatar(): String = avatarIds[this] ?: this
internal fun String.toLocalAvatar(): String = avatarIds.entries.firstOrNull { it.value == this }?.key ?: this
