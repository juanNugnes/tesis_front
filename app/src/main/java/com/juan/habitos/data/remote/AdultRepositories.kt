package com.juan.habitos.data.remote

import com.juan.habitos.domain.model.AdultAccount
import com.juan.habitos.domain.model.AdultOtpChallenge
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.repository.AdultAuthenticationRequiredException
import com.juan.habitos.domain.repository.AdultAuthorizationException
import com.juan.habitos.domain.repository.AdultCredentialStore
import com.juan.habitos.domain.repository.AdultLinkException
import com.juan.habitos.domain.repository.AdultLinkFailureReason
import com.juan.habitos.domain.repository.AuthRepository
import com.juan.habitos.domain.repository.DeviceSessionRepository
import com.juan.habitos.domain.repository.LinkRepository
import com.juan.habitos.domain.repository.ProfileLocalStore
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

internal suspend fun <T> adultAuthenticatedRequest(
    credentialStore: AdultCredentialStore,
    request: suspend (authorization: String) -> T,
): T {
    val token = credentialStore.adultAccessToken.first()
        ?: throw AdultAuthenticationRequiredException()
    return try {
        request("Bearer $token")
    } catch (exception: HttpException) {
        when (exception.code()) {
            401 -> {
                credentialStore.clearAdultAuthentication()
                throw AdultAuthenticationRequiredException()
            }
            403 -> throw AdultAuthorizationException()
            else -> throw exception
        }
    }
}

class RemoteAdultAuthRepository(
    private val api: HabitsApi,
    private val credentialStore: AdultCredentialStore,
) : AuthRepository {
    override suspend fun requestVerificationCode(email: String): AdultOtpChallenge {
        val response = api.requestAdultOtp(AdultOtpRequest(email))
        return AdultOtpChallenge(response.message, response.devCode)
    }

    override suspend fun verifyCode(email: String, code: String): Boolean = try {
        val response = api.verifyAdultOtp(AdultOtpVerifyRequest(email, code))
        credentialStore.saveAdultAccessToken(response.accessToken)
        true
    } catch (exception: HttpException) {
        if (exception.code() in setOf(400, 401, 404, 410, 422)) false else throw exception
    }

    override suspend fun validateSession(): AdultAccount = adultAuthenticatedRequest(credentialStore) { authorization ->
        api.getAdultMe(authorization)
    }.toDomain()
}

class RemoteLinkRepository(
    private val api: HabitsApi,
    private val sessionStore: DeviceSessionRepository,
    private val profileStore: ProfileLocalStore,
) : LinkRepository {
    override suspend fun profiles(): List<Profile> = loadProfiles()

    override suspend fun findProfile(code: String): Profile? {
        val claimed = try {
            adultAuthenticatedRequest(sessionStore) { authorization ->
                api.claimAdultProfileLink(authorization, AdultProfileLinkClaimRequest(code))
            }
        } catch (exception: HttpException) {
            when (exception.code()) {
                409 -> throw AdultLinkException(AdultLinkFailureReason.REVOKED_LINK, "Revoked adult profile link")
                400, 404, 410, 422 -> throw AdultLinkException(AdultLinkFailureReason.INVALID_OR_EXPIRED_CODE, "Invalid or expired adult profile link")
                else -> throw exception
            }
        }
        val claimedProfile = claimed.profile.toDomain()
        profileStore.save(claimedProfile)
        val refreshed = loadProfiles()
        return refreshed.firstOrNull { it.id == claimedProfile.id }
            ?: throw IllegalStateException("Claimed profile is missing from adult profile list")
    }

    override suspend fun link(profile: Profile) {
        profileStore.save(profile)
        sessionStore.addLinkedProfile(profile.id ?: profile.name)
    }

    private suspend fun loadProfiles(): List<Profile> = adultAuthenticatedRequest(sessionStore) { authorization ->
        api.getAdultProfiles(authorization)
    }.map(ProfileDetailDto::toDomain).distinctBy { it.id }
}

private fun AdultDto.toDomain() = AdultAccount(id = id, email = email, active = active)
