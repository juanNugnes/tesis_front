package com.juan.habitos.ui.adult

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.juan.habitos.domain.model.DashboardPeriod
import com.juan.habitos.ui.components.MetricCard
import com.juan.habitos.ui.state.AdultDashboardUiState
import com.juan.habitos.ui.theme.*

private val tabs = listOf("📊\nRESUMEN", "📱\nAPPS", "🌙\nNOCTURNO", "🧠\nATENCIÓN", "🔔\nALERTAS")
@Composable fun AdultDashboardScreen(state: AdultDashboardUiState, onPeriod: (DashboardPeriod) -> Unit, onExit: () -> Unit) { var tab by rememberSaveable { mutableIntStateOf(0) }; Column(Modifier.fillMaxSize().background(AppColors.Surface).statusBarsPadding()) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onExit) { Text("←", color = Color.White, fontSize = 24.sp) }; Column(Modifier.weight(1f)) { Text("PANEL LOCAL", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold); Text("${state.profile.avatar} ${state.profile.name} · ${state.profile.age} años", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp) }; Surface(color = Color(0xFF2E1065), shape = RoundedCornerShape(12.dp)) { Text("Hoy", Modifier.padding(14.dp), color = AppColors.PurpleSoft, fontWeight = FontWeight.Bold) } }; Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { tabs.forEachIndexed { index, label -> Column(Modifier.width(82.dp).clickable { tab = index }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(label, fontSize = 11.sp, lineHeight = 19.sp, color = if (tab == index) AppColors.PurpleSoft else Color(0xFF94A3B8), fontWeight = FontWeight.Bold) }; } }; HorizontalDivider(color = AppColors.Divider); if (tab != 0) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("${tabs[tab].substringAfter('\n').lowercase().replaceFirstChar { it.uppercase() }} · En construcción", color = Color(0xFF94A3B8)) } else Summary(state, onPeriod) } }

@Composable private fun Summary(state: AdultDashboardUiState, onPeriod: (DashboardPeriod) -> Unit) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) { Row(Modifier.fillMaxWidth().background(AppColors.Card, RoundedCornerShape(14.dp)).padding(4.dp)) { listOf(DashboardPeriod.TODAY to "Hoy", DashboardPeriod.WEEK to "Esta semana").forEach { (period,label) -> Surface(onClick = { onPeriod(period) }, Modifier.weight(1f).height(48.dp), color = if (state.period == period) AppColors.Purple else Color.Transparent, shape = RoundedCornerShape(12.dp)) { Box(contentAlignment = Alignment.Center) { Text(label, fontWeight = FontWeight.Bold, color = if (state.period == period) Color.White else Color(0xFF64748B)) } } } }; Spacer(Modifier.height(20.dp)); val usage = state.usage; val app = state.apps.firstOrNull(); Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) { Box(Modifier.weight(1f)) { MetricCard("Tiempo total", "${usage.currentMinutes / 60}h ${usage.currentMinutes % 60}m", "Límite: ${usage.expectedMinutes / 60} horas") }; Box(Modifier.weight(1f)) { MetricCard("Sesiones", "${usage.sessionCount}", "Prom. ${usage.currentMinutes / usage.sessionCount.coerceAtLeast(1)} min/sesión") } }; Spacer(Modifier.height(12.dp)); Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) { Box(Modifier.weight(1f)) { MetricCard("App principal", "▶️ ${app?.name?.removeSuffix(" Kids") ?: "—"}", app?.let { "${it.minutes / 60}h ${it.minutes % 60}m hoy" } ?: "Sin datos") }; Box(Modifier.weight(1f)) { MetricCard("Alertas hoy", "${state.alerts.size}", "Ver alertas →", Color(0xFFF59E0B)) } } } }
