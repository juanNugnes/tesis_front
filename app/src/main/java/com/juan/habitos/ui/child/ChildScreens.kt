package com.juan.habitos.ui.child

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.juan.habitos.ui.components.*
import com.juan.habitos.ui.state.*
import com.juan.habitos.ui.theme.*
import kotlinx.coroutines.launch

private val ChildCardColor = Color.White.copy(alpha = .11f)
private val ChildBorderColor = Color.White.copy(alpha = .20f)

@Composable fun ChildHomeScreen(state: ChildHomeUiState, onLock: () -> Unit, onAnotherActivity: () -> Unit, onStartActivity: () -> Unit, onFinishActivity: () -> Unit, onRetryActivity: () -> Unit, onRetrySelection: () -> Unit) {
    val gradient = Brush.verticalGradient(listOf(Color(0xFF5B21B6), Color(0xFF4F46E5), Color(0xFF2563EB)))
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(gradient).statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ChildHeader(state, onLock) }
        item { DaySceneCard(state.profile.avatar, state.chosenActivities) }
        item { DayUsageSummary(state.usage.currentMinutes, state.habitualUsageMinutes) }
        item { ActivitySuggestionCard(state.profile.avatar, state.activityState, state.selectionRegistrationFailed, onAnotherActivity, onStartActivity, onFinishActivity, onRetryActivity, onRetrySelection) }
        item { DayPeriodUsageCard(state.periodUsage) }
        item { ActivityHistory(state.chosenActivities) }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable private fun ChildHeader(state: ChildHomeUiState, onLock: () -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Surface(Modifier.size(64.dp), shape = CircleShape, color = Color(0xFFFFF4D8), border = BorderStroke(2.dp, Color.White.copy(.55f))) { Box(contentAlignment = Alignment.Center) { Text(state.profile.avatar, fontSize = 34.sp) } }; Spacer(Modifier.width(14.dp)); Text("¡Hola, ${state.profile.name}!", Modifier.weight(1f), color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold); FilledTonalIconButton(onLock, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(.15f), contentColor = Color.White)) { Text("🔒") } } }

@Composable private fun DaySceneCard(avatar: String, chosenActivities: Int) {
    val idle = rememberInfiniteTransition(label = "day-scene-idle")
    val avatarOffset by idle.animateFloat(-3f, 3f, infiniteRepeatable(tween(3600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "avatar-float")
    val cloudOffset by idle.animateFloat(-8f, 10f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "cloud-drift")
    ChildCard(containerColor = Color(0xFFE9F8EE).copy(.96f), borderColor = Color.White.copy(.55f)) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("🌱  Tu día", color = Color(0xFF24543A), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(listOf(Color(0xFFBDE9FF), Color(0xFFE9F8D8))))) {
            Text("☁️", Modifier.align(Alignment.TopStart).padding(start = 22.dp, top = 12.dp).graphicsLayer { translationX = cloudOffset }, fontSize = 23.sp)
            Text("☁️", Modifier.align(Alignment.TopCenter).padding(top = 30.dp).graphicsLayer { translationX = -cloudOffset * .65f; alpha = .78f }, fontSize = 17.sp)
            Box(Modifier.size(94.dp).offset(x = (-18).dp, y = 96.dp).background(Color(0xFF8ED081), CircleShape))
            Box(Modifier.size(150.dp).align(Alignment.BottomEnd).offset(x = 32.dp, y = 78.dp).background(Color(0xFF72C979), CircleShape))
            Text("☀️", Modifier.align(Alignment.TopEnd).padding(14.dp), fontSize = 27.sp)
            Text(avatar, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).graphicsLayer { translationY = avatarOffset }, fontSize = 43.sp)
            val details = listOf("🌿", "🌼", "🦋", "⭐")
            details.forEachIndexed { index, detail -> val visible = index < chosenActivities.coerceIn(0, details.size); val detailScale by animateFloatAsState(if (visible) 1f else .7f, tween(350, easing = FastOutSlowInEasing), label = "day-detail-scale-$index"); val detailAlpha by animateFloatAsState(if (visible) 1f else 0f, tween(280), label = "day-detail-alpha-$index"); Text(detail, Modifier.align(if (index % 2 == 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = (18 + index * 7).dp, vertical = (8 + index * 4).dp).graphicsLayer { scaleX = detailScale; scaleY = detailScale; alpha = detailAlpha }, fontSize = 22.sp) }
        }
    } }
}

