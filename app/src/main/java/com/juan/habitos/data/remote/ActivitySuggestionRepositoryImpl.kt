package com.juan.habitos.data.remote

import com.juan.habitos.domain.model.ActivitySuggestion
import com.juan.habitos.domain.model.ActivitySuggestionOffer
import com.juan.habitos.domain.model.ActivitySelection
import com.juan.habitos.domain.repository.ActivitySuggestionRepository
import com.juan.habitos.domain.repository.ProfileLocalStore
import kotlinx.coroutines.flow.first

class RemoteActivitySuggestionRepository(
    private val profileStore: ProfileLocalStore,
    private val api: HabitsApi,
    private val authorization: RequestAuthorization,
) : ActivitySuggestionRepository {
    init {
        require(authorization.principal == RequestPrincipal.DEVICE) {
            "Activity suggestions require Device authorization"
        }
    }

    override suspend fun suggestions(): ActivitySuggestionOffer {
        val response = authorization.execute { header ->
            api.getActivitySuggestions(
                profileId = currentProfileId(),
                authorization = header,
                request = ActivitySuggestionsRequest(context = VOLUNTARY_CONTEXT),
            )
        }
        return ActivitySuggestionOffer(
            offerId = response.offerId,
            activities = response.activities.take(MAX_SUGGESTIONS).map { it.toDomain() },
            reason = response.reason,
        )
    }

    override suspend fun registerSelection(selection: ActivitySelection) {
        authorization.execute { header ->
            api.createActivitySelection(
                profileId = currentProfileId(),
                authorization = header,
                request = ActivitySelectionRequest(
                    selectionId = selection.selectionId,
                    offerId = selection.offerId,
                    activityId = selection.activityId,
                    selectedAtEpochMillis = selection.selectedAtEpochMillis,
                ),
            )
        }
    }

    override suspend fun selectionCount(startEpochMillis: Long, endEpochMillis: Long): Int =
        authorization.execute { header ->
            api.getActivitySelectionCount(
                profileId = currentProfileId(),
                authorization = header,
                startEpochMillis = startEpochMillis,
                endEpochMillis = endEpochMillis,
            )
        }.count

    private suspend fun currentProfileId() = requireNotNull(profileStore.profile.first()?.id) {
        "The current profile does not have a backend id"
    }

    private fun ActivitySuggestionDto.toDomain() = ActivitySuggestion(
        id = activityId,
        title = title,
        description = description,
        category = category,
        activityLevel = activityLevel,
        bedtimeSuitable = bedtimeSuitable,
        requiresOtherPerson = requiresOtherPerson,
        mayRequireDeviceInteraction = mayRequireDeviceInteraction,
    )

    private companion object {
        const val VOLUNTARY_CONTEXT = "voluntary"
        const val MAX_SUGGESTIONS = 3
    }
}
