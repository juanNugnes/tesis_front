package com.juan.habitos.ui.adult

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.juan.habitos.domain.model.AlertDisplayStatus
import com.juan.habitos.domain.model.AlertOrigin
import com.juan.habitos.domain.model.AppUsage
import com.juan.habitos.domain.model.DashboardPeriod
import com.juan.habitos.data.mock.ALERTS_DEMO_CARD_LABEL
import com.juan.habitos.data.mock.ALERTS_DEMO_HEADER
import com.juan.habitos.data.mock.DASHBOARD_DEMO_LABEL
import com.juan.habitos.data.mock.activeAlerts
import com.juan.habitos.data.mock.reviewAlerts
import com.juan.habitos.ui.components.MetricCard
import com.juan.habitos.ui.components.rememberApplicationVisualPresentation
import com.juan.habitos.ui.state.*
import com.juan.habitos.ui.theme.*
import com.juan.habitos.data.remote.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val SUMMARY_TAB = 0
internal const val ANALYSIS_TAB = 3
private const val ALERTS_TAB = 4
private val tabs = listOf("📊\nRESUMEN", "📱\nAPPS", "🌙\nNOCTURNO", "🧠\nANÁLISIS", "🔔\nALERTAS")

@Composable fun AdultDashboardScreen(state: AdultDashboardUiState, onPeriod: (DashboardPeriod) -> Unit, onProfile: () -> Unit, messages: AdultMessagesUiState = AdultMessagesUiState(), onRetryMessages: () -> Unit = {}, onMessagesSignIn: () -> Unit = {}, onExit: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(SUMMARY_TAB) }
    var focusedMessageId by rememberSaveable(messages.profileId) { mutableStateOf<String?>(null) }
    var focusedMessageVersion by rememberSaveable(messages.profileId) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(AppColors.Surface).statusBarsPadding()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onExit) { Text("←", color = Color.White, fontSize = 24.sp) }
            Column(Modifier.weight(1f).clickable(onClick = onProfile)) {
                Text("PANEL DEL ADULTO", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("${state.profile.avatar} ${state.profile.name} · ${state.profile.age} años", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            }
            Text("›", Modifier.clickable(onClick = onProfile).padding(8.dp), color = AppColors.PurpleSoft, fontSize = 28.sp)
        }
        if (state.dashboardDemoRequested && tab != ALERTS_TAB) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                color = AppColors.Purple.copy(alpha = .18f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AppColors.PurpleSoft.copy(alpha = .45f)),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        if (state.dashboardDemoActive) DASHBOARD_DEMO_LABEL else "Modo de demostración del panel",
                        color = AppColors.PurpleSoft,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (state.dashboardDemoActive) "Resumen, Aplicaciones, Nocturno y las comparaciones de Análisis usan el escenario local de cuatro semanas; no son registros reales."
                        else "Para el video, creá o seleccioná un perfil llamado Santiago, de 8 años. El perfil actual y sus datos no fueron modificados.",
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
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
                SUMMARY_TAB -> Summary(state, onPeriod, messages) { tab = ANALYSIS_TAB }
                1 -> AppsScreen(state.appsTab)
                2 -> NightScreen(state.nightTab)
                ANALYSIS_TAB -> AttentionScreen(state.attentionTab, messages, onRetryMessages, onMessagesSignIn, focusedMessageId, focusedMessageVersion)
                ALERTS_TAB -> AlertsScreen(
                    alerts = state.alerts,
                    alertsDemo = state.alertsDemo,
                    demoAlert = adultMessageAlertForAlertsTab(state.alertsDemo || messages.localOnly, adultMessageDemoAlert(messages)),
                ) { target ->
                    openAdultMessageDemoAlert(target,
                        selectMessage = { id, version -> focusedMessageId = id; focusedMessageVersion = version },
                        selectTab = { tab = it },
                    )
                }
            }
        }
    }
}

