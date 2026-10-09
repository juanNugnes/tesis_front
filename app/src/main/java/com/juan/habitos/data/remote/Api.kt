package com.juan.habitos.data.remote

import com.google.gson.annotations.SerializedName
import com.juan.habitos.BuildConfig
import retrofit2.Retrofit
import retrofit2.Response
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

data class ProfileSettingsRequest(
    @SerializedName("school_start") val schoolStart: String? = null,
    @SerializedName("school_end") val schoolEnd: String? = null,
    @SerializedName("sleep_start") val sleepStart: String? = null,
    @SerializedName("sleep_end") val sleepEnd: String? = null,
)

data class CreateProfileRequest(
    val alias: String,
    val age: Int,
    val avatar: String,
    val active: Boolean = true,
    val settings: ProfileSettingsRequest? = null,
)

data class ProfileUpdateRequest(
    val alias: String? = null,
    val age: Int? = null,
    val avatar: String? = null,
    val active: Boolean? = null,
)

data class ProfileSettingsDto(
    @SerializedName("profile_id") val profileId: String? = null,
    @SerializedName("school_start") val schoolStart: String? = null,
    @SerializedName("school_end") val schoolEnd: String? = null,
    @SerializedName("sleep_start") val sleepStart: String? = null,
    @SerializedName("sleep_end") val sleepEnd: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
)

data class ActivityPreferencesRequest(
    val interests: List<String>,
    @SerializedName("adaptation_type") val adaptationType: String,
    val considerations: List<String>,
    @SerializedName("activities_to_avoid") val activitiesToAvoid: List<String>,
    @SerializedName("additional_notes") val additionalNotes: String?,
    @SerializedName("valid_until") val validUntil: String?,
)

data class ActivityPreferencesDto(
    val interests: List<String> = emptyList(),
    @SerializedName("adaptation_type") val adaptationType: String = "NONE",
    val considerations: List<String> = emptyList(),
    @SerializedName("activities_to_avoid") val activitiesToAvoid: List<String> = emptyList(),
    @SerializedName("additional_notes") val additionalNotes: String? = null,
    @SerializedName("valid_until") val validUntil: String? = null,
    val id: String? = null,
    @SerializedName("profile_id") val profileId: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
)

data class ActivitySuggestionsRequest(
    val context: String,
)

data class ActivitySuggestionDto(
    @SerializedName("activity_id") val activityId: String,
    val title: String,
    val description: String,
    val category: String,
    @SerializedName("activity_level") val activityLevel: String,
    @SerializedName("bedtime_suitable") val bedtimeSuitable: Boolean,
    @SerializedName("requires_other_person") val requiresOtherPerson: Boolean,
    @SerializedName("may_require_device_interaction") val mayRequireDeviceInteraction: Boolean,
)

data class ActivitySuggestionsResponseDto(
    @SerializedName("offer_id") val offerId: String? = null,
    val context: String,
    val activities: List<ActivitySuggestionDto>,
)

data class ActivitySelectionRequest(
    @SerializedName("selection_id") val selectionId: String,
    @SerializedName("offer_id") val offerId: String,
    @SerializedName("activity_id") val activityId: String,
    @SerializedName("selected_at_epoch_millis") val selectedAtEpochMillis: Long,
)

data class ActivitySelectionResponseDto(
    @SerializedName("selection_id") val selectionId: String? = null,
    @SerializedName("offer_id") val offerId: String? = null,
    @SerializedName("activity_id") val activityId: String? = null,
    @SerializedName("selected_at_epoch_millis") val selectedAtEpochMillis: Long? = null,
)

data class ProfileContextRequest(
    val type: String,
    @SerializedName("start_date") val startDate: String,
    @SerializedName("end_date") val endDate: String?,
    val description: String?,
)

data class ProfileContextDto(
    val id: String,
    @SerializedName("profile_id") val profileId: String,
    val type: String,
    @SerializedName("start_date") val startDate: String,
    @SerializedName("end_date") val endDate: String? = null,
    val description: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
)

data class ProfileDetailDto(
    val id: String,
    val alias: String,
    val age: Int,
    val avatar: String,
    val active: Boolean,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    val settings: ProfileSettingsDto? = null,
)

data class ProfileBootstrapDto(
    val id: String,
    val alias: String,
    val age: Int,
    val avatar: String,
    val active: Boolean,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    val settings: ProfileSettingsDto? = null,
    @SerializedName("bootstrap_token") val bootstrapToken: String,
    @SerializedName("bootstrap_expires_at") val bootstrapExpiresAt: String,
)

data class DeviceCreateRequest(
    @SerializedName("installation_id") val installationId: String,
    val platform: String = "ANDROID",
)