@Composable private fun DayUsageSummary(todayMinutes: Int, habitualMinutes: Int) { var expanded by rememberSaveable { mutableStateOf(false) }; val difference = todayMinutes - habitualMinutes; val message = when { difference > 15 -> "Hoy usaste el celu un poquito más que otros días"; difference < -15 -> "Hoy usaste el celu un poquito menos que otros días"; else -> "Hoy viene parecido a otros días" }; ChildCard { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Así viene tu día", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp); Text(message, color = Color.White.copy(.82f), lineHeight = 20.sp); TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(if (expanded) "Ocultar mi tiempo ↑" else "Ver mi tiempo →", color = Color(0xFFFFE08A), fontWeight = FontWeight.Bold) }; AnimatedVisibility(visible = expanded, enter = expandVertically(tween(220)) + fadeIn(), exit = shrinkVertically(tween(180)) + fadeOut()) { UsageComparison(todayMinutes, habitualMinutes) } } } }

@Composable fun UsageComparison(todayMinutes: Int, habitualMinutes: Int) { val max = maxOf(todayMinutes, habitualMinutes, 1); Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { HorizontalDivider(color = Color.White.copy(.18f)); ComparisonRow("Hoy", todayMinutes, max, Color(0xFFFFD166)); ComparisonRow("Habitual", habitualMinutes, max, Color(0xFFB8A6F7)) } }
@Composable private fun ComparisonRow(label: String, minutes: Int, maxMinutes: Int, color: Color) { Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.width(70.dp), color = Color.White.copy(.72f), fontSize = 13.sp); LinearProgressIndicator({ minutes.toFloat() / maxMinutes }, Modifier.weight(1f).height(9.dp).clip(CircleShape), color = color, trackColor = Color.White.copy(.13f)); Spacer(Modifier.width(12.dp)); Text(formatMinutes(minutes), Modifier.width(64.dp), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) } }

@Composable fun DayPeriodUsageCard(periods: List<DayPeriodUsageUiModel>) { var selected by rememberSaveable { mutableStateOf<DayPeriod?>(null) }; ChildCard { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { Text("Momentos de tu día", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp); Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(9.dp)) { periods.forEachIndexed { index, period -> DayPeriodItem(period, selected == period.period, selected != null, index, Modifier.weight(1f)) { selected = if (selected == period.period) null else period.period } } }; AnimatedContent(targetState = selected, transitionSpec = { fadeIn(tween(220)) + expandVertically() togetherWith fadeOut(tween(140)) + shrinkVertically() }, label = "period-detail") { selectedPeriod -> periods.firstOrNull { it.period == selectedPeriod }?.let { item -> val label = periodPresentation(item.period).second; Text("$label\nUsaste el celu ${formatMinutesLong(item.minutes)}.", Modifier.fillMaxWidth().background(Color.White.copy(.10f), RoundedCornerShape(12.dp)).padding(12.dp), color = Color.White, fontWeight = FontWeight.Medium, lineHeight = 21.sp) } } } } }
@Composable fun DayPeriodItem(item: DayPeriodUsageUiModel, selected: Boolean, hasSelection: Boolean, index: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val (icon, label) = periodPresentation(item.period)
    val idle = rememberInfiniteTransition(label = "period-$index")
    val floatingOffset by idle.animateFloat(-2.5f, 2.5f, infiniteRepeatable(tween(3200 + index * 550, easing = FastOutSlowInEasing, delayMillis = index * 180), RepeatMode.Reverse), label = "period-float-$index")
    val scale by animateFloatAsState(if (selected) 1.06f else if (hasSelection) .95f else 1f, tween(220, easing = FastOutSlowInEasing), label = "period-scale")
    val emphasis by animateFloatAsState(if (hasSelection && !selected) .68f else 1f, tween(220), label = "period-emphasis")
    Surface(onClick = onClick, modifier = modifier.height(96.dp).graphicsLayer { translationY = floatingOffset; scaleX = scale; scaleY = scale; alpha = emphasis }, color = if (selected) Color(0xFFFFD166).copy(.28f) else Color.White.copy(.10f), shape = RoundedCornerShape(30.dp), border = BorderStroke(1.dp, if (selected) Color(0xFFFFE08A) else Color.White.copy(.16f))) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(icon, fontSize = 27.sp); Spacer(Modifier.height(6.dp)); Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) } }
}
private fun periodPresentation(period: DayPeriod) = when (period) { DayPeriod.MORNING -> "🌅" to "Mañana"; DayPeriod.AFTERNOON -> "☀️" to "Tarde"; DayPeriod.NIGHT -> "🌙" to "Noche" }

