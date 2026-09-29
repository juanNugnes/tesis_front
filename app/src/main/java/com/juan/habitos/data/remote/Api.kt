package com.juan.habitos.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
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

data class UsageDto(val profileId: String, val currentMinutes: Int, val expectedMinutes: Int)
data class AnalysisDto(val profileId: String, val summary: String)

interface HabitsApi {
    @POST("api/v1/profiles") suspend fun createProfile(@Body request: CreateProfileRequest): ProfileDetailDto
    @GET("api/v1/profiles/{profileId}") suspend fun getProfile(@Path("profileId") id: String): ProfileDetailDto
    @PUT("api/v1/profiles/{profileId}") suspend fun updateProfile(@Path("profileId") id: String, @Body request: ProfileUpdateRequest): ProfileDetailDto
    @POST("api/v1/profiles/{profileId}/usage") suspend fun postUsage(@Path("profileId") id: String, @Body usage: UsageDto): UsageDto
    @GET("api/v1/profiles/{profileId}/usage") suspend fun getUsage(@Path("profileId") id: String): UsageDto
    @POST("api/v1/profiles/{profileId}/analysis") suspend fun postAnalysis(@Path("profileId") id: String): AnalysisDto
    @GET("api/v1/profiles/{profileId}/analysis/latest") suspend fun latestAnalysis(@Path("profileId") id: String): AnalysisDto
}

object HabitsApiClient {
    private const val EMULATOR_BASE_URL = "http://10.0.2.2:8000/"
    val api: HabitsApi by lazy {
        Retrofit.Builder()
            .baseUrl(EMULATOR_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HabitsApi::class.java)
    }
}
