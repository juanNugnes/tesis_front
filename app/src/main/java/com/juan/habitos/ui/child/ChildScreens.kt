package com.juan.habitos.ui.child

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.juan.habitos.domain.model.AppUsage
import com.juan.habitos.R
import com.juan.habitos.domain.model.UsageMetricStatus
import com.juan.habitos.ui.components.*
import com.juan.habitos.ui.state.*
import com.juan.habitos.ui.theme.*
import kotlinx.coroutines.launch

private val ChildCardColor = Color.White.copy(alpha = .11f)
private val ChildBorderColor = Color.White.copy(alpha = .20f)

@Composable fun ChildHomeScreen(state: ChildHomeUiState, onLock: () -> Unit, onAnotherActivity: () -> Unit, onStartActivity: () -> Unit, onFinishActivity: () -> Unit, onRetryActivity: () -> Unit, onRetrySelection: () -> Unit, onCompletePreferences: (() -> Unit)? = null, automaticActivitiesContent: (@Composable () -> Unit)? = null) {
    val gradient = Brush.verticalGradient(listOf(Color(0xFF5B21B6), Color(0xFF4F46E5), Color(0xFF2563EB)))
    val listState = rememberLazyListState()
    LaunchedEffect(state.showCompletionCelebration, state.completionCelebrationId) {
        if (state.showCompletionCelebration) listState.animateScrollToItem(1)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(gradient).statusBarsPadding(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ChildHeader(state, onLock) }
        item { DaySceneCard(state.profile.avatar, state.chosenActivities ?: 0, state.showCompletionCelebration, state.completionCelebrationId) }
        item { DayUsageSummary(state.usage.currentMinutes, state.habitualUsageMinutes, state.usage.informationMessage) }
        item { ChildUsageDetails(state.usage.sessionCount, state.apps, state.applicationsStatus) }
        item { ActivitySuggestionCard(state.profile.avatar, state.activityState, state.selectionRegistrationFailed, onAnotherActivity, onStartActivity, onFinishActivity, onRetryActivity, onRetrySelection, onCompletePreferences) }
        if (state.periodUsage.isNotEmpty()) item { DayPeriodUsageCard(state.periodUsage) }
        item { ActivityHistory(state.chosenActivities) }
        if (automaticActivitiesContent != null) item { automaticActivitiesContent() }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable private fun ChildHeader(state: ChildHomeUiState, onLock: () -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Surface(Modifier.size(64.dp), shape = CircleShape, color = Color(0xFFFFF4D8), border = BorderStroke(2.dp, Color.White.copy(.55f))) { Box(contentAlignment = Alignment.Center) { Text(state.profile.avatar, fontSize = 34.sp) } }; Spacer(Modifier.width(14.dp)); Text("¡Hola, ${state.profile.name}!", Modifier.weight(1f), color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold); FilledTonalIconButton(onLock, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(.15f), contentColor = Color.White)) { Text("🔒") } } }

@Composable private fun DaySceneCard(avatar: String, chosenActivities: Int, showCompletionCelebration: Boolean, completionCelebrationId: Long) {
    val idle = rememberInfiniteTransition(label = "day-scene-idle")
    val avatarOffset by idle.animateFloat(-3f, 3f, infiniteRepeatable(tween(3600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "avatar-float")
    val cloudOffset by idle.animateFloat(-8f, 10f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "cloud-drift")
    val celebrationProgress = remember { Animatable(0f) }
    LaunchedEffect(showCompletionCelebration, completionCelebrationId) {
        if (showCompletionCelebration) {
            celebrationProgress.snapTo(0f)
            celebrationProgress.animateTo(1f, tween(2_400, easing = LinearEasing))
        } else celebrationProgress.snapTo(0f)
    }
    val celebrationBounce = if (showCompletionCelebration) {
        1f + kotlin.math.sin(celebrationProgress.value * kotlin.math.PI.toFloat() * 3f).coerceAtLeast(0f) * .12f
    } else 1f
    ChildCard(containerColor = Color(0xFFE9F8EE).copy(.96f), borderColor = Color.White.copy(.55f)) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("🌱  Tu día", color = Color(0xFF24543A), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(listOf(Color(0xFFBDE9FF), Color(0xFFE9F8D8))))) {
            Text("☁️", Modifier.align(Alignment.TopStart).padding(start = 22.dp, top = 12.dp).graphicsLayer { translationX = cloudOffset }, fontSize = 23.sp)
            Text("☁️", Modifier.align(Alignment.TopCenter).padding(top = 30.dp).graphicsLayer { translationX = -cloudOffset * .65f; alpha = .78f }, fontSize = 17.sp)
            Box(Modifier.size(94.dp).offset(x = (-18).dp, y = 96.dp).background(Color(0xFF8ED081), CircleShape))
            Box(Modifier.size(150.dp).align(Alignment.BottomEnd).offset(x = 32.dp, y = 78.dp).background(Color(0xFF72C979), CircleShape))
            Text("☀️", Modifier.align(Alignment.TopEnd).padding(14.dp), fontSize = 27.sp)
            Text(avatar, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).graphicsLayer { translationY = avatarOffset; scaleX = celebrationBounce; scaleY = celebrationBounce }, fontSize = 43.sp)
            val details = listOf("🌿", "🌼", "🦋", "⭐")
            details.forEachIndexed { index, detail -> val visible = index < chosenActivities.coerceIn(0, details.size); val detailScale by animateFloatAsState(if (visible) 1f else .7f, tween(350, easing = FastOutSlowInEasing), label = "day-detail-scale-$index"); val detailAlpha by animateFloatAsState(if (visible) 1f else 0f, tween(280), label = "day-detail-alpha-$index"); Text(detail, Modifier.align(if (index % 2 == 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = (18 + index * 7).dp, vertical = (8 + index * 4).dp).graphicsLayer { scaleX = detailScale; scaleY = detailScale; alpha = detailAlpha }, fontSize = 22.sp) }
            if (showCompletionCelebration) SceneCompletionCelebration(celebrationProgress.value)
        }
    } }
}