@Composable fun ActivitySuggestionCard(avatar: String, activityState: ActivityState, selectionRegistrationFailed: Boolean, onAnotherIdea: () -> Unit, onStart: () -> Unit, onFinish: () -> Unit, onRetry: () -> Unit, onRetrySelection: () -> Unit) {
    val scope = rememberCoroutineScope()
    val avatarScale = remember { Animatable(1f) }
    val refreshRotation = remember { Animatable(0f) }
    val celebrationProgress = remember { Animatable(0f) }
    var finishReaction by remember { mutableStateOf(false) }
    fun react() { scope.launch { avatarScale.snapTo(1f); avatarScale.animateTo(1.07f, tween(110)); avatarScale.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) } }
    ChildCard(containerColor = Color(0xFF312E81).copy(.88f), borderColor = Color(0xFFFFE08A).copy(.55f)) { Box { AnimatedContent(targetState = activityState, transitionSpec = { (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(260))) togetherWith (slideOutHorizontally(tween(260)) { -it / 3 } + fadeOut(tween(180))) }, label = "activity") { currentState -> when (currentState) {
    ActivityState.Loading -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(color = Color(0xFFFFE08A))
        Text("Buscando una idea para vos…", color = Color.White.copy(.82f))
    }
    ActivityState.Empty -> Text("No tenemos una sugerencia para mostrar ahora.", Modifier.fillMaxWidth().padding(vertical = 12.dp), color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    is ActivityState.Error -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(currentState.message, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF7F5FF), contentColor = AppColors.PurpleUi)) { Text("Reintentar", fontWeight = FontWeight.Bold) }
    }
    is ActivityState.Suggested -> Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(avatar, Modifier.graphicsLayer { scaleX = avatarScale.value; scaleY = avatarScale.value }, fontSize = 25.sp); Spacer(Modifier.width(8.dp)); Text("Tengo una idea para vos", color = Color(0xFFFFE08A), fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        Text("¿Querés hacer algo diferente?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Surface(color = Color.White.copy(.12f), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color.White.copy(.16f))) { Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) { Text(currentState.activity.emoji, fontSize = 39.sp); Text(currentState.activity.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center); Text(currentState.activity.description, color = Color.White.copy(.78f), fontSize = 13.sp, lineHeight = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
        Button(onClick = { react(); onStart() }, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF7F5FF), contentColor = AppColors.PurpleUi), shape = RoundedCornerShape(14.dp)) { Text("Empezar actividad", fontWeight = FontWeight.Bold) }
        TextButton(onClick = { react(); scope.launch { refreshRotation.snapTo(0f); refreshRotation.animateTo(360f, tween(320, easing = FastOutSlowInEasing)); refreshRotation.snapTo(0f) }; onAnotherIdea() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("🔄", Modifier.graphicsLayer { rotationZ = refreshRotation.value }, color = Color(0xFFFFE08A)); Spacer(Modifier.width(7.dp)); Text("Otra idea", color = Color(0xFFFFE08A), fontWeight = FontWeight.Bold) }
        if (selectionRegistrationFailed) SelectionRetry(onRetrySelection)
    }
    is ActivityState.Running -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(avatar, Modifier.graphicsLayer { scaleX = avatarScale.value; scaleY = avatarScale.value }, fontSize = 28.sp); Spacer(Modifier.width(8.dp)); Text("Actividad en curso", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
        Text(currentState.activity.emoji, fontSize = 42.sp)
        Text(currentState.activity.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(currentState.activity.description, color = Color.White.copy(.72f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Button(onClick = { react(); finishReaction = true; scope.launch { celebrationProgress.snapTo(0f); celebrationProgress.animateTo(1f, tween(1300, easing = FastOutSlowInEasing)); finishReaction = false }; onFinish() }, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF7F5FF), contentColor = AppColors.PurpleUi), shape = RoundedCornerShape(14.dp)) { Text("Finalizar actividad", fontWeight = FontWeight.Bold) }
        if (selectionRegistrationFailed) SelectionRetry(onRetrySelection)
    }
} }; if (finishReaction) CelebrationOverlay(celebrationProgress.value) } } }

@Composable private fun SelectionRetry(onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("No pudimos guardar tu elección.", Modifier.weight(1f), color = Color.White.copy(.68f), fontSize = 12.sp)
        TextButton(onClick = onRetry) { Text("Reintentar", color = Color(0xFFFFE08A), fontSize = 12.sp) }
    }
}

