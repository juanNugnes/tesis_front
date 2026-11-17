package com.juan.habitos.ui.adult

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juan.habitos.domain.model.*
import com.juan.habitos.ui.state.ChildPreferencesUiState
import com.juan.habitos.ui.state.PreferencesFormUiState
import com.juan.habitos.ui.state.ParentalScreenTimeUiState
import com.juan.habitos.ui.state.ProfileScheduleUiState
import com.juan.habitos.ui.state.AutomaticOfferSettingsUiState
import com.juan.habitos.ui.theme.*
import java.time.LocalDate

private val interestLabels = linkedMapOf(
    ChildInterest.DRAW_CREATE to "Dibujar o crear", ChildInterest.STORIES to "Leer o escuchar historias",
    ChildInterest.MUSIC to "Música", ChildInterest.GAMES_CHALLENGES to "Juegos y desafíos",
    ChildInterest.OUTDOORS to "Actividades al aire libre", ChildInterest.MOVEMENT to "Movimiento",
    ChildInterest.BUILD to "Construir o armar cosas", ChildInterest.WRITE to "Escribir",
    ChildInterest.IMAGINE_ACT to "Imaginar o representar historias",
)
internal const val CHILD_CONSIDERATIONS_QUESTION = "¿Tu hijo/a tiene alguna dificultad de movimiento, visual, auditiva o sensibilidad a los sonidos que debamos considerar?"
private val considerationLabels = linkedMapOf(
    ChildConsideration.UPPER_BODY_MOBILITY to "Brazos/manos",
    ChildConsideration.LOWER_BODY_MOBILITY to "Piernas",
    ChildConsideration.VISUAL to "Visual",
    ChildConsideration.AUDITORY to "Auditiva",
    ChildConsideration.SOUND_SENSITIVITY to "Sensibilidad a sonidos",
)
private val restrictionLabels = linkedMapOf(
    ActivityRestriction.RUN_JUMP to "Correr o saltar", ActivityRestriction.STANDING to "Actividades de pie",
    ActivityRestriction.ARMS_HANDS to "Movimientos con brazos o manos", ActivityRestriction.VISUAL_PRECISION to "Actividades que requieren precisión visual",
    ActivityRestriction.SOUNDS to "Actividades con sonidos", ActivityRestriction.MANY_STIMULI to "Actividades con muchos estímulos",
    ActivityRestriction.HIGH_CONCENTRATION to "Actividades que requieren mucha concentración", ActivityRestriction.NONE to "Ninguna de estas",
)

