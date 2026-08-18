package com.juan.habitos.data.remote

import retrofit2.http.*

data class ProfileDto(val id: String?, val name: String, val avatar: String, val age: Int, val schoolSchedules: List<String> = emptyList(), val bedtime: String? = null)
data class UsageDto(val profileId: String, val currentMinutes: Int, val expectedMinutes: Int)
data class AnalysisDto(val profileId: String, val summary: String)

interface HabitsApi {
    @POST("api/v1/profiles") suspend fun createProfile(@Body profile: ProfileDto): ProfileDto
    @GET("api/v1/profiles/{profileId}") suspend fun getProfile(@Path("profileId") id: String): ProfileDto
    @POST("api/v1/profiles/{profileId}/usage") suspend fun postUsage(@Path("profileId") id: String, @Body usage: UsageDto): UsageDto
    @GET("api/v1/profiles/{profileId}/usage") suspend fun getUsage(@Path("profileId") id: String): UsageDto
    @POST("api/v1/profiles/{profileId}/analysis") suspend fun postAnalysis(@Path("profileId") id: String): AnalysisDto
    @GET("api/v1/profiles/{profileId}/analysis/latest") suspend fun latestAnalysis(@Path("profileId") id: String): AnalysisDto
}
