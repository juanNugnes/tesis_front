package com.juan.habitos.data.mock

import com.juan.habitos.domain.model.AppUsage
import com.juan.habitos.domain.model.DailyUsage
import com.juan.habitos.domain.model.DashboardPeriod
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.model.UsageMetricStatus
import com.juan.habitos.domain.model.UsageMetricStatuses
import com.juan.habitos.domain.model.UsageOverview
import com.juan.habitos.data.remote.AdultMessageCard
import com.juan.habitos.data.remote.AdultMessageHistory
import com.juan.habitos.data.remote.AdultMessageStatistics
import com.juan.habitos.data.remote.AdultMessageVersion
import kotlin.math.roundToInt

internal const val DASHBOARD_DEMO_LABEL = "Datos ficticios · Demostración"
internal const val DASHBOARD_DEMO_MESSAGE_LABEL = "Ejemplo ficticio"

internal data class DashboardDemoDay(
    val totalMinutes: Int,
    val sessions: Int,
    val maxSessionMinutes: Int,
    val restMinutes: Int,
    val applications: List<AppUsage>,
) {
    init {
        require(totalMinutes >= 0 && sessions >= 0 && maxSessionMinutes >= 0 && restMinutes >= 0)
        require(applications.sumOf(AppUsage::minutes) == totalMinutes) {
            "Demo application minutes must equal the daily total"
        }
    }
}

internal data class DashboardDemoComparison(val habitual: Int, val recent: Int) {
    val difference: Int get() = recent - habitual
    val percentage: Int get() = if (habitual == 0) 0 else (difference * 100.0 / habitual).roundToInt()
}

internal data class AdultDashboardDemoFixture(
    val expectedProfileName: String,
    val expectedProfileAge: Int,
    val history: List<DashboardDemoDay>,
    val restStart: String,
    val restEnd: String,
    val lastActivityLocalTime: String,
) {
    init {
        require(history.size == 28) { "Dashboard demo requires four complete weeks" }
    }

    val durationComparison: DashboardDemoComparison get() = comparison(DashboardDemoDay::totalMinutes)
    val sessionsComparison: DashboardDemoComparison get() = comparison(DashboardDemoDay::sessions)
    val restComparison: DashboardDemoComparison get() = comparison(DashboardDemoDay::restMinutes)

    fun matches(profile: Profile): Boolean =
        profile.name.trim().equals(expectedProfileName, ignoreCase = true) && profile.age == expectedProfileAge

    fun overview(period: DashboardPeriod): UsageOverview {
        val days = if (period == DashboardPeriod.TODAY) history.takeLast(1) else history.takeLast(7)
        val applications = days
            .flatMap(DashboardDemoDay::applications)
            .groupBy(AppUsage::packageName)
            .map { (packageName, values) ->
                val first = values.first()
                first.copy(packageName = packageName, minutes = values.sumOf(AppUsage::minutes))
            }
        val statuses = UsageMetricStatuses(
            totalUsageMinutes = UsageMetricStatus.MEASURED,
            sessionCount = UsageMetricStatus.MEASURED,
            nightUsageMinutes = UsageMetricStatus.MEASURED,
            maxSessionMinutes = UsageMetricStatus.MEASURED,
            unlockCount = UsageMetricStatus.UNAVAILABLE,
            applications = UsageMetricStatus.MEASURED,
            lastNightActivityAt = UsageMetricStatus.MEASURED,
        )
        return UsageOverview(
            usage = DailyUsage(
                currentMinutes = days.sumOf(DashboardDemoDay::totalMinutes),
                sessionCount = days.sumOf(DashboardDemoDay::sessions),
                nightUsageMinutes = days.sumOf(DashboardDemoDay::restMinutes),
                maxSessionMinutes = days.maxOf(DashboardDemoDay::maxSessionMinutes),
                metricStatuses = statuses,
            ),
            applications = applications,
            applicationsStatus = UsageMetricStatus.MEASURED,
            nightWindowStart = restStart,
            nightWindowEnd = restEnd,
            recordCount = days.size,
        )
    }

    fun messageHistory(profileId: String): AdultMessageHistory {
        val comparison = durationComparison
        val card = AdultMessageCard(
            cardId = "dashboard-demo-duration",
            profileId = profileId,
            seriesId = "dashboard-demo-four-weeks",
            timeZone = "America/Argentina/Buenos_Aires",
            direction = "INCREASE",
            status = "ACTIVE",
            title = "Más uso registrado en este teléfono",
            text = "En el período reciente se registraron ${minutesLabel(comparison.recent)} por día, frente a ${minutesLabel(comparison.habitual)} por día. La diferencia fue de ${comparison.difference} minutos más por día.",
            recommendation = "Podés conversar sobre qué cambió en la rutina y revisar juntos sus acuerdos sobre el celular.",
            evaluationDate = "2026-11-09",
            openingReferenceStart = "2026-10-13",
            openingRecentStart = "2026-10-27",
            comparisonRecentStart = "2026-10-27",
            statistics = AdultMessageStatistics(
                recent = comparison.recent.toDouble(),
                reference = comparison.habitual.toDouble(),
                delta = comparison.difference.toDouble(),
                minimum = comparison.difference.toDouble(),
                maximum = comparison.difference.toDouble(),
                above = 14.0,
                below = 0.0,
                percentChange = comparison.percentage.toDouble(),
                observedDaysRecent = 14,
                observedDaysReference = 14,
            ),
            measurement = "uso registrado en este teléfono",
            measurementDetail = "$DASHBOARD_DEMO_MESSAGE_LABEL. Mensaje local de presentación basado en el historial ficticio de cuatro semanas; no fue generado ni persistido por Analytics.",
            configurationId = "dashboard-demo-local",
            configurationFingerprint = "0".repeat(64),
            scope = "local_presentation_only",
            deliveryStatus = "NOT_DELIVERED",
        )
        return AdultMessageHistory(listOf(AdultMessageVersion(version = 1, card = card)))
    }

    private fun comparison(selector: (DashboardDemoDay) -> Int): DashboardDemoComparison =
        DashboardDemoComparison(
            habitual = history.take(14).map(selector).average().roundToInt(),
            recent = history.takeLast(14).map(selector).average().roundToInt(),
        )
}

