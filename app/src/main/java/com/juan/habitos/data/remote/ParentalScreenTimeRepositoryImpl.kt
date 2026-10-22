package com.juan.habitos.data.remote

import com.google.gson.annotations.SerializedName
import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.ParentalScreenTimeRepository
import com.juan.habitos.domain.repository.ProfileFormException
import com.juan.habitos.domain.repository.ProfileFormFailure

data class ParentalScreenTimeRequest(
    @SerializedName("sex_code") val sexCode: Int?,
    @SerializedName("weekday_category") val weekdayCategory: Int?,
    @SerializedName("weekend_category") val weekendCategory: Int?,
    val respondent: String = "parent_or_caregiver",
    @SerializedName("reference_days") val referenceDays: Int = 30,
)
data class ParentalScreenTimeResponseDto(
    val id: String,
    @SerializedName("profile_id") val profileId: String,
    val revision: Int, val age: Int,
    @SerializedName("sex_code") val sexCode: Int?,
    @SerializedName("weekday_category") val weekdayCategory: Int?,
    @SerializedName("weekend_category") val weekendCategory: Int?,
    val respondent: String,
    @SerializedName("reference_days") val referenceDays: Int,
)
// Deliberately ignore estimate, including scores and technical model failures. Only response is persisted input.
data class ParentalScreenTimeReviewDto(val response: ParentalScreenTimeResponseDto)

class RemoteParentalScreenTimeRepository(
    private val api: HabitsApi,
    private val authorization: RequestAuthorization,
    private val diagnostics: ProfileFormDiagnostics = ProfileFormDiagnostics(),
) : ParentalScreenTimeRepository {
    init { require(authorization.principal == RequestPrincipal.ADULT) { "Parental answers require Adult authorization" } }
    override suspend fun latest(profileId: String): ParentalScreenTimeRecord? = try {
        authorization.execute { header ->
            profileFormRequest(ProfileFormOperation.PMUM_LATEST, diagnostics) { api.latestParentalScreenTime(profileId, header) }
        }.toRecord(profileId)
    } catch (exception: ProfileFormException) {
        if (exception.reason != ProfileFormFailure.UNCLASSIFIED_NOT_FOUND) throw exception
        // A domain 404 is empty only after verifying that this adult can access this same profile.
        verifyFormProfile(api, authorization, profileId, diagnostics)
        diagnostics.http(ProfileFormOperation.PMUM_LATEST, 404, "no_previous_answers")
        null
    }
    override suspend fun save(profileId: String, answers: ParentalScreenTimeAnswers): ParentalScreenTimeRecord {
        validate(answers)
        return try {
            authorization.execute { header ->
                profileFormRequest(ProfileFormOperation.PMUM_SAVE, diagnostics) {
                    api.saveParentalScreenTime(profileId, header, ParentalScreenTimeRequest(answers.sexCode, answers.weekdayCategory, answers.weekendCategory))
                }
            }.toRecord(profileId)
        } catch (exception: ProfileFormException) {
            if (exception.reason == ProfileFormFailure.UNCLASSIFIED_NOT_FOUND) verifyFormProfile(api, authorization, profileId, diagnostics)
            throw exception
        }
    }
    private fun ParentalScreenTimeReviewDto.toRecord(expectedProfileId: String): ParentalScreenTimeRecord {
        require(response.profileId == expectedProfileId) { "Parental response belongs to another profile" }
        require(response.respondent == "parent_or_caregiver" && response.referenceDays == 30) { "Unsupported parental response format" }
        require(response.id.isNotBlank() && response.revision > 0)
        val answers = ParentalScreenTimeAnswers(response.sexCode, response.weekdayCategory, response.weekendCategory)
        validate(answers)
        return ParentalScreenTimeRecord(response.id, response.profileId, response.revision, response.age, answers)
    }
    private fun validate(answers: ParentalScreenTimeAnswers) {
        require(answers.sexCode == null || answers.sexCode in 1..2)
        require(answers.weekdayCategory == null || answers.weekdayCategory in 1..5)
        require(answers.weekendCategory == null || answers.weekendCategory in 1..5)
    }
}
