package com.juan.habitos.data.remote

import com.juan.habitos.domain.model.ActivityRestriction
import com.juan.habitos.domain.model.ChildConsideration
import com.juan.habitos.domain.model.ChildInterest
import com.juan.habitos.domain.model.ChildPreferences
import com.juan.habitos.domain.model.ConsiderationDuration
import com.juan.habitos.domain.model.ConsiderationPreference
import com.juan.habitos.domain.repository.ChildPreferencesLocalStore
import com.juan.habitos.domain.repository.ChildPreferencesRepository
import com.juan.habitos.domain.repository.ProfileLocalStore
import com.juan.habitos.domain.repository.DeviceCredentialStore
import com.juan.habitos.domain.repository.ProfileFormException
import com.juan.habitos.domain.repository.ProfileFormFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate

class CachedChildPreferencesRepository(
    private val local: ChildPreferencesLocalStore,
    private val profileStore: ProfileLocalStore,
    private val api: HabitsApi,
    private val authorization: RequestAuthorization,
    private val expectedProfileId: String? = null,
    private val diagnostics: ProfileFormDiagnostics = ProfileFormDiagnostics(),
) : ChildPreferencesRepository {
    constructor(local: ChildPreferencesLocalStore, profileStore: ProfileLocalStore, api: HabitsApi, credentialStore: DeviceCredentialStore) :
        this(local, profileStore, api, DeviceRequestAuthorization(credentialStore))
    override val preferences: Flow<ChildPreferences> = local.preferences

    override suspend fun save(preferences: ChildPreferences) = local.save(preferences)

    override suspend fun refresh(): ChildPreferences {
        val cached = local.preferences.first()
        val profileId = checkedProfileId() ?: return cached
        val remote = authorization.execute { header ->
            request(profileId, ProfileFormOperation.PREFERENCES_READ) { api.getActivityPreferences(profileId, header) }
        }.toDomainPreferences()
        local.save(remote)
        return remote
    }

    override suspend fun update(preferences: ChildPreferences): ChildPreferences {
        val profileId = checkedProfileId()
        if (profileId == null) {
            local.save(preferences)
            return preferences
        }
        val remote = authorization.execute { header ->
            request(profileId, ProfileFormOperation.PREFERENCES_SAVE) { api.updateActivityPreferences(profileId, header, preferences.toActivityPreferencesRequest()) }
        }.toDomainPreferences()
        local.save(remote)
        return remote
    }

    private suspend fun checkedProfileId(): String? {
        val current = profileStore.profile.first()?.id
        if (expectedProfileId != null) {
            diagnostics.profileMatch(current == expectedProfileId)
            if (current != expectedProfileId) throw ProfileFormException(ProfileFormFailure.PROFILE_CHANGED)
        }
        return current
    }

    private suspend fun request(profileId: String, operation: ProfileFormOperation, call: suspend () -> ActivityPreferencesDto): ActivityPreferencesDto {
        val dto = try { profileFormRequest(operation, diagnostics, call) }
        catch (exception: ProfileFormException) {
            if (exception.reason == ProfileFormFailure.UNCLASSIFIED_NOT_FOUND) verifyFormProfile(api, authorization, profileId, diagnostics)
            throw exception
        }
        if (dto.profileId != null && dto.profileId != profileId) throw ProfileFormException(ProfileFormFailure.PROFILE_CHANGED)
        return dto
    }
}

internal class IncompatibleActivityPreferencesException(message: String) : IllegalArgumentException(message)

internal fun ChildPreferences.toActivityPreferencesRequest(): ActivityPreferencesRequest {
    require(additionalNotes == null || additionalNotes.length <= 500) { "additionalNotes exceeds backend limit" }
    require(considerationsAnswered || considerations.isEmpty()) { "Unanswered considerations must be empty" }
    return ActivityPreferencesRequest(
        interests = interests.flatMap(::interestToBackend).sorted(),
        considerationsAnswered = considerationsAnswered,
        considerations = considerations.entries.sortedBy { it.key.name }.map { (type, preference) ->
            require(preference.durationType != ConsiderationDuration.TEMPORARY || preference.validUntil != null) {
                "Temporary consideration requires validUntil"
            }
            ActivityConsiderationDto(
                type = type.toBackend(),
                durationType = preference.durationType.name,
                validUntil = preference.validUntil
                    .takeIf { preference.durationType == ConsiderationDuration.TEMPORARY }
                    ?.toString(),
            )
        },
        activitiesToAvoid = activitiesToAvoid.flatMap(::restrictionToBackend).distinct().sorted(),
        additionalNotes = additionalNotes?.takeIf { it.isNotBlank() },
    )
}

internal fun ActivityPreferencesDto.toDomainPreferences(): ChildPreferences {
    val restrictions = activitiesToAvoid.toDomainRestrictions()
    if (!considerationsAnswered && considerations.isNotEmpty()) {
        throw IncompatibleActivityPreferencesException("Unanswered considerations must be empty")
    }
    val mappedConsiderations = considerations.associate { dto ->
        val type = dto.type.toDomainConsideration()
        val duration = dto.durationType.toDomainDuration()
        val validUntil = when (duration) {
            ConsiderationDuration.TEMPORARY -> dto.validUntil?.let(LocalDate::parse)
                ?: throw IncompatibleActivityPreferencesException("Temporary consideration requires valid_until")
            ConsiderationDuration.PERMANENT -> null
        }
        type to ConsiderationPreference(duration, validUntil)
    }
    if (mappedConsiderations.size != considerations.size) {
        throw IncompatibleActivityPreferencesException("Duplicate consideration type")
    }
    return ChildPreferences(
        interests = interests.mapTo(mutableSetOf(), String::toDomainInterest),
        considerationsAnswered = considerationsAnswered,
        considerations = mappedConsiderations,
        activitiesToAvoid = restrictions,
        additionalNotes = additionalNotes,
    )
}

