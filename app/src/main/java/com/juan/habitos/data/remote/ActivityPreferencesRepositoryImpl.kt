package com.juan.habitos.data.remote

import com.juan.habitos.domain.model.ActivityRestriction
import com.juan.habitos.domain.model.AdaptationType
import com.juan.habitos.domain.model.ChildConsideration
import com.juan.habitos.domain.model.ChildInterest
import com.juan.habitos.domain.model.ChildPreferences
import com.juan.habitos.domain.repository.ChildPreferencesLocalStore
import com.juan.habitos.domain.repository.ChildPreferencesRepository
import com.juan.habitos.domain.repository.ProfileLocalStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate

class CachedChildPreferencesRepository(
    private val local: ChildPreferencesLocalStore,
    private val profileStore: ProfileLocalStore,
    private val api: HabitsApi,
) : ChildPreferencesRepository {
    override val preferences: Flow<ChildPreferences> = local.preferences

    override suspend fun save(preferences: ChildPreferences) = local.save(preferences)

    override suspend fun refresh(): ChildPreferences {
        val cached = local.preferences.first()
        val profileId = profileStore.profile.first()?.id ?: return cached
        val remote = api.getActivityPreferences(profileId).toDomainPreferences()
        local.save(remote)
        return remote
    }

    override suspend fun update(preferences: ChildPreferences): ChildPreferences {
        val profileId = profileStore.profile.first()?.id
        if (profileId == null) {
            local.save(preferences)
            return preferences
        }
        val remote = api.updateActivityPreferences(profileId, preferences.toActivityPreferencesRequest()).toDomainPreferences()
        local.save(remote)
        return remote
    }
}

internal class IncompatibleActivityPreferencesException(message: String) : IllegalArgumentException(message)

internal fun ChildPreferences.toActivityPreferencesRequest(): ActivityPreferencesRequest {
    require(additionalNotes == null || additionalNotes.length <= 500) { "additionalNotes exceeds backend limit" }
    return ActivityPreferencesRequest(
        interests = interests.flatMap(::interestToBackend).sorted(),
        adaptationType = adaptationType.toBackend(),
        considerations = considerations.flatMap(::considerationToBackend).sorted(),
        activitiesToAvoid = activitiesToAvoid.flatMap(::restrictionToBackend).distinct().sorted(),
        additionalNotes = additionalNotes?.takeIf { it.isNotBlank() },
        validUntil = validUntil.takeIf { adaptationType == AdaptationType.TEMPORARY }?.toString(),
    )
}

internal fun ActivityPreferencesDto.toDomainPreferences(): ChildPreferences {
    val restrictions = activitiesToAvoid.toDomainRestrictions()
    val adaptation = adaptationType.toDomainAdaptation()
    val endDate = if (adaptation == AdaptationType.TEMPORARY) validUntil?.let(LocalDate::parse) else null
    return ChildPreferences(
        interests = interests.mapTo(mutableSetOf(), String::toDomainInterest),
        adaptationType = adaptation,
        considerations = considerations.mapTo(mutableSetOf(), String::toDomainConsideration),
        activitiesToAvoid = restrictions,
        additionalNotes = additionalNotes,
        validUntil = endDate,
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

private fun considerationToBackend(value: ChildConsideration): List<String> = listOf(when (value) {
    ChildConsideration.MOVEMENT -> "MOBILITY"
    ChildConsideration.VISION -> "VISION"
    ChildConsideration.HEARING -> "HEARING"
    ChildConsideration.ATTENTION -> "ATTENTION_CONCENTRATION"
    ChildConsideration.ARMS_HANDS -> "ARMS_HANDS"
    ChildConsideration.LEGS -> "LEGS"
    ChildConsideration.COMMUNICATION -> "COMMUNICATION"
    ChildConsideration.SENSORY -> "SENSORY_SENSITIVITY"
    ChildConsideration.TEMPORARY_DISCOMFORT -> "TEMPORARY_PHYSICAL_DISCOMFORT"
    ChildConsideration.OTHER -> "OTHER"
})

private fun String.toDomainConsideration(): ChildConsideration = when (this) {
    "MOBILITY" -> ChildConsideration.MOVEMENT
    "VISION" -> ChildConsideration.VISION
    "HEARING" -> ChildConsideration.HEARING
    "ATTENTION_CONCENTRATION" -> ChildConsideration.ATTENTION
    "ARMS_HANDS" -> ChildConsideration.ARMS_HANDS
    "LEGS" -> ChildConsideration.LEGS
    "COMMUNICATION" -> ChildConsideration.COMMUNICATION
    "SENSORY_SENSITIVITY" -> ChildConsideration.SENSORY
    "TEMPORARY_PHYSICAL_DISCOMFORT" -> ChildConsideration.TEMPORARY_DISCOMFORT
    "OTHER" -> ChildConsideration.OTHER
    else -> throw IncompatibleActivityPreferencesException("Unknown consideration: $this")
}

private fun AdaptationType.toBackend(): String = when (this) {
    AdaptationType.NONE -> "NONE"
    AdaptationType.TEMPORARY -> "TEMPORARY"
    AdaptationType.PERMANENT -> "PERMANENT"
}

private fun String.toDomainAdaptation(): AdaptationType = when (this) {
    "NONE" -> AdaptationType.NONE
    "TEMPORARY" -> AdaptationType.TEMPORARY
    "PERMANENT" -> AdaptationType.PERMANENT
    else -> throw IncompatibleActivityPreferencesException("Unknown adaptation type: $this")
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