internal fun dayUsageMessage(todayMinutes: Int?, habitualMinutes: Int?, informationMessage: String?, timeVisible: Boolean): String = when {
    todayMinutes == null -> informationMessage ?: "Todavía no hay información de uso para hoy"
    timeVisible -> "Hoy llevás ${formatMinutesLong(todayMinutes)} usando tu celular"
    habitualMinutes == null -> "Ya hay información disponible sobre el uso de hoy"
    todayMinutes - habitualMinutes > 15 -> "Hoy usaste el celu un poquito más que otros días"
    todayMinutes - habitualMinutes < -15 -> "Hoy usaste el celu un poquito menos que otros días"
    else -> "Hoy viene parecido a otros días"
}

@Composable private fun DayUsageSummary(todayMinutes: Int?, habitualMinutes: Int?, informationMessage: String?) {
    var timeVisible by rememberSaveable { mutableStateOf(false) }
    ChildCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Así viene tu día", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            AnimatedContent(targetState = timeVisible, label = "day-usage-visibility") { visible ->
                Text(dayUsageMessage(todayMinutes, habitualMinutes, informationMessage, visible), color = Color.White.copy(.82f), lineHeight = 20.sp)
            }
            if (todayMinutes != null) {
                TextButton(onClick = { timeVisible = !timeVisible }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(if (timeVisible) "Ocultar mi tiempo ↑" else "Ver mi tiempo →", color = Color(0xFFFFE08A), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

internal fun applicationsStateMessage(apps: List<AppUsage>?, status: UsageMetricStatus): String? = when {
    apps == null && status == UsageMetricStatus.PERMISSION_DENIED -> "Falta el permiso de uso para ver aplicaciones"
    apps == null -> "No hay información disponible sobre las aplicaciones"
    apps.isEmpty() -> "No se registraron aplicaciones hoy"
    else -> null
}

internal fun visibleApplications(apps: List<AppUsage>, expanded: Boolean): List<AppUsage> =
    apps.sortedByDescending(AppUsage::minutes).take(if (expanded) 10 else 5)

internal fun shouldShowApplicationExpansion(applicationCount: Int): Boolean = applicationCount > 5

internal fun childSessionSummary(sessionCount: Int?): String = when (sessionCount) {
    null -> "Todavía no hay información sobre tus momentos de uso"
    0 -> "Hoy no se registraron momentos de uso"
    1 -> "¡Hoy agarraste el celu 1 vez! 📱"
    else -> "¡Hoy agarraste el celu $sessionCount veces! 📱"
}

@Composable private fun ChildApplicationRow(app: AppUsage) {
    val visual = rememberApplicationVisualPresentation(app)
    Surface(color = Color.White.copy(.09f), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color.White.copy(.12f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(42.dp), color = Color.White.copy(.92f), shape = RoundedCornerShape(12.dp)) {
                Box(Modifier.padding(7.dp), contentAlignment = Alignment.Center) {
                    if (visual.installedIcon != null) Image(visual.installedIcon, contentDescription = "Ícono de ${visual.displayName}", modifier = Modifier.fillMaxSize())
                    else Text(visual.fallbackIcon, fontSize = 21.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(visual.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatMinutesLong(app.minutes), color = Color(0xFFFFE08A), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable private fun ChildUsageDetails(sessionCount: Int?, apps: List<AppUsage>?, applicationsStatus: UsageMetricStatus) {
    var showAll by rememberSaveable(apps?.size) { mutableStateOf(false) }
    val sortedApps = remember(apps) { apps?.sortedByDescending(AppUsage::minutes) }
    val displayedApps = remember(apps, showAll) { visibleApplications(apps.orEmpty(), showAll) }
    ChildCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tus aplicaciones de hoy", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Surface(color = Color.White.copy(.08f), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("◷", color = Color(0xFFFFE08A), fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        childSessionSummary(sessionCount), modifier = Modifier.weight(1f),
                        color = Color.White.copy(.82f), fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        lineHeight = 22.sp, softWrap = true,
                    )
                }
            }
            applicationsStateMessage(sortedApps, applicationsStatus)?.let { message ->
                Text(message, color = Color.White.copy(.72f), lineHeight = 20.sp)
            } ?: displayedApps.forEach { app -> ChildApplicationRow(app) }
            if (sortedApps != null && shouldShowApplicationExpansion(sortedApps.size)) {
                TextButton(onClick = { showAll = !showAll }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (showAll) "Ver menos" else "Ver más aplicaciones", color = Color(0xFFFFE08A), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

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

data class ActivityUnavailablePresentation(val message: String, val actionLabel: String? = null)

fun activityUnavailablePresentation(state: ActivityState, navigationAvailable: Boolean): ActivityUnavailablePresentation? = when (state) {
    ActivityState.PreferencesRequired -> ActivityUnavailablePresentation(
        "Antes de mostrarte actividades, un adulto tiene que completar tus preferencias.",
        "Completar preferencias".takeIf { navigationAvailable },
    )
    ActivityState.NoEligibleActivities -> ActivityUnavailablePresentation("Por ahora no encontramos una actividad para mostrarte.")
    is ActivityState.Error -> ActivityUnavailablePresentation(state.message)
    else -> null
}

@Composable fun ActivitySuggestionCard(avatar: String, activityState: ActivityState, selectionRegistrationFailed: Boolean, onAnotherIdea: () -> Unit, onStart: () -> Unit, onFinish: () -> Unit, onRetry: () -> Unit, onRetrySelection: () -> Unit, onCompletePreferences: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val avatarScale = remember { Animatable(1f) }
    val refreshRotation = remember { Animatable(0f) }
    fun react() { scope.launch { avatarScale.snapTo(1f); avatarScale.animateTo(1.07f, tween(110)); avatarScale.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) } }
    ChildCard(containerColor = Color(0xFF312E81).copy(.88f), borderColor = Color(0xFFFFE08A).copy(.55f)) { Box { AnimatedContent(targetState = activityState, transitionSpec = { (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(260))) togetherWith (slideOutHorizontally(tween(260)) { -it / 3 } + fadeOut(tween(180))) }, label = "activity") { currentState -> when (currentState) {
    ActivityState.Loading -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(color = Color(0xFFFFE08A))
        Text("Buscando una idea para vos…", color = Color.White.copy(.82f))
    }
    ActivityState.PreferencesRequired -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(activityUnavailablePresentation(currentState, onCompletePreferences != null)!!.message, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        onCompletePreferences?.let { action -> Button(onClick = action, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF7F5FF), contentColor = AppColors.PurpleUi)) { Text("Completar preferencias", fontWeight = FontWeight.Bold) } }
    }
    ActivityState.NoEligibleActivities -> Text(activityUnavailablePresentation(currentState, false)!!.message, Modifier.fillMaxWidth().padding(vertical = 12.dp), color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    is ActivityState.Error -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(currentState.message, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF7F5FF), contentColor = AppColors.PurpleUi)) { Text("Reintentar", fontWeight = FontWeight.Bold) }
    }
    is ActivityState.Suggested -> Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(avatar, Modifier.graphicsLayer { scaleX = avatarScale.value; scaleY = avatarScale.value }, fontSize = 25.sp); Spacer(Modifier.width(8.dp)); Text("Tengo una idea para vos", color = Color(0xFFFFE08A), fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        Text("¿Querés hacer algo diferente?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Surface(color = Color.White.copy(.12f), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color.White.copy(.16f))) { Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(currentState.activity.emoji, fontSize = 39.sp); Text(currentState.activity.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center); ActivityInstructions(currentState.activity.description) } }
        Button(onClick = { react(); onStart() }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF7F5FF), contentColor = AppColors.PurpleUi), shape = RoundedCornerShape(14.dp)) { Text("Empezar actividad", fontWeight = FontWeight.Bold) }
        TextButton(onClick = { react(); scope.launch { refreshRotation.snapTo(0f); refreshRotation.animateTo(360f, tween(320, easing = FastOutSlowInEasing)); refreshRotation.snapTo(0f) }; onAnotherIdea() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("🔄", Modifier.graphicsLayer { rotationZ = refreshRotation.value }, color = Color(0xFFFFE08A)); Spacer(Modifier.width(7.dp)); Text("Otra idea", color = Color(0xFFFFE08A), fontWeight = FontWeight.Bold) }
        if (selectionRegistrationFailed) SelectionRetry(onRetrySelection)
    }
    is ActivityState.Running -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(avatar, Modifier.graphicsLayer { scaleX = avatarScale.value; scaleY = avatarScale.value }, fontSize = 28.sp); Spacer(Modifier.width(8.dp)); Text("Actividad en curso", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
        Text(currentState.activity.emoji, fontSize = 42.sp)
        Text(currentState.activity.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        ActivityInstructions(currentState.activity.description)
        activityRunningMessage(currentState)?.let { message ->
            // Split only this fixed UI message, never the activity's description or steps.
            val (headline, clarification) = message.split(". ", limit = 2)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Outlined.PhoneAndroid, contentDescription = null,
                    modifier = Modifier.padding(top = 2.dp).size(20.dp), tint = Color(0xFFB9F6D1))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("$headline.", color = Color(0xFFB9F6D1), fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, softWrap = true)
                    Text("$clarification.", color = Color.White.copy(.82f), fontSize = 14.sp,
                        lineHeight = 20.sp, softWrap = true)
                }
            }
        }
        Button(onClick = { react(); onFinish() }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF7F5FF), contentColor = AppColors.PurpleUi), shape = RoundedCornerShape(14.dp)) { Text("Finalizar actividad", fontWeight = FontWeight.Bold) }
        if (selectionRegistrationFailed) SelectionRetry(onRetrySelection)
    }
} } } } }

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

