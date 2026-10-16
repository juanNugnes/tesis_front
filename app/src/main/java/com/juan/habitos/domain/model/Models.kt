package com.juan.habitos.domain.model

import java.time.LocalDate

enum class SchoolSchedule { MORNING, AFTERNOON, EVENING }
enum class BedtimeRange { FROM_20_TO_21, FROM_21_TO_22, FROM_22_TO_23, FROM_23_TO_00, AFTER_00 }
data class Profile(
    val name: String,
    val avatar: String,
    val age: Int,
    val schoolSchedules: Set<SchoolSchedule> = emptySet(),
    val bedtime: BedtimeRange? = null, // Legacy: retained for existing demo/mock profiles.
    val schoolStart: String? = null,
    val schoolEnd: String? = null,
    val sleepStart: String? = null,
    val sleepEnd: String? = null,
    val id: String? = null,
    val active: Boolean = true,
)
enum class UsageMetricStatus { MEASURED, UNAVAILABLE, PERMISSION_DENIED, CAPTURE_ERROR, INCOMPLETE }
enum class UsageCoverageStatus { UNKNOWN, PARTIAL }

data class UsageMetricStatuses(
    val totalUsageMinutes: UsageMetricStatus,
    val sessionCount: UsageMetricStatus,
    val nightUsageMinutes: UsageMetricStatus,
    val maxSessionMinutes: UsageMetricStatus,
    val unlockCount: UsageMetricStatus,
    val applications: UsageMetricStatus,
)

data class AppUsage(
    val name: String,
    val packageName: String,
    val minutes: Int,
    val category: String? = null,
    val icon: String = "📱",
)

data class DailyUsage(
    val currentMinutes: Int?,
    val expectedMinutes: Int? = null,
    val sessionCount: Int?,
    val nightUsageMinutes: Int? = null,
    val maxSessionMinutes: Int? = null,
    val unlockCount: Int? = null,
    val metricStatuses: UsageMetricStatuses = UsageMetricStatuses(
        totalUsageMinutes = if (currentMinutes == null) UsageMetricStatus.UNAVAILABLE else UsageMetricStatus.MEASURED,
        sessionCount = if (sessionCount == null) UsageMetricStatus.UNAVAILABLE else UsageMetricStatus.MEASURED,
        nightUsageMinutes = if (nightUsageMinutes == null) UsageMetricStatus.UNAVAILABLE else UsageMetricStatus.MEASURED,
        maxSessionMinutes = if (maxSessionMinutes == null) UsageMetricStatus.UNAVAILABLE else UsageMetricStatus.MEASURED,
        unlockCount = if (unlockCount == null) UsageMetricStatus.UNAVAILABLE else UsageMetricStatus.MEASURED,
        applications = UsageMetricStatus.UNAVAILABLE,
    ),
    val coverageStatus: UsageCoverageStatus = UsageCoverageStatus.UNKNOWN,
    val periodsUniform: Boolean = true,
    val sourceCount: Int = 1,
    val informationMessage: String? = null,
)

data class UsageOverview(
    val usage: DailyUsage,
    val applications: List<AppUsage>?,
    val applicationsStatus: UsageMetricStatus,
    val nightWindowStart: String? = null,
    val nightWindowEnd: String? = null,
    val recordCount: Int = 1,
)
data class Recommendation(val title: String, val description: String, val activity: String)
data class ActivitySuggestion(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val activityLevel: String,
    val bedtimeSuitable: Boolean,
    val requiresOtherPerson: Boolean,
    val mayRequireDeviceInteraction: Boolean,
)
data class ActivitySuggestionOffer(
    val offerId: String?,
    val activities: List<ActivitySuggestion>,
    val reason: String? = null,
)
data class ActivitySelection(
    val selectionId: String,
    val offerId: String,
    val activityId: String,
    val selectedAtEpochMillis: Long,
)
data class Alert(
    val id: String,
    val type: String,
    val icon: String,
    val title: String,
    val description: String,
    val detail: String,
    val recommendation: String,
)
enum class DashboardPeriod { TODAY, WEEK }

enum class ChildInterest { DRAW_CREATE, STORIES, MUSIC, GAMES_CHALLENGES, OUTDOORS, MOVEMENT, BUILD, WRITE, IMAGINE_ACT }
enum class ChildConsideration { UPPER_BODY_MOBILITY, LOWER_BODY_MOBILITY, VISUAL, AUDITORY, SOUND_SENSITIVITY }
enum class ConsiderationDuration { TEMPORARY, PERMANENT }
data class ConsiderationPreference(
    val durationType: ConsiderationDuration = ConsiderationDuration.PERMANENT,
    val validUntil: LocalDate? = null,
)
enum class ActivityRestriction { RUN_JUMP, STANDING, ARMS_HANDS, VISUAL_PRECISION, SOUNDS, MANY_STIMULI, HIGH_CONCENTRATION, NONE }
data class ChildPreferences(
    val interests: Set<ChildInterest> = emptySet(),
    val considerationsAnswered: Boolean = false,
    val considerations: Map<ChildConsideration, ConsiderationPreference> = emptyMap(),
    val activitiesToAvoid: Set<ActivityRestriction> = emptySet(),
    val additionalNotes: String? = null,
)

enum class ProfileContextType { VACATION, ILLNESS, SCHOOL_ACTIVITY, ROUTINE_CHANGE, OTHER }
data class ProfileContext(
    val id: String? = null,
    val type: ProfileContextType,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val description: String? = null,
)

enum class DeviceMode { CHILD, ADULT }
data class DeviceSession(
    val mode: DeviceMode? = null,
    val adultEmail: String? = null,
    val adultAuthenticated: Boolean = false,
    val linkedProfileKeys: Set<String> = emptySet(),
)

data class DeviceIdentity(
    val installationId: String? = null,
    val deviceId: String? = null,
    val profileId: String? = null,
)

data class DeviceCredentials(
    val bootstrapToken: String? = null,
    val bootstrapExpiresAt: String? = null,
    val accessToken: String? = null,
)

data class AdultAccount(
    val id: String,
    val email: String,
    val active: Boolean,
)

data class AdultOtpChallenge(
    val message: String,
    val devCode: String? = null,
)

data class AdultProfileLinkCode(
    val code: String,
    val expiresAt: String,
    val purpose: String,
)

enum class RepresentativeVerificationStatus { PENDING, APPROVED, REJECTED }
data class RepresentativeVerification(
    val id: String,
    val profileId: String,
    val status: RepresentativeVerificationStatus,
    val verifiedAt: String? = null,
)

enum class MonitoringConsentStatus { ACTIVE, REVOKED }
data class MonitoringConsent(
    val id: String,
    val profileId: String,
    val status: MonitoringConsentStatus,
    val privacyNoticeVersion: String,
    val purposes: Set<String>,
    val grantedAt: String,
    val revokedAt: String? = null,
)

data class RegisteredDevice(
    val id: String,
    val profileId: String,
    val installationId: String,
    val platform: String,
    val active: Boolean,
)

data class DeviceProfileClaim(
    val device: RegisteredDevice,
    val profile: Profile,
)
