package com.juan.habitos.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import com.juan.habitos.HabitosApplication
import com.juan.habitos.data.device.*
import com.juan.habitos.permission.AndroidPermissionManager
import com.juan.habitos.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UsageDiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { AppTheme { UsageDiagnosticsRoute() } } }
}

@Composable private fun UsageDiagnosticsRoute() {
    val context = LocalContext.current
    val application = context.applicationContext as HabitosApplication
    val repository = remember { UsageDiagnosticsRepository(AndroidDeviceUsageDataSource(context.applicationContext)) }
    val vm: UsageDiagnosticsViewModel = viewModel(factory = object : ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = UsageDiagnosticsViewModel(repository, { application.profileRepository.profile.first()?.id }, { application.deviceRepository.identity.first().deviceId }) as T })
    val state by vm.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) { val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refresh() }; lifecycleOwner.lifecycle.addObserver(observer); onDispose { lifecycleOwner.lifecycle.removeObserver(observer) } }
    val copyText: (String, String, String, String) -> Unit = { label, text, successMessage, tooLargeMessage ->
        val copied = HistoricalJsonFileExporter.copyToClipboardIfSafe(text) { safeText ->
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, safeText))
        }
        if (copied) {
            Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, tooLargeMessage, Toast.LENGTH_LONG).show()
        }
    }
    val shareHistoricalJson: (String) -> Unit = { json ->
        coroutineScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    HistoricalJsonFileExporter.prepare(
                        cacheDirectory = context.cacheDir,
                        json = json,
                        date = LocalDate.now(ZoneId.systemDefault()),
                    )
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    context.packageName + HistoricalJsonFileExporter.FILE_PROVIDER_AUTHORITY_SUFFIX,
                    prepared.file,
                )
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = prepared.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(context.contentResolver, prepared.file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(sendIntent, "Compartir JSON histórico"))
            } catch (exception: Exception) {
                Log.e("UsageDiagnostics", "Unable to share historical JSON file", exception)
                Toast.makeText(context, "No pudimos preparar el archivo para compartir.", Toast.LENGTH_LONG).show()
            }
        }
    }
    UsageDiagnosticsScreen(
        state = state,
        onRange = vm::selectRange,
        onRefresh = vm::refresh,
        onOpenSettings = { AndroidPermissionManager(context.applicationContext).request(0) },
        onCopyDiagnostic = { vm.exportText()?.let { copyText("Usage diagnostics", it, "Diagnóstico copiado", "El diagnóstico es demasiado grande para copiar.") } },
        onGenerateHistorical = { vm.generateHistoricalJson(false) },
        onGenerateOverlapping = { vm.generateHistoricalJson(true) },
        onShareHistorical = { vm.historicalJson()?.let(shareHistoricalJson) },
        onCopyHistorical = { vm.historicalJson()?.let { copyText("Historical usage JSON", it, "JSON histórico copiado", "El diagnóstico es demasiado grande para copiar. Usá Compartir JSON.") } },
        onCalculateObservation = vm::calculateInteractiveScreenObservation,
        onCopyObservation = { vm.observationJson()?.let { copyText("Interactive screen observation", it, "Observación compacta copiada", "La observación es demasiado grande para copiar.") } },
    )
}

