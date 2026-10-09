package com.juan.habitos.data.remote

import com.juan.habitos.domain.model.ActivitySuggestion
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

    override suspend fun suggestions(): List<ActivitySuggestion> {
        val profileId = requireNotNull(profileStore.profile.first()?.id) {
            "The current profile does not have a backend id"
        }
        return authorization.execute { header ->
            api.getActivitySuggestions(
                profileId = profileId,
                authorization = header,
                request = ActivitySuggestionsRequest(context = VOLUNTARY_CONTEXT),
            )
        }.activities.take(MAX_SUGGESTIONS).map { it.toDomain() }
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