data class DeviceDto(
    val id: String,
    @SerializedName("profile_id") val profileId: String,
    @SerializedName("installation_id") val installationId: String,
    val platform: String,
    val active: Boolean,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("last_seen_at") val lastSeenAt: String? = null,
)

data class DeviceRegistrationDto(
    val id: String,
    @SerializedName("profile_id") val profileId: String,
    @SerializedName("installation_id") val installationId: String,
    val platform: String,
    val active: Boolean,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("last_seen_at") val lastSeenAt: String? = null,
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String = "bearer",
    @SerializedName("expires_in") val expiresIn: Int,
)

data class DeviceLinkClaimRequest(
    val code: String,
    @SerializedName("installation_id") val installationId: String,
    val platform: String = "ANDROID",
)

data class DeviceLinkClaimResponse(
    val device: DeviceDto,
    val profile: ProfileDetailDto,
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String = "bearer",
    @SerializedName("expires_in") val expiresIn: Int,
)

data class DeviceLinkCodeDto(
    val code: String,
    @SerializedName("expires_at") val expiresAt: String,
    val purpose: String,
)

data class AdultOtpRequest(
    val email: String,
)

data class AdultOtpRequestResponse(
    val message: String,
    @SerializedName("dev_code") val devCode: String? = null,
)

data class AdultOtpVerifyRequest(
    val email: String,
    val code: String,
)

data class AdultAccessTokenDto(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String = "bearer",
    @SerializedName("expires_in") val expiresIn: Int,
)

data class AdultDto(
    val id: String,
    val email: String,
    val active: Boolean,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("last_login_at") val lastLoginAt: String? = null,
)

data class AdultProfileLinkClaimRequest(
    val code: String,
)

data class AdultProfileLinkDto(
    val id: String,
    @SerializedName("profile_id") val profileId: String,
    val active: Boolean,
    @SerializedName("created_at") val createdAt: String,
)

data class AdultProfileLinkClaimResponse(
    val profile: ProfileDetailDto,
    val link: AdultProfileLinkDto,
)

data class AdultProfileLinkCodeDto(
    val code: String,
    @SerializedName("expires_at") val expiresAt: String,
    val purpose: String,
)

data class RepresentativeVerificationDto(
    val id: String,
    @SerializedName("profile_id") val profileId: String,
    val status: String,
    @SerializedName("verified_at") val verifiedAt: String? = null,
    @SerializedName("verified_by") val verifiedBy: String? = null,
    val notes: String? = null,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
)

data class ConsentGrantRequest(
    @SerializedName("privacy_notice_version") val privacyNoticeVersion: String,
    val purposes: List<String>,
)

data class ConsentDto(
    val id: String,
    @SerializedName("profile_id") val profileId: String,
    val status: String,
    @SerializedName("privacy_notice_version") val privacyNoticeVersion: String,
    val purposes: List<String>,
    @SerializedName("granted_at") val grantedAt: String,
    @SerializedName("revoked_at") val revokedAt: String? = null,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
)

data class UsageDto(val profileId: String, val currentMinutes: Int, val expectedMinutes: Int)
data class AnalysisDto(val profileId: String, val summary: String)