@Composable private fun UsageDiagnosticsScreen(
    state: UsageDiagnosticsUiState,
    onRange: (DiagnosticRange) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onCopyDiagnostic: () -> Unit,
    onGenerateHistorical: () -> Unit,
    onGenerateOverlapping: () -> Unit,
    onShareHistorical: () -> Unit,
    onCopyHistorical: () -> Unit,
    onCalculateObservation: () -> Unit,
    onCopyObservation: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().background(AppColors.Background).statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Diagnóstico de uso", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Herramienta experimental · solo build debug", color = Color(0xFF94A3B8)) }
        item { AccessCard(state.hasAccess, onOpenSettings) }
        item { InteractiveScreenObservationCard(state, onCalculateObservation, onCopyObservation) }
        item { HistoricalExportCard(state, onGenerateHistorical, onGenerateOverlapping, onShareHistorical, onCopyHistorical) }
        if (state.hasAccess) {
            item { RangeSelector(state.range, onRange) }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { message -> item { Text("Error: $message", color = MaterialTheme.colorScheme.error) } }
            state.snapshot?.let { snapshot ->
                item { IntervalCard(snapshot) }
                item { DiagnosticSummaryCard(state.range, snapshot) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onRefresh, Modifier.weight(1f)) { Text("Actualizar") }; OutlinedButton(onCopyDiagnostic, Modifier.weight(1f)) { Text("Copiar diagnóstico") } } }
                item { SectionTitle("DEVICE SESSIONS") }
                if (snapshot.deviceSessions.isEmpty()) item { EmptyText("No se encontraron pares completos SCREEN_INTERACTIVE → SCREEN_NON_INTERACTIVE.") }
                snapshot.deviceSessions.forEachIndexed { index, session -> item { SessionRow(index + 1, session) } }
                if (snapshot.incompleteDeviceSessions.isNotEmpty()) item { Text("SESIONES INCOMPLETAS (excluidas de los cálculos)", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold) }
                snapshot.incompleteDeviceSessions.forEach { session -> item { IncompleteSessionRow(session) } }
                item { SectionTitle("UNLOCK EVENTS") }
                if (snapshot.unlockTimestamps.isEmpty()) item { EmptyText("No disponible: no se encontraron eventos KEYGUARD_HIDDEN.") }
                snapshot.unlockTimestamps.forEach { timestamp -> item { DiagnosticCard { Text(formatTime(timestamp), color = Color.White, fontWeight = FontWeight.Bold) } } }
                item { SectionTitle("APP USAGE RECONSTRUCTED") }
                item { Text("Intervalos derivados únicamente de ACTIVITY_RESUMED y ACTIVITY_PAUSED/STOPPED.", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall) }
                if (snapshot.reconstructedApplications.isEmpty()) item { EmptyText("No hay pares de actividad suficientes para reconstruir uso visible.") }
                snapshot.reconstructedApplications.forEach { app -> item { ReconstructedAppCard(app) } }
                item { SectionTitle("USAGE STATS AGREGADOS") }
                item { Text("Referencia: totalTimeInForeground puede incluir información fuera del intervalo exacto solicitado.", color = Color(0xFFFBBF24), style = MaterialTheme.typography.bodySmall) }
                if (snapshot.applications.isEmpty()) item { EmptyText("Android no devolvió aplicaciones con foreground mayor a cero.") }
                snapshot.applications.forEach { app -> item { AppUsageRow(app) } }
                item { SectionTitle("USAGE EVENTS") }
                item { Text("Los tipos disponibles dependen del fabricante y de la versión de Android.", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall) }
                if (snapshot.events.isEmpty()) item { EmptyText("Android no devolvió eventos para este intervalo.") }
                snapshot.events.forEach { event -> item { EventRow(event) } }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable private fun InteractiveScreenObservationCard(
    state: UsageDiagnosticsUiState,
    onCalculate: () -> Unit,
    onCopy: () -> Unit,
) {
    DiagnosticCard {
        Text("MINUTOS DE PANTALLA INTERACTIVA", color = AppColors.PurpleSoft, fontWeight = FontWeight.Bold)
        Text("Observación técnica de la pantalla del dispositivo; no identifica quién usó físicamente el teléfono.", color = Color(0xFFCBD5E1), style = MaterialTheme.typography.bodySmall)
        Button(onCalculate, Modifier.fillMaxWidth(), enabled = !state.observationCalculating) {
            Text(if (state.observationCalculating) "Calculando…" else "Calcular observación diaria")
        }
        state.observationError?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
        state.interactiveScreenObservation?.let { observation ->
            HorizontalDivider(color = AppColors.Divider)
            SummaryLine("Fecha", observation.localDate)
            SummaryLine("Minutos", observation.interactiveScreenMinutes?.toString() ?: "No disponible")
            SummaryLine("Milisegundos exactos", observation.interactiveScreenMillis?.toString() ?: "No disponible")
            SummaryLine("Estado", observation.status.name)
            SummaryLine("Motivo faltante", observation.missingReason?.name ?: "—")
            SummaryLine("Versión", observation.calculationVersion)
            SummaryLine("Runs considerados", observation.qualityEvidence.collectorRuns.size.toString())
            SummaryLine("Runs exitosos", observation.qualityEvidence.collectorRuns.count { it.status == CollectorRunQualityStatus.SUCCESS }.toString())
            SummaryLine("Segmentos canónicos", observation.qualityEvidence.canonicalSegments.size.toString())
            SummaryLine("Huecos de cobertura", observation.qualityEvidence.coverageGaps.size.toString())
            SummaryLine(
                "Cobertura",
                "${UsageDiagnosticsRepository.formatDuration(observation.qualityEvidence.coveredMillis)} / ${UsageDiagnosticsRepository.formatDuration(observation.qualityEvidence.expectedCoverageMillis)}",
            )
            OutlinedButton(onCopy, Modifier.fillMaxWidth()) { Text("Copiar observación compacta") }
        }
    }
}

