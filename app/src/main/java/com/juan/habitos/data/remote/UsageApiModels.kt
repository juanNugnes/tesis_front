package com.juan.habitos.data.remote

import com.google.gson.annotations.SerializedName

data class UsageMetricStatusesDto(
    @SerializedName("total_usage_minutes") val totalUsageMinutes: String,
    @SerializedName("session_count") val sessionCount: String,
    @SerializedName("night_usage_minutes") val nightUsageMinutes: String,
    @SerializedName("max_session_minutes") val maxSessionMinutes: String,
    @SerializedName("unlock_count") val unlockCount: String,
    val applications: String,
)

data class NightWindowDto(
    val source: String = "PROFILE_SLEEP_SCHEDULE",
    @SerializedName("start_local_time") val startLocalTime: String,
    @SerializedName("end_local_time") val endLocalTime: String,
)

data class AppUsageCreateDto(
    @SerializedName("package_name") val packageName: String,
    @SerializedName("app_name") val appName: String?,
    val category: String? = null,
    @SerializedName("usage_minutes") val usageMinutes: Int,
)

data class AppUsageReadDto(
    val id: String? = null,
    @SerializedName("daily_usage_id") val dailyUsageId: String? = null,
    @SerializedName("package_name") val packageName: String,
    @SerializedName("app_name") val appName: String?,
    val category: String? = null,
    @SerializedName("usage_minutes") val usageMinutes: Int,
)

data class UsageSnapshotRequest(
    @SerializedName("snapshot_id") val snapshotId: String,
    @SerializedName("captured_at") val capturedAt: String,
    val date: String,
    @SerializedName("period_start_at") val periodStartAt: String,
    @SerializedName("period_end_at") val periodEndAt: String,
    val timezone: String,
    @SerializedName("duration_unit") val durationUnit: String = "MINUTES",
    @SerializedName("measurement_method") val measurementMethod: String = "ANDROID_USAGE_STATS_AGGREGATES",
    @SerializedName("calculation_version") val calculationVersion: String = "android-collector-1.0",
    @SerializedName("coverage_status") val coverageStatus: String,
    @SerializedName("total_usage_minutes") val totalUsageMinutes: Int?,
    @SerializedName("session_count") val sessionCount: Int?,
    @SerializedName("night_usage_minutes") val nightUsageMinutes: Int?,
    @SerializedName("max_session_minutes") val maxSessionMinutes: Int?,
    @SerializedName("unlock_count") val unlockCount: Int?,
    val applications: List<AppUsageCreateDto>?,
    @SerializedName("metric_statuses") val metricStatuses: UsageMetricStatusesDto,
    @SerializedName("night_window") val nightWindow: NightWindowDto?,
)

data class UsageSourceDto(
    @SerializedName("snapshot_id") val snapshotId: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("captured_at") val capturedAt: String,
    @SerializedName("period_start_at") val periodStartAt: String,
    @SerializedName("period_end_at") val periodEndAt: String,
    val timezone: String,
    @SerializedName("duration_unit") val durationUnit: String,
    @SerializedName("measurement_method") val measurementMethod: String,
    @SerializedName("calculation_version") val calculationVersion: String,
    @SerializedName("coverage_status") val coverageStatus: String,
    @SerializedName("total_usage_minutes") val totalUsageMinutes: Int?,
    @SerializedName("session_count") val sessionCount: Int?,
    @SerializedName("night_usage_minutes") val nightUsageMinutes: Int?,
    @SerializedName("max_session_minutes") val maxSessionMinutes: Int?,
    @SerializedName("unlock_count") val unlockCount: Int?,
    val applications: List<AppUsageCreateDto>?,
    @SerializedName("metric_statuses") val metricStatuses: UsageMetricStatusesDto,
    @SerializedName("night_window") val nightWindow: NightWindowDto?,
)

data class DailyUsageReadDto(
    val id: String,
    @SerializedName("profile_id") val profileId: String,
    val date: String,
    @SerializedName("total_usage_minutes") val totalUsageMinutes: Int?,
    @SerializedName("session_count") val sessionCount: Int?,
    @SerializedName("night_usage_minutes") val nightUsageMinutes: Int?,
    @SerializedName("max_session_minutes") val maxSessionMinutes: Int?,
    @SerializedName("unlock_count") val unlockCount: Int?,
    val applications: List<AppUsageReadDto>?,
    @SerializedName("metric_statuses") val metricStatuses: UsageMetricStatusesDto,
    @SerializedName("coverage_status") val coverageStatus: String,
    @SerializedName("period_start_at") val periodStartAt: String?,
    @SerializedName("period_end_at") val periodEndAt: String?,
    val timezone: String?,
    @SerializedName("duration_unit") val durationUnit: String,
    @SerializedName("measurement_method") val measurementMethod: String?,
    @SerializedName("calculation_version") val calculationVersion: String?,
    @SerializedName("night_window") val nightWindow: NightWindowDto?,
    @SerializedName("periods_uniform") val periodsUniform: Boolean,
    @SerializedName("source_count") val sourceCount: Int,
    val sources: List<UsageSourceDto> = emptyList(),
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
)

data class UsageWriteResultDto(
    val result: String,
    val usage: DailyUsageReadDto,
)