@Composable fun AdultProfileScreen(profile: Profile, onEditProfile: () -> Unit, onSchedules: () -> Unit, onPreferences: () -> Unit, onContexts: (() -> Unit)?, onLinkedDevices: (() -> Unit)?, onMonitoringConsent: (() -> Unit)?, onAdultLinkCode: (() -> Unit)?, onUsageDiagnostics: (() -> Unit)?, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(AppColors.Surface).statusBarsPadding(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onBack) { Text("←", color = Color.White, fontSize = 24.sp) }; Text("Perfil de ${profile.name}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold) } }
        item { Card(colors = CardDefaults.cardColors(containerColor = AppColors.Card), shape = RoundedCornerShape(18.dp)) { Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Text(profile.avatar, fontSize = 46.sp); Spacer(Modifier.width(14.dp)); Text("${profile.name} · ${profile.age} años", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) } } }
        item { ProfileGroup("Información", "👤", "Datos del perfil", "Nombre, edad y avatar") { openProfileEditor(onEditProfile) } }
        item { ProfileGroup("Rutinas", "🕒", "Horarios de escuela y sueño", profileScheduleSummary(profile), onSchedules) }
        item { ProfileGroup("Actividades", "🌿", "Preferencias y necesidades", "Personalizá las actividades sugeridas", onPreferences) }
        if (onContexts != null) item { ProfileGroup("Rutinas", "📅", "Contexto", "Registrá situaciones y cambios de rutina", onContexts) }
        if (onLinkedDevices != null) item { ProfileGroup("Seguridad", "📱", "Dispositivos vinculados", "Consultá y desvinculá dispositivos infantiles", onLinkedDevices) }
        if (onMonitoringConsent != null) item { ProfileGroup("Privacidad", "🔒", "Autorización de monitoreo", "Administrá el consentimiento para registrar el uso", onMonitoringConsent) }
        if (onAdultLinkCode != null) item { ProfileGroup("Vinculación", "🔗", "Vincular a un adulto responsable", "Generá un código temporal para este perfil", onAdultLinkCode) }
        item { ProfileGroup("Seguridad", "🔐", "Cambiar PIN") }
        if (onUsageDiagnostics != null) item { ProfileGroup("Herramientas de desarrollo", "🛠️", "Diagnóstico de uso", "Solo disponible en versión de desarrollo", onUsageDiagnostics) }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

internal fun openProfileEditor(onEditProfile: () -> Unit) = onEditProfile()

@Composable fun ProfileScheduleScreen(
    state: ProfileScheduleUiState,
    onSchoolStart: (String) -> Unit, onSchoolEnd: (String) -> Unit,
    onSleepStart: (String) -> Unit, onSleepEnd: (String) -> Unit,
    onSave: () -> Unit, onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.saved) { if (state.saved) snackbar.showSnackbar("Horarios actualizados") }
    LaunchedEffect(state.errorMessage) { state.errorMessage?.let { snackbar.showSnackbar(it) } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = AppColors.Surface) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).statusBarsPadding(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onBack) { Text("←", color = Color.White, fontSize = 24.sp) }; Column { Text("Horarios de escuela y sueño", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("Actualizá las rutinas configuradas para ${state.profile?.name.orEmpty()}.", color = Color(0xFFCBD5E1), fontSize = 13.sp) } } }
            item { ScheduleSection("🏫", "Horario de escuela", state.profile?.schoolStart.orEmpty(), state.profile?.schoolEnd.orEmpty(), onSchoolStart, onSchoolEnd) }
            item { ScheduleSection("🌙", "Horario de sueño", state.profile?.sleepStart.orEmpty(), state.profile?.sleepEnd.orEmpty(), onSleepStart, onSleepEnd) }
            item { Text("Usá formato de 24 horas: HH:mm.", color = Color(0xFF94A3B8), fontSize = 13.sp) }
            item { Button(onSave, Modifier.fillMaxWidth().height(54.dp), enabled = state.valid && !state.submitting, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple), shape = RoundedCornerShape(14.dp)) { Text(if (state.submitting) "Guardando…" else "Guardar cambios", fontWeight = FontWeight.Bold) } }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

@Composable private fun ScheduleSection(icon: String, title: String, start: String, end: String, onStart: (String) -> Unit, onEnd: (String) -> Unit) { FormSection("$icon  $title") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(start, onStart, Modifier.weight(1f), label = { Text("Desde") }, placeholder = { Text("08:00") }, singleLine = true, colors = darkTextFieldColors()); OutlinedTextField(end, onEnd, Modifier.weight(1f), label = { Text("Hasta") }, placeholder = { Text("12:30") }, singleLine = true, colors = darkTextFieldColors()) } } }

internal fun profileScheduleSummary(profile: Profile): String {
    val values = listOf(profile.schoolStart, profile.schoolEnd, profile.sleepStart, profile.sleepEnd).map { formatProfileTime(it) }
    if (values.any { it == null } || values.all { it == "00:00" }) return "Sin configurar"
    return "Escuela ${values[0]}–${values[1]} · Sueño ${values[2]}–${values[3]}"
}

private fun formatProfileTime(value: String?): String? {
    val parts = value?.trim()?.split(":") ?: return null
    if (parts.size !in 2..3) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return "%02d:%02d".format(hour, minute)
}