@Composable private fun Summary(state: AdultDashboardUiState, onPeriod: (DashboardPeriod) -> Unit, messages: AdultMessagesUiState, onAnalysis: () -> Unit) {
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
        adultMessageSummaryTitle(messages)?.let { title ->
            Spacer(Modifier.height(20.dp))
            AdultSectionCard(title) {
                adultMessagesDemoLabel(messages)?.let { Text(it, color = AppColors.PurpleSoft, fontSize = 12.sp) }
                TextButton(onClick = onAnalysis) { Text("Ver análisis") }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

internal fun adultMessageSummaryTitle(state: AdultMessagesUiState): String? =
    if (state.loading || state.error != null || state.profileId == null) null
    else state.current.firstOrNull()?.card?.title

internal fun adultMessagesDemoLabel(state: AdultMessagesUiState): String? =
    if (state.demo) state.demoLabel ?: ADULT_MESSAGES_DEMO_LABEL else null

@Composable private fun AdultMessagesSection(state: AdultMessagesUiState, onRetry: () -> Unit, onSignIn: () -> Unit, focusedMessageId: String? = null, focusedVersion: Int = 0) {
    var showHistory by rememberSaveable(state.profileId) { mutableStateOf(false) }
    AdultSectionCard("Mensajes para acompañarte") {
        adultMessagesDemoLabel(state)?.let { Text(it, color = AppColors.PurpleSoft) }
        when {
            state.loading -> CircularProgressIndicator(Modifier.size(28.dp), color = AppColors.PurpleSoft)
            state.error != null -> {
                Text(state.error, color = Color(0xFFCBD5E1))
                if (state.retryable) TextButton(onClick = onRetry) { Text("Reintentar") }
                if (state.authenticationRequired) TextButton(onClick = onSignIn) { Text("Iniciar sesión adulta") }
            }
            state.profileId == null -> Text("Seleccioná un perfil infantil para consultar sus mensajes.", color = Color(0xFFCBD5E1))
            else -> {
                if (state.current.isEmpty()) Text(
                    if (state.archived.isEmpty()) "Todavía no hay mensajes disponibles" else "No hay mensajes en seguimiento.",
                    color = Color(0xFFCBD5E1),
                )
                state.current.forEach { latest ->
                    val opening = if (state.demo) state.history?.versions
                        ?.filter { it.card.cardId == latest.card.cardId && it.version < latest.version }
                        ?.minByOrNull { it.version } else null
                    var showOpening by rememberSaveable(state.profileId, latest.card.cardId, focusedMessageId, focusedVersion) {
                        mutableStateOf(opening != null && focusedMessageId == latest.card.cardId && focusedVersion == opening.version)
                    }
                    if (opening != null) {
                        Text(if (showOpening) "Versión inicial del ejemplo" else "Actualización del ejemplo", color = AppColors.PurpleSoft, fontSize = 12.sp)
                        TextButton(onClick = { showOpening = !showOpening }) {
                            Text(if (showOpening) "Ver seguimiento" else "Ver versión inicial")
                        }
                    }
                    AdultMessageContent(
                        if (showOpening && opening != null) opening.card else latest.card,
                        demoLabel = adultMessagesDemoLabel(state),
                    )
                }
                if (state.archived.isNotEmpty()) {
                    TextButton(onClick = { showHistory = !showHistory }) {
                        Text(if (showHistory) "Ocultar historial" else "Ver historial")
                    }
                    if (showHistory) state.archived.forEach { AdultMessageContent(it.card, demoLabel = adultMessagesDemoLabel(state)) }
                }
                if (!state.localOnly) TextButton(onClick = onRetry) { Text("Actualizar mensajes") }
            }
        }
    }
}

@Composable private fun AdultMessageContent(card: AdultMessageCard, demoLabel: String? = null) {
    var expanded by rememberSaveable(card.cardId, card.updatedDate, card.evaluationDate) { mutableStateOf(false) }
    Surface(color = AppColors.Surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, AppColors.Divider)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            demoLabel?.let { Text(it, color = AppColors.PurpleSoft, fontSize = 12.sp) }
            Text(messageStatusLabel(card.status), color = AppColors.PurpleSoft, fontSize = 12.sp)
            Text(card.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(card.text, color = Color(0xFFCBD5E1))
            if (card.recommendation.isNotBlank()) Text(card.recommendation, color = Color.White)
            val date = card.updatedDate ?: card.evaluationDate
            val formattedDate = runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) }.getOrDefault(date)
            Text("${if (card.updatedDate != null) "Actualizado" else "Evaluado"}: $formattedDate", color = Color(0xFF94A3B8), fontSize = 12.sp)
            if (!card.correctedDates.isNullOrEmpty()) Text("Incluye correcciones de datos registrados.", color = Color(0xFFCBD5E1), fontSize = 12.sp)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ver menos" else "Ver detalle") }
            if (expanded) {
                Text(card.measurementDetail, color = Color(0xFFCBD5E1), fontSize = 13.sp)
                Text("Referencia desde ${card.openingReferenceStart} · Comparación desde ${card.comparisonRecentStart}", color = Color(0xFF94A3B8), fontSize = 12.sp)
            }
        }
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
        item {
            val restWindow = when {
                state.bedtime != null && state.restEnd != null -> "${state.bedtime}–${state.restEnd}"
                state.bedtime != null -> state.bedtime
                else -> null
            }
            AdultSectionCard(if (state.restEnd != null) "Horario de descanso configurado" else "Horario habitual de acostarse") {
                Text(restWindow?.let { "🌙  $it" } ?: "Sin horario disponible", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { NightMetricCard("Uso durante el horario de descanso", state.usageAfterBedtimeMinutes?.let(::formatMinutes) ?: "Sin datos") }
                Box(Modifier.weight(1f)) { NightMetricCard("Última actividad registrada", state.lastActivity ?: "Sin datos") }
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

@Composable private fun AttentionScreen(state: AdultAttentionTabUiState, messages: AdultMessagesUiState, onRetryMessages: () -> Unit, onMessagesSignIn: () -> Unit, focusedMessageId: String?, focusedVersion: Int) {
    val listState = rememberLazyListState()
    LaunchedEffect(focusedMessageId, focusedVersion) {
        if (focusedMessageId != null) listState.scrollToItem(1)
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { AdultContentHeader("Análisis del comportamiento", "Patrones observados a partir del historial de uso") }
        item { AdultMessagesSection(messages, onRetryMessages, onMessagesSignIn, focusedMessageId, focusedVersion) }
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
                state.comparisons.forEachIndexed { index, comparison ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = AppColors.Surface.copy(alpha = .72f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AppColors.Divider),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(comparison.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(comparison.value, color = Color(0xFFCBD5E1), fontSize = 14.sp, lineHeight = 20.sp)
                            comparison.detail?.let { detail ->
                                Text(detail, color = AppColors.PurpleSoft, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                    if (index < state.comparisons.lastIndex) Spacer(Modifier.height(2.dp))
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

@Composable private fun AlertsScreen(
    alerts: List<Alert>,
    alertsDemo: Boolean,
    demoAlert: AdultMessageDemoAlert?,
    onOpenMessage: (AdultMessageDemoAlert) -> Unit,
) {
    val active = activeAlerts(alerts)
    val review = reviewAlerts(alerts)
    var showReview by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Alertas", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                when {
                    alertsDemo -> ALERTS_DEMO_HEADER
                    demoAlert != null -> "Esta pantalla incluye ejemplos de demostración"
                    else -> "Información disponible para este perfil"
                },
                color = if (alertsDemo || demoAlert != null) AppColors.PurpleSoft else Color(0xFF94A3B8),
                fontSize = 13.sp,
                fontWeight = if (alertsDemo || demoAlert != null) FontWeight.Bold else FontWeight.Normal,
            )
        }
        if (alertsDemo) item {
            AlertInformationCard("Demostración local", "Estos ejemplos son ficticios. No fueron generados por el dispositivo ni por Analytics en producción.")
        }
        if (demoAlert != null) item(key = demoAlert.alert.id) {
            AlertCard(demoAlert.alert) { onOpenMessage(demoAlert) }
        }
        active.forEach { alert -> item(key = alert.id) { AlertCard(alert) } }
        if (!alertsDemo && active.isEmpty()) item {
            AlertInformationCard(
                "Alertas con datos reales",
                "Las familias de avisos todavía no están habilitadas con datos reales. No hay evaluaciones verificadas para mostrar.",
            )
        }
        if (alertsDemo && review.isNotEmpty()) {
            item {
                HorizontalDivider(color = AppColors.Divider)
                Spacer(Modifier.height(2.dp))
                Text("Otros estados para revisión", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Escenarios alternativos; no son avisos activos adicionales.", color = Color(0xFF94A3B8), fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showReview = !showReview },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, AppColors.PurpleSoft),
                ) {
                    Text(if (showReview) "Ocultar estados de ejemplo" else "Revisar estados de ejemplo (${review.size})")
                }
            }
            if (showReview) review.forEach { alert -> item(key = alert.id) { AlertCard(alert) } }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable private fun AlertCard(alert: Alert, onClick: (() -> Unit)? = null) {
    Card(modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier, colors = CardDefaults.cardColors(containerColor = AppColors.Card), border = BorderStroke(1.dp, AppColors.Divider), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = AppColors.Purple.copy(alpha = .18f), shape = RoundedCornerShape(10.dp)) { Text(alert.icon, Modifier.padding(9.dp), fontSize = 22.sp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(alert.type, color = AppColors.PurpleSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    if (alert.origin == AlertOrigin.DEMO_FICTITIOUS) {
                        Surface(color = Color(0xFF3B2D68), shape = RoundedCornerShape(50)) {
                            Text(ALERTS_DEMO_CARD_LABEL, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            alertStatusLabel(alert.displayStatus)?.let { status ->
                Text(status, color = Color(0xFFFBBF24), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(alert.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
            Text(alert.description, color = Color(0xFFCBD5E1), fontSize = 14.sp, lineHeight = 21.sp)
            if (alert.detail.isNotBlank()) Surface(color = AppColors.Surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, AppColors.Divider)) {
                Text(alert.detail, Modifier.fillMaxWidth().padding(14.dp), color = Color(0xFFE2E8F0), fontSize = 13.sp, lineHeight = 20.sp)
            }
            if (alert.periods.isNotBlank()) AlertDetail("PERÍODOS", alert.periods)
            alert.evaluatedAt?.let { AlertDetail("EVALUADO", it) }
            if (alert.measurement.isNotBlank()) AlertDetail("QUÉ SE MIDE", alert.measurement)
            if (alert.limitation.isNotBlank()) AlertDetail("LÍMITES", alert.limitation)
            if (alert.recommendation.isNotBlank()) {
                HorizontalDivider(color = AppColors.Divider)
                Text("SUGERENCIA", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(alert.recommendation, color = Color(0xFFCBD5E1), fontSize = 14.sp, lineHeight = 21.sp)
            }
            if (onClick != null) Text("Ver análisis →", color = AppColors.PurpleSoft, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun AlertDetail(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(text, color = Color(0xFFCBD5E1), fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable private fun AlertInformationCard(title: String, text: String) {
    Surface(color = AppColors.Card, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, AppColors.Divider)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(text, color = Color(0xFFCBD5E1), fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

internal fun alertStatusLabel(status: AlertDisplayStatus): String? = when (status) {
    AlertDisplayStatus.ACTIVE -> null
    AlertDisplayStatus.INSUFFICIENT_WITH_PRIOR -> "DATOS INSUFICIENTES · HABÍA UN AVISO PREVIO"
    AlertDisplayStatus.INSUFFICIENT_WITHOUT_PRIOR -> "DATOS INSUFICIENTES · SIN AVISO PREVIO"
    AlertDisplayStatus.WITHDRAWN -> "CORREGIDO · AVISO RETIRADO"
    AlertDisplayStatus.CORRECTED_CONTINUES -> "CORREGIDO · EL CAMBIO CONTINÚA RESPALDADO"
    AlertDisplayStatus.EVALUATED_NO_PATTERN -> "EVALUADO · SIN CAMBIO CONFIRMADO"
    AlertDisplayStatus.NOT_EVALUATED -> "NO EVALUADO"
    AlertDisplayStatus.NOT_ENABLED -> "NO HABILITADO CON DATOS REALES"
}
