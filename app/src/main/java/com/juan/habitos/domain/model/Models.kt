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
data class AppUsage(val name: String, val packageName: String, val minutes: Int, val category: String, val icon: String)
data class DailyUsage(val currentMinutes: Int, val expectedMinutes: Int, val sessionCount: Int)
data class Recommendation(val title: String, val description: String, val activity: String)
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
enum class AdaptationType { NONE, TEMPORARY, PERMANENT }
enum class ChildConsideration { MOVEMENT, VISION, HEARING, ATTENTION, ARMS_HANDS, LEGS, COMMUNICATION, SENSORY, TEMPORARY_DISCOMFORT, OTHER }
enum class ActivityRestriction { RUN_JUMP, STANDING, ARMS_HANDS, VISUAL_PRECISION, SOUNDS, MANY_STIMULI, HIGH_CONCENTRATION, NONE }
data class ChildPreferences(
    val interests: Set<ChildInterest> = emptySet(),
    val adaptationType: AdaptationType = AdaptationType.NONE,
    val considerations: Set<ChildConsideration> = emptySet(),
    val activitiesToAvoid: Set<ActivityRestriction> = emptySet(),
    val additionalNotes: String? = null,
    val validUntil: LocalDate? = null,
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
