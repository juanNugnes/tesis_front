package com.juan.habitos.ui.adult

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.juan.habitos.domain.model.Alert
import com.juan.habitos.domain.model.AppUsage
import com.juan.habitos.domain.model.DashboardPeriod
import com.juan.habitos.ui.components.MetricCard
import com.juan.habitos.ui.components.rememberApplicationVisualPresentation
import com.juan.habitos.ui.state.*
import com.juan.habitos.ui.theme.*

private const val SUMMARY_TAB = 0
private const val ALERTS_TAB = 4
private val tabs = listOf("📊\nRESUMEN", "📱\nAPPS", "🌙\nNOCTURNO", "🧠\nANÁLISIS", "🔔\nALERTAS")

@Composable fun AdultDashboardScreen(state: AdultDashboardUiState, onPeriod: (DashboardPeriod) -> Unit, onProfile: () -> Unit, onExit: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(SUMMARY_TAB) }
    Column(Modifier.fillMaxSize().background(AppColors.Surface).statusBarsPadding()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onExit) { Text("←", color = Color.White, fontSize = 24.sp) }
            Column(Modifier.weight(1f).clickable(onClick = onProfile)) {
                Text("PANEL DEL ADULTO", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("${state.profile.avatar} ${state.profile.name} · ${state.profile.age} años", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            }
            Text("›", Modifier.clickable(onClick = onProfile).padding(8.dp), color = AppColors.PurpleSoft, fontSize = 28.sp)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            tabs.forEachIndexed { index, label ->
                Column(Modifier.width(82.dp).clickable { tab = index }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 11.sp, lineHeight = 19.sp, color = if (tab == index) AppColors.PurpleSoft else Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                }
            }
        }
        HorizontalDivider(color = AppColors.Divider)
        Box(Modifier.weight(1f)) {
            when (tab) {
                SUMMARY_TAB -> Summary(state, onPeriod)
                1 -> AppsScreen(state.appsTab)
                2 -> NightScreen(state.nightTab)
                3 -> AttentionScreen(state.attentionTab)
                ALERTS_TAB -> AlertsScreen(state.alerts)
            }
        }
    }
}

@Composable private fun Summary(state: AdultDashboardUiState, onPeriod: (DashboardPeriod) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Row(Modifier.fillMaxWidth().background(AppColors.Card, RoundedCornerShape(14.dp)).padding(4.dp)) {
            listOf(DashboardPeriod.TODAY to "Hoy", DashboardPeriod.WEEK to "Esta semana").forEach { (period, label) ->
                Surface(onClick = { onPeriod(period) }, Modifier.weight(1f).height(48.dp), color = if (state.period == period) AppColors.Purple else Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(label, fontWeight = FontWeight.Bold, color = if (state.period == period) Color.White else Color(0xFF64748B)) }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        val usage = state.usage
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { MetricCard("Tiempo total", usage.currentMinutes?.let(::formatMinutes) ?: "Sin datos", usage.informationMessage ?: "Uso registrado en el período") }
            Box(Modifier.weight(1f)) { MetricCard("Sesiones", usage.sessionCount?.toString() ?: "Sin datos", usage.maxSessionMinutes?.let { "Máxima: ${formatMinutes(it)}" } ?: "Sin duración máxima disponible") }
        }
        usage.informationMessage?.let { message -> Spacer(Modifier.height(12.dp)); Text(message, color = Color(0xFFCBD5E1), fontSize = 13.sp) }
    }
}

