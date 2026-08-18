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
    override suspend fun recommendation() = Recommendation("¿Hacemos una pausa?", "Estuviste usando el celular durante un tiempo prolongado. Podés probar una actividad diferente por unos minutos.", "🧘 Estirá el cuerpo durante 5 minutos")
    override suspend fun getDailyUsage() = daily()
    override suspend fun getApplicationUsage() = apps()
}
class FakeAlertRepository : AlertRepository { override suspend fun alerts() = listOf(Alert("1", "Uso prolongado")) }