private data class CelebrationParticle(val xFraction: Float, val size: Dp, val travel: Dp, val color: Color, val rotation: Float)
private val celebrationParticles = listOf(
    CelebrationParticle(.08f, 15.dp, 155.dp, Color(0xFFFFD166), -22f),
    CelebrationParticle(.20f, 11.dp, 190.dp, Color(0xFF86EFAC), 28f),
    CelebrationParticle(.33f, 18.dp, 170.dp, Color(0xFFF9A8D4), -35f),
    CelebrationParticle(.46f, 13.dp, 215.dp, Color(0xFF93C5FD), 18f),
    CelebrationParticle(.59f, 16.dp, 180.dp, Color(0xFFC4B5FD), 32f),
    CelebrationParticle(.71f, 10.dp, 205.dp, Color(0xFFFFE08A), -26f),
    CelebrationParticle(.83f, 17.dp, 165.dp, Color(0xFF6EE7B7), 38f),
    CelebrationParticle(.93f, 12.dp, 195.dp, Color(0xFFFDA4AF), -18f),
)

@Composable private fun CelebrationOverlay(progress: Float) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        celebrationParticles.forEachIndexed { index, particle ->
            val delayedProgress = ((progress - index * .025f) / (1f - index * .025f)).coerceIn(0f, 1f)
            val eased = FastOutSlowInEasing.transform(delayedProgress)
            Box(Modifier.align(Alignment.BottomStart).offset(x = maxWidth * particle.xFraction - particle.size / 2).size(particle.size).graphicsLayer { translationY = -with(density) { particle.travel.toPx() } * eased; translationX = kotlin.math.sin(eased * 3.14f + index) * with(density) { 10.dp.toPx() }; rotationZ = particle.rotation * eased; alpha = if (delayedProgress < .72f) 1f else ((1f - delayedProgress) / .28f).coerceIn(0f, 1f); scaleX = .8f + eased * .2f; scaleY = scaleX }.background(particle.color, if (index % 2 == 0) CircleShape else RoundedCornerShape(4.dp)))
        }
        Surface(Modifier.align(Alignment.Center).graphicsLayer { alpha = if (progress < .72f) 1f else ((1f - progress) / .28f).coerceIn(0f, 1f); scaleX = .92f + progress.coerceAtMost(.3f) * .26f; scaleY = scaleX }, color = Color(0xFFF7F5FF), shape = RoundedCornerShape(18.dp), shadowElevation = 6.dp) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("¡Actividad terminada!", color = AppColors.PurpleUi, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("Elegiste hacer algo diferente.", color = Color(0xFF475569), fontSize = 13.sp) }
        }
    }
}

@Composable fun ActivityHistory(chosenActivities: Int) { Surface(color = Color(0xFFDAF5E3).copy(.96f), shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Color.White.copy(.5f))) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text("🌱", fontSize = 25.sp); Spacer(Modifier.width(12.dp)); Text("Hoy elegiste $chosenActivities ${if (chosenActivities == 1) "actividad" else "actividades"}", color = Color(0xFF24543A), fontWeight = FontWeight.Bold) } } }

@Composable private fun ChildCard(containerColor: Color = ChildCardColor, borderColor: Color = ChildBorderColor, content: @Composable () -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = containerColor), border = BorderStroke(1.dp, borderColor), shape = RoundedCornerShape(24.dp)) { Box(Modifier.fillMaxWidth().padding(18.dp)) { content() } } }
private fun formatMinutes(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
private fun formatMinutesLong(minutes: Int): String = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

@Composable fun AdultPinScreen(state: PinUiState, onDigit: (Int) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) { Column(Modifier.fillMaxSize().background(AppColors.Surface).statusBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("👤", color = AppColors.PurpleSoft, fontSize = 58.sp); Spacer(Modifier.height(22.dp)); Text("Panel del adulto", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold); Text("Ingresá tu PIN de 4 dígitos", color = Color(0xFFCBD5E1)); Spacer(Modifier.height(30.dp)); PinDots(state.digits.length, state.error, emptyColor = Color(0xFF64748B), filledColor = AppColors.PurpleSoft); if (state.error) Text("PIN incorrecto. Intentá nuevamente.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)); Spacer(Modifier.height(30.dp)); NumericPinKeyboard(onDigit, onDelete, highContrastDark = true); Spacer(Modifier.height(28.dp)); TextButton(onBack) { Text("← Volver", color = Color(0xFFCBD5E1)) } } }