@Composable private fun ProfileGroup(section: String, icon: String, title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { Text(section.uppercase(), color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold); Surface(onClick = onClick ?: {}, enabled = onClick != null, color = AppColors.Card, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, AppColors.Divider)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(icon, fontSize = 24.sp); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(title, color = Color.White, fontWeight = FontWeight.Bold); subtitle?.let { Text(it, color = Color(0xFFCBD5E1), fontSize = 13.sp) } }; if (onClick != null) Text("›", color = AppColors.PurpleSoft, fontSize = 26.sp) } } } }

@Composable fun PreferencesOnboardingIntro(name: String, onConfigure: () -> Unit, onSkip: () -> Unit) { Column(Modifier.fillMaxSize().background(AppColors.Surface).statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) { Text("🌿", fontSize = 52.sp); Spacer(Modifier.height(18.dp)); Text("Personalizá sus actividades", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(10.dp)); Text("Podés contarnos qué cosas le gustan a $name y si hay algo que debamos tener en cuenta al recomendar actividades.", color = Color(0xFFCBD5E1), lineHeight = 22.sp); Spacer(Modifier.height(8.dp)); Text("Es opcional y podés configurarlo más adelante.", color = Color(0xFF94A3B8), fontSize = 13.sp); Spacer(Modifier.height(28.dp)); Button(onConfigure, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)) { Text("Configurar ahora", fontWeight = FontWeight.Bold) }; TextButton(onSkip, Modifier.fillMaxWidth()) { Text("Ahora no", color = Color(0xFFCBD5E1)) } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ChildPreferencesScreen(
    state: ChildPreferencesUiState,
    onInterest: (ChildInterest) -> Unit, onOmitConsiderations: () -> Unit, onNoConsiderations: () -> Unit,
    onConsideration: (ChildConsideration) -> Unit,
    onConsiderationDuration: (ChildConsideration, ConsiderationDuration) -> Unit,
    onConsiderationValidUntil: (ChildConsideration, LocalDate) -> Unit,
    onRestriction: (ActivityRestriction) -> Unit, onNotes: (String) -> Unit, onSave: () -> Unit, onBack: () -> Unit,
    onSimulatedScreenHabits: (ParentalScreenTimeSelections) -> Unit,
    formState: PreferencesFormUiState? = null,
    parentalScreenTimeState: ParentalScreenTimeUiState? = null,
    onParentalSex: (ParentalSexChoice?) -> Unit = {},
    onParentalWeekday: (ParentalTimeChoice?) -> Unit = {},
    onParentalWeekend: (ParentalTimeChoice?) -> Unit = {},
    onLogin: () -> Unit = {},
    onRetry: () -> Unit = {},
    automaticOfferSettings: AutomaticOfferSettingsUiState? = null,
    onAutomaticOffersEnabled: (Boolean) -> Unit = {},
    onSharedPhone: (Boolean) -> Unit = {},
    onSaveAutomaticOfferSettings: () -> Unit = {},
) {
    val preferences = state.preferences
    val snackbar = remember { SnackbarHostState() }
    var datePickerFor by remember { mutableStateOf<ChildConsideration?>(null) }
    LaunchedEffect(state.saved) { if (state.saved && formState == null) snackbar.showSnackbar("Preferencias actualizadas") }
    LaunchedEffect(state.errorMessage) { if (formState == null) state.errorMessage?.let { snackbar.showSnackbar(it) } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = AppColors.Surface) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).statusBarsPadding(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onBack) { Text("←", color = Color.White, fontSize = 24.sp) }; Column { Text("Preferencias y necesidades", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("Contanos un poco más sobre ${state.childName} para adaptar mejor las actividades sugeridas.", color = Color(0xFFCBD5E1), fontSize = 13.sp, lineHeight = 19.sp) } } }
            if (state.refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (formState != null) {
                if (formState.authenticationRequired) item {
                    Column {
                        Text("Tu sesión adulta venció. Tus cambios siguen en el formulario.", color = Color(0xFFFCA5A5))
                        TextButton(onLogin) { Text("Iniciar sesión como adulto", color = AppColors.PurpleSoft) }
                    }
                } else if (state.errorMessage != null) item {
                    Column {
                        Text(state.errorMessage, color = Color(0xFFFCA5A5))
                        TextButton(onRetry, enabled = !state.refreshing && !formState.saving) { Text("Reintentar consulta", color = AppColors.PurpleSoft) }
                    }
                }
            }
            item { Text("Esta información es opcional y podés cambiarla cuando quieras.", color = Color(0xFF94A3B8), fontSize = 13.sp) }
            item { FormSection("¿Qué cosas disfruta hacer ${state.childName}?", "Podés elegir más de una.") { SelectableOptions(interestLabels, preferences.interests, onInterest) } }
            item { FormSection(CHILD_CONSIDERATIONS_QUESTION, "Podés seleccionar más de una opción.") {
                SelectableOptions(considerationLabels, preferences.considerations.keys, onConsideration)
                SingleChoice("Ninguna", preferences.considerationsAnswered && preferences.considerations.isEmpty(), onClick = onNoConsiderations)
                TextButton(onOmitConsiderations) { Text(if (preferences.considerationsAnswered) "Omitir esta pregunta" else "Pregunta omitida", color = Color(0xFFCBD5E1)) }
            } }
            considerationLabels.forEach { (type, label) -> preferences.considerations[type]?.let { preference ->
                item { FormSection(label) {
                    SingleChoice("Permanente", preference.durationType == ConsiderationDuration.PERMANENT) { onConsiderationDuration(type, ConsiderationDuration.PERMANENT) }
                    SingleChoice("Temporal", preference.durationType == ConsiderationDuration.TEMPORARY) { onConsiderationDuration(type, ConsiderationDuration.TEMPORARY) }
                    if (preference.durationType == ConsiderationDuration.TEMPORARY) {
                        OutlinedButton(onClick = { datePickerFor = type }, modifier = Modifier.fillMaxWidth()) { Text(preference.validUntil?.let { "Hasta cuándo: $it" } ?: "Hasta cuándo") }
                        if (preference.validUntil == null) Text("Seleccioná una fecha.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        else if (preference.validUntil.isBefore(state.today)) Text("La fecha no puede ser anterior a hoy.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                } }
            } }
            if (preferences.considerations.isNotEmpty()) {
                item { FormSection("¿Hay algún tipo de actividad que convenga evitar?") { val noteLimit = if (preferences.additionalNotes.orEmpty().length > 240) 500 else 240; SelectableOptions(restrictionLabels, preferences.activitiesToAvoid, onRestriction); OutlinedTextField(preferences.additionalNotes.orEmpty(), onNotes, Modifier.fillMaxWidth(), label = { Text("Otro aspecto a tener en cuenta (opcional)") }, supportingText = { Text("${preferences.additionalNotes.orEmpty().length}/$noteLimit") }, minLines = 2, maxLines = 4, colors = darkTextFieldColors()) } }
            }
            item { Surface(color = AppColors.Purple.copy(.12f), shape = RoundedCornerShape(14.dp)) { Text("🔒  Esta información se utiliza únicamente para adaptar las actividades sugeridas.", Modifier.padding(14.dp), color = Color(0xFFCBD5E1), fontSize = 13.sp, lineHeight = 18.sp) } }
            item {
                val screenTime = parentalScreenTimeState
                val selections = screenTime?.selections ?: state.simulatedScreenHabits
                ParentalScreenTimeSection(state.age, selections,
                    { if (screenTime == null) onSimulatedScreenHabits(selections.copy(sex = it)) else onParentalSex(it) },
                    { if (screenTime == null) onSimulatedScreenHabits(selections.copy(weekday = it)) else onParentalWeekday(it) },
                    { if (screenTime == null) onSimulatedScreenHabits(selections.copy(weekend = it)) else onParentalWeekend(it) },
                    persistedState = screenTime,
                )
            }
            automaticOfferSettings?.let { automatic -> item {
                FormSection("Ofertas automáticas", "Se evalúan después de un período continuo de uso. La configuración se guarda por separado.") {
                    if (automatic.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Habilitar ofertas automáticas", Modifier.weight(1f), color = Color.White)
                        Switch(automatic.automaticEnabled, onAutomaticOffersEnabled, enabled = automatic.loaded && !automatic.saving)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Este teléfono es compartido", Modifier.weight(1f), color = Color.White)
                        Switch(automatic.sharedPhone, onSharedPhone, enabled = automatic.loaded && !automatic.saving)
                    }
                    automatic.error?.let { Text(it, color = Color(0xFFFCA5A5), fontSize = 13.sp) }
                    if (automatic.saved) Text("Configuración de ofertas automáticas guardada.", color = AppColors.PurpleSoft, fontSize = 13.sp)
                    Button(
                        onSaveAutomaticOfferSettings,
                        Modifier.fillMaxWidth(),
                        enabled = automatic.loaded && automatic.hasChanges && !automatic.saving,
                    ) { Text(if (automatic.saving) "Guardando…" else "Guardar ofertas automáticas") }
                }
            } }
            formState?.message?.let { message -> item { Text(message, color = AppColors.PurpleSoft) } }
            item { Button(onSave, Modifier.fillMaxWidth().height(54.dp), enabled = formState?.canSave ?: (state.loaded && state.valid && !state.submitting && !state.refreshing), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple), shape = RoundedCornerShape(14.dp)) { Text(if (state.submitting || formState?.saving == true) "Guardando…" else "Guardar cambios", fontWeight = FontWeight.Bold) } }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
    datePickerFor?.let { type -> val picker = rememberDatePickerState(initialSelectedDateMillis = (preferences.considerations[type]?.validUntil ?: state.today).toEpochDay().times(86_400_000)); DatePickerDialog(onDismissRequest = { datePickerFor = null }, confirmButton = { TextButton({ picker.selectedDateMillis?.let { onConsiderationValidUntil(type, LocalDate.ofEpochDay(it / 86_400_000)) }; datePickerFor = null }) { Text("Aceptar") } }, dismissButton = { TextButton({ datePickerFor = null }) { Text("Cancelar") } }) { DatePicker(picker) } }
}

@Composable internal fun FormSection(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = AppColors.Card), border = BorderStroke(1.dp, AppColors.Divider), shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold); subtitle?.let { Text(it, color = Color(0xFF94A3B8), fontSize = 13.sp) }; content() } } }
@Composable private fun <T> SelectableOptions(options: Map<T, String>, selected: Set<T>, onToggle: (T) -> Unit) { options.forEach { (value, label) -> val checked = value in selected; Surface(onClick = { onToggle(value) }, color = if (checked) AppColors.Purple.copy(.22f) else AppColors.Surface, border = BorderStroke(1.dp, if (checked) AppColors.PurpleSoft else AppColors.Divider), shape = RoundedCornerShape(13.dp)) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (checked) "✓" else "○", color = if (checked) AppColors.PurpleSoft else Color(0xFF94A3B8), fontWeight = FontWeight.Bold); Spacer(Modifier.width(10.dp)); Text(label, color = Color.White, fontSize = 14.sp) } } } }
@Composable internal fun SingleChoice(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) { Surface(onClick = onClick, enabled = enabled, color = if (selected) AppColors.Purple.copy(.22f) else AppColors.Surface, border = BorderStroke(1.dp, if (selected) AppColors.PurpleSoft else AppColors.Divider), shape = RoundedCornerShape(13.dp)) { Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, onClick = null, enabled = enabled); Spacer(Modifier.width(8.dp)); Text(label, color = Color.White) } } }
@Composable private fun darkTextFieldColors() = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = AppColors.PurpleSoft, unfocusedBorderColor = AppColors.Divider, focusedLabelColor = AppColors.PurpleSoft, unfocusedLabelColor = Color(0xFF94A3B8), focusedSupportingTextColor = Color(0xFF94A3B8), unfocusedSupportingTextColor = Color(0xFF94A3B8))