interface HabitsApi {
    @POST("api/v1/auth/adult/otp/request") suspend fun requestAdultOtp(@Body request: AdultOtpRequest): AdultOtpRequestResponse
    @POST("api/v1/auth/adult/otp/verify") suspend fun verifyAdultOtp(@Body request: AdultOtpVerifyRequest): AdultAccessTokenDto
    @GET("api/v1/auth/adult/me") suspend fun getAdultMe(@Header("Authorization") authorization: String): AdultDto
    @GET("api/v1/adult/profiles") suspend fun getAdultProfiles(@Header("Authorization") authorization: String): List<ProfileDetailDto>
    @POST("api/v1/adult/profile-links/claim") suspend fun claimAdultProfileLink(@Header("Authorization") authorization: String, @Body request: AdultProfileLinkClaimRequest): AdultProfileLinkClaimResponse
    @POST("api/v1/profiles/{profileId}/adult-link-codes") suspend fun createAdultProfileLinkCode(@Path("profileId") profileId: String, @Header("Authorization") authorization: String): AdultProfileLinkCodeDto
    @GET("api/v1/profiles/{profileId}/representative-verification") suspend fun getRepresentativeVerification(@Path("profileId") profileId: String, @Header("Authorization") authorization: String): RepresentativeVerificationDto
    @POST("api/v1/profiles/{profileId}/consents") suspend fun grantConsent(@Path("profileId") profileId: String, @Header("Authorization") authorization: String, @Body request: ConsentGrantRequest): ConsentDto
    @GET("api/v1/profiles/{profileId}/consents/current") suspend fun getCurrentConsent(@Path("profileId") profileId: String, @Header("Authorization") authorization: String): ConsentDto
    @POST("api/v1/profiles/{profileId}/consents/{consentId}/revoke") suspend fun revokeConsent(@Path("profileId") profileId: String, @Path("consentId") consentId: String, @Header("Authorization") authorization: String): ConsentDto
    @POST("api/v1/profiles") suspend fun createProfile(@Body request: CreateProfileRequest): ProfileBootstrapDto
    @GET("api/v1/profiles/{profileId}") suspend fun getProfile(@Path("profileId") id: String, @Header("Authorization") authorization: String): ProfileDetailDto
    @PUT("api/v1/profiles/{profileId}") suspend fun updateProfile(@Path("profileId") id: String, @Header("Authorization") authorization: String, @Body request: ProfileUpdateRequest): ProfileDetailDto
    @GET("api/v1/profiles/{profileId}/settings") suspend fun getProfileSettings(@Path("profileId") id: String, @Header("Authorization") authorization: String): ProfileSettingsDto
    @PUT("api/v1/profiles/{profileId}/settings") suspend fun updateProfileSettings(@Path("profileId") id: String, @Header("Authorization") authorization: String, @Body request: ProfileSettingsRequest): ProfileSettingsDto
    @GET("api/v1/profiles/{profileId}/activity-preferences") suspend fun getActivityPreferences(@Path("profileId") id: String, @Header("Authorization") authorization: String): ActivityPreferencesDto
    @PUT("api/v1/profiles/{profileId}/activity-preferences") suspend fun updateActivityPreferences(@Path("profileId") id: String, @Header("Authorization") authorization: String, @Body request: ActivityPreferencesRequest): ActivityPreferencesDto
    @POST("api/v1/profiles/{profileId}/activity-suggestions")
    suspend fun getActivitySuggestions(
        @Path("profileId") profileId: String,
        @Header("Authorization") authorization: String,
        @Body request: ActivitySuggestionsRequest,
    ): ActivitySuggestionsResponseDto = error("Not implemented")
    @POST("api/v1/profiles/{profileId}/activity-selections")
    suspend fun createActivitySelection(
        @Path("profileId") profileId: String,
        @Header("Authorization") authorization: String,
        @Body request: ActivitySelectionRequest,
    ): ActivitySelectionResponseDto = error("Not implemented")
    @POST("api/v1/profiles/{profileId}/contexts") suspend fun createProfileContext(@Path("profileId") profileId: String, @Header("Authorization") authorization: String, @Body request: ProfileContextRequest): ProfileContextDto
    @GET("api/v1/profiles/{profileId}/contexts") suspend fun getProfileContexts(@Path("profileId") profileId: String, @Header("Authorization") authorization: String): List<ProfileContextDto>
    @GET("api/v1/profiles/{profileId}/contexts/active") suspend fun getActiveProfileContexts(@Path("profileId") profileId: String, @Header("Authorization") authorization: String): List<ProfileContextDto>
    @PUT("api/v1/profiles/{profileId}/contexts/{contextId}") suspend fun updateProfileContext(@Path("profileId") profileId: String, @Path("contextId") contextId: String, @Header("Authorization") authorization: String, @Body request: ProfileContextRequest): ProfileContextDto
    @DELETE("api/v1/profiles/{profileId}/contexts/{contextId}") suspend fun deleteProfileContext(@Path("profileId") profileId: String, @Path("contextId") contextId: String, @Header("Authorization") authorization: String)
    @POST("api/v1/profiles/{profileId}/devices") suspend fun registerDevice(@Path("profileId") profileId: String, @Header("Authorization") authorization: String, @Body request: DeviceCreateRequest): Response<DeviceRegistrationDto>
    @POST("api/v1/profiles/{profileId}/device-link-codes") suspend fun createDeviceLinkCode(@Path("profileId") profileId: String): Response<DeviceLinkCodeDto>
    @POST("api/v1/device-links/claim") suspend fun claimDeviceLink(@Body request: DeviceLinkClaimRequest): Response<DeviceLinkClaimResponse>
    @POST("api/v1/profiles/{profileId}/usage") suspend fun postUsage(@Path("profileId") id: String, @Body usage: UsageDto): UsageDto
    @GET("api/v1/profiles/{profileId}/usage") suspend fun getUsage(@Path("profileId") id: String): UsageDto
    @POST("api/v1/profiles/{profileId}/analysis") suspend fun postAnalysis(@Path("profileId") id: String): AnalysisDto
    @GET("api/v1/profiles/{profileId}/analysis/latest") suspend fun latestAnalysis(@Path("profileId") id: String): AnalysisDto
}

object HabitsApiClient {
    val api: HabitsApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HabitsApi::class.java)
    }
}
