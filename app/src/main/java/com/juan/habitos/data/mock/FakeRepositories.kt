package com.juan.habitos.data.mock

import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.*

class FakeUsageRepository : UsageRepository, DeviceUsageDataSource {
    override suspend fun daily(period: DashboardPeriod) = if (period == DashboardPeriod.TODAY) DailyUsage(165, 180, 8) else DailyUsage(990, 1260, 42)
    override suspend fun apps() = listOf(
        AppUsage("YouTube Kids", "com.google.android.apps.youtube.kids", 80, "Video", "▶️"),
        AppUsage("Roblox", "com.roblox.client", 45, "Juegos", "🎮"),
        AppUsage("Khan Academy", "org.khanacademy.android", 35, "Educación", "📚")
    )
    override suspend fun recommendation() = Recommendation("¿Querés hacer algo diferente?", "", "🎨 Dibujá algo que te guste")
    override suspend fun getDailyUsage() = daily()
    override suspend fun getApplicationUsage() = apps()
}
class FakeAlertRepository : AlertRepository {
    override suspend fun alerts() = listOf(
        Alert(
            id = "schedule-1",
            type = "HORARIO",
            icon = "🌙",
            title = "Uso posterior al horario habitual de acostarse",
            description = "Durante los últimos 7 días se registró actividad posterior al horario habitual de acostarse en 3 noches.",
            detail = "Horario habitual de acostarse: 22:00\nMayor duración registrada: 44 min",
            recommendation = "Podés conversar sobre el uso del celular antes de dormir y acordar un momento para dejar el dispositivo fuera de la habitación.",
        ),
        Alert(
            id = "habit-change-1",
            type = "CAMBIO DE HÁBITO",
            icon = "📈",
            title = "Aumento en el tiempo de uso",
            description = "Durante los últimos 7 días se observó un aumento del tiempo de uso respecto de su comportamiento habitual.",
            detail = "Promedio reciente: 3 h 20 min\nHabitual: 2 h 15 min",
            recommendation = "Antes de establecer un límite, puede ser útil revisar si hubo algún cambio de rutina, una actividad escolar o más tiempo libre.",
        ),
        Alert(
            id = "frequency-1",
            type = "FRECUENCIA",
            icon = "📱",
            title = "Aumento en la frecuencia de uso",
            description = "Durante los últimos 7 días se registró una mayor cantidad de sesiones respecto de su comportamiento habitual.",
            detail = "Promedio reciente: 24 sesiones por día\nHabitual: 17 sesiones por día",
            recommendation = "Podés observar en qué momentos aparece el celular con mayor frecuencia y conversar sobre espacios del día sin pantalla.",
        ),
    )
}
