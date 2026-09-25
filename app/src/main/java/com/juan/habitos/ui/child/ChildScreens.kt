package com.juan.habitos.ui.child

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.juan.habitos.ui.components.*
import com.juan.habitos.ui.state.*
import com.juan.habitos.ui.theme.*

private val ChildCardColor = Color.White.copy(alpha = .11f)
private val ChildBorderColor = Color.White.copy(alpha = .20f)

@Composable fun ChildHomeScreen(state: ChildHomeUiState, onLock: () -> Unit, onAnotherActivity: () -> Unit, onStartActivity: () -> Unit, onFinishActivity: () -> Unit) {
    val gradient = Brush.verticalGradient(listOf(Color(0xFF5B21B6), Color(0xFF4F46E5), Color(0xFF2563EB)))
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(gradient).statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ChildHeader(state, onLock) }
        item { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { UsageProgressCircle(state.usage); Spacer(Modifier.height(14.dp)); Text("Tiempo de pantalla hoy", color = Color.White, fontWeight = FontWeight.Bold) } }
        item { UsageComparison(state.usage.currentMinutes, state.habitualUsageMinutes) }
        item { DayPeriodUsageCard(state.periodUsage) }
        item { ActivitySuggestionCard(state.activityState, onAnotherActivity, onStartActivity, onFinishActivity) }
        item { ActivityHistory(state.chosenActivities) }
        item { AppsTodayCard(state) }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable private fun ChildHeader(state: ChildHomeUiState, onLock: () -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("¡Buenas tardes!", color = Color.White.copy(.72f), fontSize = 14.sp); Text("${state.profile.avatar}  ${state.profile.name}", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold) }; FilledTonalIconButton(onLock, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(.15f), contentColor = Color.White)) { Text("🔒") } } }

@Composable fun UsageComparison(todayMinutes: Int, habitualMinutes: Int) { val max = maxOf(todayMinutes, habitualMinutes, 1); ChildCard { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { ComparisonRow("Hoy", todayMinutes, max, AppColors.PurpleSoft); ComparisonRow("Habitual", habitualMinutes, max, Color(0xFFB8A6F7)) } } }
@Composable private fun ComparisonRow(label: String, minutes: Int, maxMinutes: Int, color: Color) { Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.width(70.dp), color = Color.White.copy(.72f), fontSize = 13.sp); LinearProgressIndicator({ minutes.toFloat() / maxMinutes }, Modifier.weight(1f).height(9.dp).clip(CircleShape), color = color, trackColor = Color.White.copy(.13f)); Spacer(Modifier.width(12.dp)); Text(formatMinutes(minutes), Modifier.width(64.dp), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) } }

@Composable fun DayPeriodUsageCard(periods: List<DayPeriodUsageUiModel>) { ChildCard { Column(verticalArrangement = Arrangement.spacedBy(15.dp)) { Text("☀️  Cómo usaste el celu hoy", color = Color.White, fontWeight = FontWeight.Bold); Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) { periods.forEach { period -> DayPeriodItem(period, Modifier.weight(1f)) } } } } }
@Composable fun DayPeriodItem(item: DayPeriodUsageUiModel, modifier: Modifier = Modifier) { val (icon, label) = when (item.period) { DayPeriod.MORNING -> "🌅" to "Mañana"; DayPeriod.AFTERNOON -> "☀️" to "Tarde"; DayPeriod.NIGHT -> "🌙" to "Noche" }; Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) { Box(Modifier.fillMaxWidth().height(42.dp).background(if (item.period == DayPeriod.AFTERNOON) AppColors.PurpleSoft.copy(.88f) else Color.White.copy(.12f), RoundedCornerShape(10.dp))); Text(icon, fontSize = 18.sp); Text(label, color = Color.White.copy(.7f), fontSize = 11.sp); Text(formatMinutes(item.minutes), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) } }

@Composable fun ActivitySuggestionCard(activityState: ActivityState, onAnotherIdea: () -> Unit, onStart: () -> Unit, onFinish: () -> Unit) { ChildCard { when (activityState) {
    is ActivityState.Suggested -> Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("🌿  ¿Querés hacer algo diferente?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Surface(color = Color.White.copy(.12f), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color.White.copy(.12f))) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("${activityState.activity.emoji}  ${activityState.activity.text}", color = Color.White, fontWeight = FontWeight.Bold); activityState.activity.suggestedDurationMinutes?.let { Text("Duración sugerida: $it min", color = Color.White.copy(.7f), fontSize = 12.sp) } } }
        TextButton(onClick = onAnotherIdea, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Otra idea →", color = Color(0xFFE9D5FF), fontWeight = FontWeight.Bold) }
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF7F5FF), contentColor = AppColors.PurpleUi), shape = RoundedCornerShape(14.dp)) { Text("Empezar actividad", fontWeight = FontWeight.Bold) }
    }
    is ActivityState.Running -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("🌿  Actividad en curso", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("${activityState.activity.emoji}  ${activityState.activity.text}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        activityState.activity.suggestedDurationMinutes?.let { Text("Duración sugerida: $it min", color = Color.White.copy(.72f)) }
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF7F5FF), contentColor = AppColors.PurpleUi), shape = RoundedCornerShape(14.dp)) { Text("Finalizar actividad", fontWeight = FontWeight.Bold) }
    }
} } }

@Composable fun ActivityHistory(chosenActivities: Int) { ChildCard { Text("🌱  Hoy elegiste $chosenActivities ${if (chosenActivities == 1) "actividad" else "actividades"}", color = Color.White, fontWeight = FontWeight.Bold) } }

@Composable private fun AppsTodayCard(state: ChildHomeUiState) { ChildCard { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { Text("MIS APPS DE HOY", color = Color.White.copy(.68f), fontSize = 12.sp, fontWeight = FontWeight.Bold); val max = state.apps.maxOfOrNull { it.minutes } ?: 1; state.apps.forEach { UsageAppRow(it, max) } } } }
@Composable private fun ChildCard(content: @Composable () -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = ChildCardColor), border = BorderStroke(1.dp, ChildBorderColor), shape = RoundedCornerShape(20.dp)) { Box(Modifier.fillMaxWidth().padding(16.dp)) { content() } } }
private fun formatMinutes(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

@Composable fun AdultPinScreen(state: PinUiState, onDigit: (Int) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) { Column(Modifier.fillMaxSize().background(AppColors.Surface).statusBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("👤", color = AppColors.PurpleSoft, fontSize = 58.sp); Spacer(Modifier.height(22.dp)); Text("Panel del adulto", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold); Text("Ingresá tu PIN de 4 dígitos", color = Color(0xFFCBD5E1)); Spacer(Modifier.height(30.dp)); PinDots(state.digits.length, state.error, emptyColor = Color(0xFF64748B), filledColor = AppColors.PurpleSoft); if (state.error) Text("PIN incorrecto. Intentá nuevamente.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)); Spacer(Modifier.height(30.dp)); NumericPinKeyboard(onDigit, onDelete, highContrastDark = true); Spacer(Modifier.height(28.dp)); TextButton(onBack) { Text("← Volver", color = Color(0xFFCBD5E1)) } } }