@Composable private fun HistoricalExportCard(
    state: UsageDiagnosticsUiState,
    onGenerate: () -> Unit,
    onGenerateOverlapping: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    DiagnosticCard {
        Text("EXPORT HISTÓRICO RAW", color = AppColors.PurpleSoft, fontWeight = FontWeight.Bold)
        Text("Genera un JSON local con eventos sin deduplicar. No calcula minutos ni envía información al backend.", color = Color(0xFFCBD5E1), style = MaterialTheme.typography.bodySmall)
        Button(onGenerate, Modifier.fillMaxWidth(), enabled = !state.historicalExporting) {
            Text(if (state.historicalExporting) "Generando…" else "Generar JSON del día")
        }
        OutlinedButton(onGenerateOverlapping, Modifier.fillMaxWidth(), enabled = !state.historicalExporting) {
            Text("Generar con consultas solapadas")
        }
        state.historicalError?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
        if (state.historicalJson != null) {
            Text(
                if (state.historicalIncludesOverlaps) "JSON listo · incluye dos consultas solapadas" else "JSON listo · consulta diaria y estado previo",
                color = Color(0xFF34D399),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onShare, Modifier.fillMaxWidth()) { Text("Compartir JSON histórico") }
            OutlinedButton(onCopy, Modifier.fillMaxWidth()) { Text("Copiar JSON histórico (si es pequeño)") }
        }
    }
}

