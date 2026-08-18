package com.juan.habitos.domain.model

enum class SchoolSchedule { MORNING, AFTERNOON, EVENING }
enum class BedtimeRange { FROM_20_TO_21, FROM_21_TO_22, FROM_22_TO_23, FROM_23_TO_00, AFTER_00 }
data class Profile(
    val name: String,
    val avatar: String,
    val age: Int,
    val schoolSchedules: Set<SchoolSchedule> = emptySet(),
    val bedtime: BedtimeRange? = null,
)
data class AppUsage(val name: String, val packageName: String, val minutes: Int, val category: String, val icon: String)
data class DailyUsage(val currentMinutes: Int, val expectedMinutes: Int, val sessionCount: Int)
data class Recommendation(val title: String, val description: String, val activity: String)
data class Alert(val id: String, val title: String)
enum class DashboardPeriod { TODAY, WEEK }