private fun minutesLabel(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

private fun app(name: String, packageName: String, minutes: Int, icon: String) =
    AppUsage(name = name, packageName = packageName, minutes = minutes, icon = icon)

private val habitualApplications = listOf(
    app("YouTube Kids", "com.google.android.apps.youtube.kids", 40, "▶️"),
    app("Roblox", "com.roblox.client", 20, "🎮"),
    app("Khan Academy", "org.khanacademy.android", 25, "📚"),
    app("Chrome", "com.android.chrome", 15, "🌐"),
)

private val recentApplications = listOf(
    app("YouTube Kids", "com.google.android.apps.youtube.kids", 55, "▶️"),
    app("Roblox", "com.roblox.client", 30, "🎮"),
    app("Khan Academy", "org.khanacademy.android", 35, "📚"),
    app("Chrome", "com.android.chrome", 20, "🌐"),
)

private val habitualDay = DashboardDemoDay(100, 6, 30, 10, habitualApplications)
private val recentDay = DashboardDemoDay(140, 8, 40, 20, recentApplications)
private val today = DashboardDemoDay(140, 8, 35, 20, recentApplications)

internal val dashboardDemoFixture = AdultDashboardDemoFixture(
    expectedProfileName = "Santiago",
    expectedProfileAge = 8,
    history = List(14) { habitualDay } + List(13) { recentDay } + today,
    restStart = "21:00",
    restEnd = "07:00",
    lastActivityLocalTime = "22:30",
)

internal fun dashboardDemoEnabled(isDebug: Boolean, dashboardDemoMode: Boolean): Boolean =
    isDebug && dashboardDemoMode