@Composable private fun AccessCard(granted: Boolean, onOpenSettings: () -> Unit) { DiagnosticCard { Text("Acceso a estadísticas:", color = Color.White, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text(if (granted) "✓ Concedido" else "✕ No concedido", color = if (granted) Color(0xFF34D399) else Color(0xFFF87171), fontWeight = FontWeight.Bold); if (!granted) { Spacer(Modifier.height(12.dp)); Button(onOpenSettings) { Text("Abrir configuración") } } } }
@Composable private fun RangeSelector(selected: DiagnosticRange, onSelect: (DiagnosticRange) -> Unit) { Column { Text("Rango de análisis", color = Color.White, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { DiagnosticRange.entries.forEach { range -> FilterChip(selected == range, { onSelect(range) }, { Text(range.label) }) } } } }
@Composable private fun IntervalCard(snapshot: UsageDiagnosticSnapshot) { DiagnosticCard { Text("Desde: ${formatDate(snapshot.fromMillis)}", color = Color.White); Text("Hasta: ${formatDate(snapshot.toMillis)}", color = Color.White); Text("Duración del intervalo: ${UsageDiagnosticsRepository.formatDuration(snapshot.toMillis - snapshot.fromMillis)}", color = Color(0xFFCBD5E1)) } }

@Composable private fun DiagnosticSummaryCard(range: DiagnosticRange, snapshot: UsageDiagnosticSnapshot) { val summary = snapshot.summary; DiagnosticCard { SectionTitle("DIAGNÓSTICO"); Text("Intervalo: ${range.label}", color = Color.White); Text("MÉTRICAS EXPERIMENTALES", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold); SummaryLine("Foreground reconstructed from events", summary.eventForegroundMillis?.let(UsageDiagnosticsRepository::formatDuration) ?: "No disponible"); SummaryLine("UsageStats foreground (referencia)", UsageDiagnosticsRepository.formatDuration(summary.totalForegroundMillis)); SummaryLine("Apps utilizadas", summary.appCount.toString()); SummaryLine("Desbloqueos detectados", summary.unlockCount?.toString() ?: "No disponible"); SummaryLine("Sesiones estimadas", summary.sessionCount?.toString() ?: "No disponible"); SummaryLine("Duración promedio", summary.averageSessionMillis?.let(UsageDiagnosticsRepository::formatDuration) ?: "No disponible"); SummaryLine("Sesión máxima", summary.maxSessionMillis?.let(UsageDiagnosticsRepository::formatDuration) ?: "No disponible"); SummaryLine("Eventos encontrados", summary.eventCount.toString()); Spacer(Modifier.height(8.dp)); Text("La reconstrucción suma intervalos de actividad visible. UsageStats queda como referencia y puede abarcar datos fuera del intervalo exacto. Las sesiones usan pares SCREEN_INTERACTIVE/SCREEN_NON_INTERACTIVE.", color = Color(0xFFCBD5E1), style = MaterialTheme.typography.bodySmall) } }
@Composable private fun SummaryLine(label: String, value: String) { Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f), color = Color(0xFFCBD5E1)); Text(value, color = Color.White, fontWeight = FontWeight.Bold) } }
@Composable private fun AppUsageRow(app: AppUsageDiagnostic) { DiagnosticCard { Text(app.packageName, color = Color.White, fontWeight = FontWeight.Bold); Text("Foreground: ${UsageDiagnosticsRepository.formatDuration(app.foregroundMillis)}", color = Color(0xFFCBD5E1)); Text("Último uso: ${formatTime(app.lastTimeUsed)}", color = Color(0xFFCBD5E1)) } }
@Composable private fun SessionRow(number: Int, session: DetectedSession) { DiagnosticCard { Text("$number. ${formatTime(session.startMillis)} → ${formatTime(session.endMillis)}", color = Color.White, fontWeight = FontWeight.Bold); Text(UsageDiagnosticsRepository.formatDuration(session.durationMillis), color = Color(0xFFCBD5E1)) } }
@Composable private fun IncompleteSessionRow(session: IncompleteDeviceSession) { DiagnosticCard { Text("${session.startMillis?.let(::formatTime) ?: "?"} → ${session.endMillis?.let(::formatTime) ?: "?"}", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold); Text("Incompleta; no incluida en promedio, máxima ni cantidad.", color = Color(0xFFCBD5E1), style = MaterialTheme.typography.bodySmall) } }
@Composable private fun ReconstructedAppCard(app: ReconstructedAppUsage) { DiagnosticCard { Text(app.packageName, color = Color.White, fontWeight = FontWeight.Bold); app.intervals.forEach { interval -> Text("${formatTime(interval.startMillis)} → ${formatTime(interval.endMillis)}   ${UsageDiagnosticsRepository.formatDuration(interval.durationMillis)}", color = Color(0xFFCBD5E1)) }; HorizontalDivider(color = AppColors.Divider); Text("Total: ${UsageDiagnosticsRepository.formatDuration(app.totalMillis)}", color = Color.White, fontWeight = FontWeight.Bold) } }
@Composable private fun EventRow(event: RawUsageEvent) { DiagnosticCard { Text("${formatTime(event.timestamp)} | ${event.packageName ?: "Android/System"}", color = Color.White, fontWeight = FontWeight.Medium); Text(event.eventName, color = eventColor(event.eventName), style = MaterialTheme.typography.bodySmall); Text("Tipo numérico: ${event.eventType}", color = Color(0xFF64748B), style = MaterialTheme.typography.labelSmall) } }
@Composable private fun DiagnosticCard(content: @Composable ColumnScope.() -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = AppColors.Card), shape = RoundedCornerShape(16.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content) } }
@Composable private fun SectionTitle(text: String) = Text(text, color = AppColors.PurpleSoft, fontWeight = FontWeight.Bold)
@Composable private fun EmptyText(text: String) = Text(text, color = Color(0xFF94A3B8), modifier = Modifier.padding(vertical = 8.dp))
private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
private fun formatDate(timestamp: Long) = dateFormatter.format(Instant.ofEpochMilli(timestamp))
private fun formatTime(timestamp: Long) = timeFormatter.format(Instant.ofEpochMilli(timestamp))
private fun eventColor(name: String) = when (name) { "SCREEN_INTERACTIVE", "KEYGUARD_HIDDEN", "ACTIVITY_RESUMED" -> Color(0xFF34D399); "SCREEN_NON_INTERACTIVE", "KEYGUARD_SHOWN", "ACTIVITY_PAUSED", "ACTIVITY_STOPPED" -> Color(0xFFFBBF24); else -> Color(0xFFA78BFA) }