@Composable private fun AppsScreen(state: AdultAppsTabUiState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { AdultContentHeader("Aplicaciones", "Distribución del uso de ${state.periodLabel}") }
        item {
            AdultSectionCard("Aplicaciones más utilizadas durante ${state.periodLabel}") {
                if (state.applications.isEmpty()) Text(state.informationMessage ?: "No se registraron aplicaciones en el período.", color = Color(0xFFCBD5E1))
                else { val max = state.applications.maxOfOrNull { it.minutes } ?: 1; state.applications.forEach { app -> AdultApplicationRow(app, max) } }
            }
        }
        if (state.categories.isNotEmpty()) item {
            AdultSectionCard("Distribución por categorías") {
                state.categories.forEach { category -> UsageDistributionRow("", category.name, "${category.percentage} %", category.percentage / 100f) }
            }
        }
        if (state.comparisonApp != null && state.comparisonTodayMinutes != null && state.comparisonHabitualMinutes != null) item {
            AdultSectionCard("Comparación · ${state.comparisonApp}") {
                ComparisonBar("Hoy", state.comparisonTodayMinutes, maxOf(state.comparisonTodayMinutes, state.comparisonHabitualMinutes))
                ComparisonBar("Habitual", state.comparisonHabitualMinutes, maxOf(state.comparisonTodayMinutes, state.comparisonHabitualMinutes))
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable private fun NightScreen(state: AdultNightTabUiState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { AdultContentHeader("Uso nocturno", "Actividad durante el horario de descanso configurado") }
        item { AdultSectionCard("Horario habitual de acostarse") { Text(state.bedtime?.let { "🌙  $it" } ?: "Sin horario disponible", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold) } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { NightMetricCard("Uso durante el horario de descanso", state.usageAfterBedtimeMinutes?.let(::formatMinutes) ?: "Sin datos") }
                Box(Modifier.weight(1f)) { NightMetricCard("Última actividad", state.lastActivity ?: "Sin datos") }
            }
        }
        state.informationMessage?.let { message -> item { Text(message, color = Color(0xFFCBD5E1), fontSize = 13.sp) } }
        state.activeNightsThisWeek?.let { value -> item { NightMetricCard("Noches con actividad en los últimos 7 días", value.toString()) } }
        if (state.recentDays.isNotEmpty()) item {
            AdultSectionCard("Últimos días") {
                val max = state.recentDays.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1
                state.recentDays.forEach { day -> UsageDistributionRow("", day.label, formatMinutes(day.minutes), day.minutes.toFloat() / max) }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable private fun AttentionScreen(state: AdultAttentionTabUiState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { AdultContentHeader("Análisis del comportamiento", "Patrones observados a partir del historial de uso") }
        item {
            AdultSectionCard("Patrones observados") {
                state.factors.forEach { factor ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(factor.icon, fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Column { Text(factor.title, color = Color.White, fontWeight = FontWeight.Bold); Text(factor.description, color = Color(0xFFCBD5E1), fontSize = 13.sp, lineHeight = 19.sp) }
                    }
                }
            }
        }
        item {
            AdultSectionCard("Comparación con su comportamiento habitual") {
                state.comparisons.forEach { comparison ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(comparison.label, Modifier.weight(1f), color = Color(0xFFCBD5E1))
                        Surface(color = AppColors.Purple.copy(alpha = .18f), shape = RoundedCornerShape(9.dp)) { Text(comparison.value, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = AppColors.PurpleSoft, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable private fun AdultContentHeader(title: String, subtitle: String) { Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Color(0xFF94A3B8), fontSize = 13.sp) } }
@Composable private fun AdultSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = AppColors.Card), border = BorderStroke(1.dp, AppColors.Divider), shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp); content() } } }
@Composable private fun AdultApplicationRow(app: AdultAppUsageUiModel, maxMinutes: Int) {
    val visual = rememberApplicationVisualPresentation(AppUsage(app.name, app.packageName, app.minutes, icon = app.icon))
    Surface(color = AppColors.Surface.copy(alpha = .72f), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, AppColors.Divider)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(42.dp), color = Color.White.copy(alpha = .92f), shape = RoundedCornerShape(11.dp)) {
                    Box(Modifier.padding(7.dp), contentAlignment = Alignment.Center) {
                        if (visual.installedIcon != null) Image(visual.installedIcon, contentDescription = "Ícono de ${visual.displayName}", modifier = Modifier.fillMaxSize())
                        else Text(visual.fallbackIcon, fontSize = 20.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(visual.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formatMinutes(app.minutes), color = Color(0xFFCBD5E1), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            LinearProgressIndicator({ app.minutes.toFloat() / maxMinutes.coerceAtLeast(1) }, Modifier.fillMaxWidth().height(7.dp), color = AppColors.PurpleSoft, trackColor = AppColors.Divider)
        }
    }
}
@Composable private fun UsageDistributionRow(icon: String, label: String, value: String, progress: Float) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { if (icon.isNotEmpty()) { Text(icon, fontSize = 18.sp); Spacer(Modifier.width(8.dp)) }; Text(label, Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Medium); Text(value, color = Color(0xFFCBD5E1), fontWeight = FontWeight.Bold) }; LinearProgressIndicator({ progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(7.dp), color = AppColors.PurpleSoft, trackColor = AppColors.Divider) } }
@Composable private fun ComparisonBar(label: String, minutes: Int, max: Int) = UsageDistributionRow("", label, formatMinutes(minutes), minutes.toFloat() / max.coerceAtLeast(1))
@Composable private fun NightMetricCard(label: String, value: String) { Card(colors = CardDefaults.cardColors(containerColor = AppColors.Card), border = BorderStroke(1.dp, AppColors.Divider), shape = RoundedCornerShape(16.dp)) { Column(Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { Text(label.uppercase(), color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(value, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold) } } }
private fun formatMinutes(minutes: Int): String = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

@Composable private fun AlertsScreen(alerts: List<Alert>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Alertas", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Situaciones identificadas a partir del historial de uso", color = Color(0xFF94A3B8), fontSize = 13.sp)
        }
        alerts.forEach { alert -> item(key = alert.id) { AlertCard(alert) } }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable private fun AlertCard(alert: Alert) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.Card), border = BorderStroke(1.dp, AppColors.Divider), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = AppColors.Purple.copy(alpha = .18f), shape = RoundedCornerShape(10.dp)) { Text(alert.icon, Modifier.padding(9.dp), fontSize = 22.sp) }
                Spacer(Modifier.width(10.dp))
                Text(alert.type, color = AppColors.PurpleSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Text(alert.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
            Text(alert.description, color = Color(0xFFCBD5E1), fontSize = 14.sp, lineHeight = 21.sp)
            Surface(color = AppColors.Surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, AppColors.Divider)) {
                Text(alert.detail, Modifier.fillMaxWidth().padding(14.dp), color = Color(0xFFE2E8F0), fontSize = 13.sp, lineHeight = 20.sp)
            }
            HorizontalDivider(color = AppColors.Divider)
            Text("RECOMENDACIÓN", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(alert.recommendation, color = Color(0xFFCBD5E1), fontSize = 14.sp, lineHeight = 21.sp)
        }
    }
}