private fun interestToBackend(value: ChildInterest): List<String> = listOf(when (value) {
    ChildInterest.DRAW_CREATE -> "DRAWING_CREATING"
    ChildInterest.STORIES -> "READING_STORIES"
    ChildInterest.MUSIC -> "MUSIC"
    ChildInterest.GAMES_CHALLENGES -> "GAMES_CHALLENGES"
    ChildInterest.OUTDOORS -> "OUTDOORS"
    ChildInterest.MOVEMENT -> "MOVEMENT"
    ChildInterest.BUILD -> "BUILDING"
    ChildInterest.WRITE -> "WRITING"
    ChildInterest.IMAGINE_ACT -> "IMAGINATIVE_PLAY"
})

private fun String.toDomainInterest(): ChildInterest = when (this) {
    "DRAWING_CREATING" -> ChildInterest.DRAW_CREATE
    "READING_STORIES" -> ChildInterest.STORIES
    "MUSIC" -> ChildInterest.MUSIC
    "GAMES_CHALLENGES" -> ChildInterest.GAMES_CHALLENGES
    "OUTDOORS" -> ChildInterest.OUTDOORS
    "MOVEMENT" -> ChildInterest.MOVEMENT
    "BUILDING" -> ChildInterest.BUILD
    "WRITING" -> ChildInterest.WRITE
    "IMAGINATIVE_PLAY" -> ChildInterest.IMAGINE_ACT
    else -> throw IncompatibleActivityPreferencesException("Unknown interest: $this")
}

private fun ChildConsideration.toBackend(): String = when (this) {
    ChildConsideration.UPPER_BODY_MOBILITY -> "UPPER_BODY_MOBILITY"
    ChildConsideration.LOWER_BODY_MOBILITY -> "LOWER_BODY_MOBILITY"
    ChildConsideration.VISUAL -> "VISUAL"
    ChildConsideration.AUDITORY -> "AUDITORY"
    ChildConsideration.SOUND_SENSITIVITY -> "SOUND_SENSITIVITY"
}

private fun String.toDomainConsideration(): ChildConsideration = when (this) {
    "UPPER_BODY_MOBILITY" -> ChildConsideration.UPPER_BODY_MOBILITY
    "LOWER_BODY_MOBILITY" -> ChildConsideration.LOWER_BODY_MOBILITY
    "VISUAL" -> ChildConsideration.VISUAL
    "AUDITORY" -> ChildConsideration.AUDITORY
    "SOUND_SENSITIVITY" -> ChildConsideration.SOUND_SENSITIVITY
    else -> throw IncompatibleActivityPreferencesException("Unknown consideration: $this")
}

private fun String.toDomainDuration(): ConsiderationDuration = when (this) {
    "TEMPORARY" -> ConsiderationDuration.TEMPORARY
    "PERMANENT" -> ConsiderationDuration.PERMANENT
    else -> throw IncompatibleActivityPreferencesException("Unknown consideration duration: $this")
}

private fun restrictionToBackend(value: ActivityRestriction): List<String> = when (value) {
    ActivityRestriction.RUN_JUMP -> listOf("RUNNING", "JUMPING")
    ActivityRestriction.STANDING -> listOf("PROLONGED_STANDING")
    ActivityRestriction.ARMS_HANDS -> listOf("ARMS_HANDS_USE")
    ActivityRestriction.VISUAL_PRECISION -> listOf("VISUAL_PRECISION")
    ActivityRestriction.SOUNDS -> listOf("LOUD_SOUNDS")
    ActivityRestriction.MANY_STIMULI -> listOf("HIGH_STIMULATION")
    ActivityRestriction.HIGH_CONCENTRATION -> listOf("PROLONGED_CONCENTRATION")
    ActivityRestriction.NONE -> emptyList()
}

private fun List<String>.toDomainRestrictions(): Set<ActivityRestriction> {
    val hasRunning = "RUNNING" in this
    val hasJumping = "JUMPING" in this
    if (hasRunning != hasJumping) {
        throw IncompatibleActivityPreferencesException("RUNNING and JUMPING must be returned together")
    }
    if (isEmpty()) return setOf(ActivityRestriction.NONE)
    return mapNotNullTo(mutableSetOf()) { value ->
        when (value) {
            "RUNNING", "JUMPING" -> ActivityRestriction.RUN_JUMP.takeIf { hasRunning && hasJumping }
            "PROLONGED_STANDING" -> ActivityRestriction.STANDING
            "ARMS_HANDS_USE" -> ActivityRestriction.ARMS_HANDS
            "VISUAL_PRECISION" -> ActivityRestriction.VISUAL_PRECISION
            "LOUD_SOUNDS" -> ActivityRestriction.SOUNDS
            "HIGH_STIMULATION" -> ActivityRestriction.MANY_STIMULI
            "PROLONGED_CONCENTRATION" -> ActivityRestriction.HIGH_CONCENTRATION
            else -> throw IncompatibleActivityPreferencesException("Unknown activity restriction: $value")
        }
    }
}