private data class CelebrationBalloon(val xFraction: Float, val emoji: String, val travel: Dp, val delay: Float)
private val celebrationBalloons = listOf(
    CelebrationBalloon(.12f, "🎈", 145.dp, 0f),
    CelebrationBalloon(.77f, "🎈", 165.dp, .08f),
    CelebrationBalloon(.89f, "🎈", 135.dp, .16f),
)

@Composable private fun SceneCompletionCelebration(progress: Float) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        celebrationBalloons.forEachIndexed { index, balloon ->
            val balloonProgress = ((progress - balloon.delay) / (1f - balloon.delay)).coerceIn(0f, 1f)
            Text(
                balloon.emoji,
                Modifier.align(Alignment.BottomStart).offset(x = maxWidth * balloon.xFraction - 14.dp).graphicsLayer {
                    translationY = -with(density) { balloon.travel.toPx() } * FastOutSlowInEasing.transform(balloonProgress)
                    translationX = kotlin.math.sin(balloonProgress * 5f + index) * with(density) { 7.dp.toPx() }
                    alpha = if (balloonProgress < .78f) 1f else ((1f - balloonProgress) / .22f).coerceIn(0f, 1f)
                },
                fontSize = 25.sp,
            )
        }
        celebrationParticles.forEachIndexed { index, particle ->
            val delayedProgress = ((progress - index * .018f) / (1f - index * .018f)).coerceIn(0f, 1f)
            val eased = FastOutSlowInEasing.transform(delayedProgress)
            Box(Modifier.align(Alignment.BottomStart).offset(x = maxWidth * particle.xFraction - particle.size / 2).size(particle.size).graphicsLayer { translationY = -with(density) { (particle.travel * .72f).toPx() } * eased; translationX = kotlin.math.sin(eased * 3.14f + index) * with(density) { 8.dp.toPx() }; rotationZ = particle.rotation * eased; alpha = if (delayedProgress < .76f) .9f else ((1f - delayedProgress) / .24f).coerceIn(0f, 1f); scaleX = .7f + eased * .25f; scaleY = scaleX }.background(particle.color, if (index % 2 == 0) CircleShape else RoundedCornerShape(4.dp)))
        }
        Surface(Modifier.align(Alignment.Center).graphicsLayer { alpha = when { progress < .12f -> progress / .12f; progress < .78f -> 1f; else -> ((1f - progress) / .22f).coerceIn(0f, 1f) }; scaleX = .9f + progress.coerceAtMost(.22f) * .45f; scaleY = scaleX }, color = Color.White.copy(.94f), shape = RoundedCornerShape(16.dp), shadowElevation = 5.dp) {
            Text("¡Actividad completada!", Modifier.padding(horizontal = 18.dp, vertical = 11.dp), color = AppColors.PurpleUi, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

fun activityHistoryMessage(chosenActivities: Int?): String = when (chosenActivities) {
    null -> "Tus actividades de hoy aparecerán acá"
    0 -> "Hoy todavía no elegiste actividades"
    1 -> "actividad elegida hoy"
    else -> "actividades elegidas hoy"
}

internal data class ActivityHistoryPresentation(val count: Int?, val title: String, val description: String)

internal fun activityHistoryPresentation(chosenActivities: Int?) = ActivityHistoryPresentation(
    count = chosenActivities?.takeIf { it > 0 },
    title = activityHistoryMessage(chosenActivities),
    description = when (chosenActivities) {
        null -> "Todavía no hay información disponible sobre las actividades elegidas."
        0 -> "¡Cuando quieras, te voy a proponer una idea!"
        1 -> "¡Qué lindo! Seguí sumando momentos diferentes."
        else -> "¡Genial! Seguí explorando nuevas ideas."
    },
)

@Composable fun ActivityHistory(chosenActivities: Int?) {
    val presentation = activityHistoryPresentation(chosenActivities)
    // Informational Surface: no onClick, interaction modifier, or navigation action.
    Surface(color = Color(0xFFE2F7EF), shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Color(0xFFCFEBDD))) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(14.dp)) {
            val illustrationWidth = if (maxWidth < 280.dp) 64.dp else 80.dp
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    painter = painterResource(R.drawable.ic_activity_history_plant),
                    contentDescription = null,
                    modifier = Modifier.width(illustrationWidth).aspectRatio(96f / 112f),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActivityHistoryHeading(presentation)
                    Text(presentation.description, color = Color(0xFF497C66), fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

@Composable private fun ActivityHistoryHeading(presentation: ActivityHistoryPresentation) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        val badge: @Composable () -> Unit = {
            presentation.count?.let { count ->
                Surface(color = Color(0xFFBCE78B), shape = CircleShape) {
                    Box(Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp).padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                        Text(count.toString(), color = Color(0xFF174A34), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
        val title: @Composable (Modifier) -> Unit = { modifier ->
            Text(presentation.title, modifier, color = Color(0xFF174A34), fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
        }
        if (presentation.count == null) title(Modifier)
        else if (maxWidth >= 180.dp && fontScale <= 1.3f && presentation.count.toString().length <= 3) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                badge()
                title(Modifier.weight(1f))
            }
        } else {
            // Keep the informative horizontal card, but wrap its heading for narrow/large-text layouts.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { badge(); title(Modifier) }
        }
    }
}

@Composable internal fun ChildCard(containerColor: Color = ChildCardColor, borderColor: Color = ChildBorderColor, content: @Composable () -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = containerColor), border = BorderStroke(1.dp, borderColor), shape = RoundedCornerShape(24.dp)) { Box(Modifier.fillMaxWidth().padding(18.dp)) { content() } } }
private fun formatMinutesLong(minutes: Int): String = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

@Composable fun AdultPinScreen(state: PinUiState, onDigit: (Int) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) { Column(Modifier.fillMaxSize().background(AppColors.Surface).statusBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("👤", color = AppColors.PurpleSoft, fontSize = 58.sp); Spacer(Modifier.height(22.dp)); Text("Panel del adulto", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold); Text("Ingresá tu PIN de 4 dígitos", color = Color(0xFFCBD5E1)); Spacer(Modifier.height(30.dp)); PinDots(state.digits.length, state.error, emptyColor = Color(0xFF64748B), filledColor = AppColors.PurpleSoft); if (state.error) Text("PIN incorrecto. Intentá nuevamente.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)); Spacer(Modifier.height(30.dp)); NumericPinKeyboard(onDigit, onDelete, highContrastDark = true); Spacer(Modifier.height(28.dp)); TextButton(onBack) { Text("← Volver", color = Color(0xFFCBD5E1)) } } }
