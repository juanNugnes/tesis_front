package com.juan.habitos.data.remote

import com.juan.habitos.domain.model.ProfileContext
import com.juan.habitos.domain.model.ProfileContextType
import com.juan.habitos.domain.repository.ProfileContextLocalStore
import com.juan.habitos.domain.repository.ProfileContextRepository
import com.juan.habitos.domain.repository.ProfileLocalStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.UUID

class CachedProfileContextRepository(
    private val local: ProfileContextLocalStore,
    private val profileStore: ProfileLocalStore,
    private val api: HabitsApi,
    private val remoteEnabled: Boolean,
    private val authorization: RequestAuthorization? = null,
) : ProfileContextRepository {
    override val contexts: Flow<List<ProfileContext>> = local.contexts

    override suspend fun refresh(): List<ProfileContext> {
        val cached = local.contexts.first()
        val profileId = remoteProfileId() ?: return cached
        val remote = adultAuthorization().execute { header -> api.getProfileContexts(profileId, header) }.map(ProfileContextDto::toDomainContext)
        local.save(remote)
        return remote
    }

    override suspend fun getActiveContexts(): List<ProfileContext> {
        val profileId = remoteProfileId() ?: return emptyList()
        return adultAuthorization().execute { header -> api.getActiveProfileContexts(profileId, header) }.map(ProfileContextDto::toDomainContext)
    }

    override suspend fun create(context: ProfileContext): ProfileContext {
        val request = context.toProfileContextRequest()
        val profileId = remoteProfileId()
        val saved = if (profileId == null) {
            context.copy(id = context.id ?: "local-${UUID.randomUUID()}")
        } else {
            adultAuthorization().execute { header -> api.createProfileContext(profileId, header, request) }.toDomainContext()
        }
        local.save((listOf(saved) + local.contexts.first().filterNot { it.id == saved.id }).sortedForDisplay())
        return saved
    }

    override suspend fun update(context: ProfileContext): ProfileContext {
        val contextId = requireNotNull(context.id) { "Cannot update a context without an id" }
        val request = context.toProfileContextRequest()
        val profileId = remoteProfileId()
        val saved = if (profileId == null) context else adultAuthorization().execute { header -> api.updateProfileContext(profileId, contextId, header, request) }.toDomainContext()
        local.save(local.contexts.first().map { existing -> if (existing.id == contextId) saved else existing }.sortedForDisplay())
        return saved
    }

    override suspend fun delete(contextId: String) {
        val profileId = remoteProfileId()
        if (profileId != null) adultAuthorization().execute { header -> api.deleteProfileContext(profileId, contextId, header) }
        local.save(local.contexts.first().filterNot { it.id == contextId })
    }

    private suspend fun remoteProfileId(): String? = if (remoteEnabled) profileStore.profile.first()?.id else null
    private fun adultAuthorization(): RequestAuthorization = requireNotNull(authorization) { "Remote ProfileContext requires Adult authorization" }
        .also { require(it.principal == RequestPrincipal.ADULT) { "ProfileContext is Adult-only" } }
}

internal class IncompatibleProfileContextException(message: String) : IllegalArgumentException(message)

internal fun ProfileContext.toProfileContextRequest(): ProfileContextRequest {
    require(endDate == null || !endDate.isBefore(startDate)) { "endDate must not be before startDate" }
    return ProfileContextRequest(
        type = type.toBackendContextType(),
        startDate = startDate.toString(),
        endDate = endDate?.toString(),
        description = description?.trim()?.takeIf(String::isNotEmpty),
    )
}

internal fun ProfileContextDto.toDomainContext(): ProfileContext {
    val start = parseContextDate(startDate, "start_date")
    val end = endDate?.let { parseContextDate(it, "end_date") }
    if (end != null && end.isBefore(start)) {
        throw IncompatibleProfileContextException("end_date must not be before start_date")
    }
    return ProfileContext(
        id = id,
        type = type.toDomainContextType(),
        startDate = start,
        endDate = end,
        description = description,
    )
}

private fun ProfileContextType.toBackendContextType(): String = when (this) {
    ProfileContextType.VACATION -> "VACATION"
    ProfileContextType.ILLNESS -> "ILLNESS"
    ProfileContextType.SCHOOL_ACTIVITY -> "SCHOOL_ACTIVITY"
    ProfileContextType.ROUTINE_CHANGE -> "ROUTINE_CHANGE"
    ProfileContextType.OTHER -> "OTHER"
}

private fun String.toDomainContextType(): ProfileContextType = when (this) {
    "VACATION" -> ProfileContextType.VACATION
    "ILLNESS" -> ProfileContextType.ILLNESS
    "SCHOOL_ACTIVITY" -> ProfileContextType.SCHOOL_ACTIVITY
    "ROUTINE_CHANGE" -> ProfileContextType.ROUTINE_CHANGE
    "OTHER" -> ProfileContextType.OTHER
    else -> throw IncompatibleProfileContextException("Unknown profile context type: $this")
}

private fun parseContextDate(value: String, field: String): LocalDate = try {
    LocalDate.parse(value)
} catch (exception: Exception) {
    throw IncompatibleProfileContextException("Invalid $field: $value")
}

private fun List<ProfileContext>.sortedForDisplay(): List<ProfileContext> = sortedByDescending(ProfileContext::startDate)
