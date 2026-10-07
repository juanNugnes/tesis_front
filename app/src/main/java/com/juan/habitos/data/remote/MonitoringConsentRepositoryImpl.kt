package com.juan.habitos.data.remote

import com.juan.habitos.domain.model.MonitoringConsent
import com.juan.habitos.domain.model.MonitoringConsentStatus
import com.juan.habitos.domain.model.RepresentativeVerification
import com.juan.habitos.domain.model.RepresentativeVerificationStatus
import com.juan.habitos.domain.repository.MonitoringConsentRepository
import retrofit2.HttpException

class RemoteMonitoringConsentRepository(
    private val api: HabitsApi,
    private val authorization: RequestAuthorization,
) : MonitoringConsentRepository {
    init {
        require(authorization.principal == RequestPrincipal.ADULT) { "Monitoring consent requires Adult authorization" }
    }

    override suspend fun getRepresentativeVerification(profileId: String): RepresentativeVerification? =
        nullableOnNotFound {
            authorization.execute { header -> api.getRepresentativeVerification(profileId, header) }.toDomain()
        }

    override suspend fun getCurrentConsent(profileId: String): MonitoringConsent? = nullableOnNotFound {
        authorization.execute { header -> api.getCurrentConsent(profileId, header) }.toDomain()
    }

    override suspend fun grantConsent(
        profileId: String,
        privacyNoticeVersion: String,
        purposes: Set<String>,
    ): MonitoringConsent {
        require(privacyNoticeVersion.isNotBlank()) { "privacyNoticeVersion is required" }
        require(purposes == setOf("USAGE_MONITORING")) { "Only USAGE_MONITORING consent is supported" }
        return authorization.execute { header ->
            api.grantConsent(profileId, header, ConsentGrantRequest(privacyNoticeVersion, purposes.sorted()))
        }.toDomain()
    }

    override suspend fun revokeConsent(profileId: String, consentId: String): MonitoringConsent =
        authorization.execute { header -> api.revokeConsent(profileId, consentId, header) }.toDomain()
}

private suspend fun <T> nullableOnNotFound(request: suspend () -> T): T? = try {
    request()
} catch (exception: HttpException) {
    if (exception.code() == 404) null else throw exception
}

internal fun RepresentativeVerificationDto.toDomain() = RepresentativeVerification(
    id = id,
    profileId = profileId,
    status = when (status) {
        "PENDING" -> RepresentativeVerificationStatus.PENDING
        "APPROVED" -> RepresentativeVerificationStatus.APPROVED
        "REJECTED" -> RepresentativeVerificationStatus.REJECTED
        else -> error("Unknown representative verification status: $status")
    },
    verifiedAt = verifiedAt,
)

internal fun ConsentDto.toDomain() = MonitoringConsent(
    id = id,
    profileId = profileId,
    status = when (status) {
        "ACTIVE" -> MonitoringConsentStatus.ACTIVE
        "REVOKED" -> MonitoringConsentStatus.REVOKED
        else -> error("Unknown consent status: $status")
    },
    privacyNoticeVersion = privacyNoticeVersion,
    purposes = purposes.toSet(),
    grantedAt = grantedAt,
    revokedAt = revokedAt,
)
