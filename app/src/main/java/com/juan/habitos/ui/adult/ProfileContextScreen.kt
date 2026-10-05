package com.juan.habitos.ui.adult

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.juan.habitos.domain.model.ProfileContext
import com.juan.habitos.domain.model.ProfileContextType
import com.juan.habitos.ui.state.ProfileContextFormState
import com.juan.habitos.ui.state.ProfileContextUiState
import com.juan.habitos.ui.theme.AppColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val contextDateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

internal fun profileContextTypeLabel(type: ProfileContextType): String = when (type) {
    ProfileContextType.VACATION -> "Vacaciones"
    ProfileContextType.ILLNESS -> "Enfermedad"
    ProfileContextType.SCHOOL_ACTIVITY -> "Actividad escolar"
    ProfileContextType.ROUTINE_CHANGE -> "Cambio de rutina"
    ProfileContextType.OTHER -> "Otro"
}

@Composable
fun ProfileContextScreen(
    state: ProfileContextUiState,
    onAdd: () -> Unit,
    onEdit: (ProfileContext) -> Unit,
    onDelete: (String) -> Unit,
    onType: (ProfileContextType) -> Unit,
    onStartDate: (LocalDate) -> Unit,
    onEndDate: (LocalDate?) -> Unit,
    onDescription: (String) -> Unit,
    onSave: () -> Unit,
    onDismissForm: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ProfileContext?>(null) }
    LaunchedEffect(state.saved) { if (state.saved) snackbar.showSnackbar("Contexto guardado") }
    LaunchedEffect(state.errorMessage) { state.errorMessage?.let { snackbar.showSnackbar(it) } }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = AppColors.Surface) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).statusBarsPadding(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onBack) { Text("←", color = Color.White, fontSize = 24.sp) }
                    Column {
                        Text("Contexto", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Podés registrar situaciones que ayuden a interpretar cambios en la rutina.",
                            color = Color(0xFFCBD5E1),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                    }
                }
            }
            if (state.refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                Button(
                    onClick = onAdd,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = state.loaded && !state.refreshing && !state.submitting && state.deletingId == null,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("+ Agregar contexto", fontWeight = FontWeight.Bold) }
            }
            item { Text("CONTEXTOS ACTUALES / RECIENTES", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            if (state.loaded && state.contexts.isEmpty()) {
                item {
                    Surface(color = AppColors.Card, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, AppColors.Divider)) {
                        Text("Todavía no hay contextos registrados.", Modifier.fillMaxWidth().padding(18.dp), color = Color(0xFFCBD5E1))
                    }
                }
            }
            items(state.contexts, key = { it.id ?: "${it.type}-${it.startDate}-${it.description}" }) { context ->
                ContextCard(
                    context = context,
                    active = context.id != null && context.id in state.activeContextIds,
                    deleting = state.deletingId == context.id,
                    enabled = !state.refreshing && !state.submitting && state.deletingId == null,
                    onEdit = { onEdit(context) },
                    onDelete = { pendingDelete = context },
                )
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    state.form?.let { form ->
        ProfileContextEditorDialog(
            form = form,
            submitting = state.submitting,
            onType = onType,
            onStartDate = onStartDate,
            onEndDate = onEndDate,
            onDescription = onDescription,
            onSave = onSave,
            onDismiss = onDismissForm,
        )
    }

    pendingDelete?.let { context ->
        AlertDialog(
            onDismissRequest = { if (state.deletingId == null) pendingDelete = null },
            title = { Text("¿Querés eliminar este contexto?") },
            text = { Text(profileContextTypeLabel(context.type)) },
            confirmButton = {
                TextButton(onClick = { context.id?.let(onDelete); pendingDelete = null }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ContextCard(
    context: ProfileContext,
    active: Boolean,
    deleting: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.Card), border = BorderStroke(1.dp, AppColors.Divider), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(profileContextTypeLabel(context.type), Modifier.weight(1f), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (active) AssistChip(onClick = {}, label = { Text("Activo") }, enabled = false)
            }
            Text(formatContextRange(context), color = AppColors.PurpleSoft, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            context.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color(0xFFCBD5E1), fontSize = 14.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit, enabled = enabled && context.id != null) { Text("Editar") }
                TextButton(onClick = onDelete, enabled = enabled && context.id != null) { Text(if (deleting) "Eliminando…" else "Eliminar", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

internal fun formatContextRange(context: ProfileContext): String {
    val start = context.startDate.format(contextDateFormatter)
    return context.endDate?.let { "$start - ${it.format(contextDateFormatter)}" } ?: "Desde $start"
}

private enum class ContextDateTarget { START, END }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileContextEditorDialog(
    form: ProfileContextFormState,
    submitting: Boolean,
    onType: (ProfileContextType) -> Unit,
    onStartDate: (LocalDate) -> Unit,
    onEndDate: (LocalDate?) -> Unit,
    onDescription: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var dateTarget by remember { mutableStateOf<ContextDateTarget?>(null) }
    Dialog(onDismissRequest = { if (!submitting) onDismiss() }) {
        Card(
            Modifier.fillMaxWidth().heightIn(max = 700.dp),
            colors = CardDefaults.cardColors(containerColor = AppColors.Card),
            shape = RoundedCornerShape(20.dp),
        ) {
            LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(if (form.editingId == null) "Agregar contexto" else "Editar contexto", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                item { Text("Tipo", color = Color(0xFFCBD5E1), fontWeight = FontWeight.Bold) }
                items(ProfileContextType.entries) { type ->
                    Surface(
                        onClick = { onType(type) },
                        color = if (type == form.type) AppColors.Purple.copy(.22f) else AppColors.Surface,
                        border = BorderStroke(1.dp, if (type == form.type) AppColors.PurpleSoft else AppColors.Divider),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = type == form.type, onClick = { onType(type) })
                            Text(profileContextTypeLabel(type), color = Color.White)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton({ dateTarget = ContextDateTarget.START }, Modifier.weight(1f)) {
                            Column { Text("Desde", fontSize = 11.sp); Text(form.startDate.format(contextDateFormatter)) }
                        }
                        OutlinedButton({ dateTarget = ContextDateTarget.END }, Modifier.weight(1f)) {
                            Column { Text("Hasta", fontSize = 11.sp); Text(form.endDate?.format(contextDateFormatter) ?: "Sin fecha") }
                        }
                    }
                }
                if (form.endDate != null) item { TextButton(onClick = { onEndDate(null) }) { Text("Quitar fecha hasta") } }
                if (!form.valid) item { Text("La fecha hasta no puede ser anterior a la fecha desde.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                item {
                    OutlinedTextField(
                        value = form.description,
                        onValueChange = onDescription,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Descripción (opcional)") },
                        placeholder = { Text("Agregá un detalle si querés") },
                        minLines = 2,
                        maxLines = 5,
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancelar") }
                        Button(onClick = onSave, enabled = form.valid && !submitting, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)) {
                            Text(if (submitting) "Guardando…" else "Guardar")
                        }
                    }
                }
            }
        }
    }

    dateTarget?.let { target ->
        key(target) {
            val initialDate = if (target == ContextDateTarget.START) form.startDate else form.endDate ?: form.startDate
            val picker = rememberDatePickerState(initialSelectedDateMillis = initialDate.toEpochDay() * 86_400_000)
            DatePickerDialog(
                onDismissRequest = { dateTarget = null },
                confirmButton = {
                    TextButton(onClick = {
                        picker.selectedDateMillis?.let { millis ->
                            val selected = LocalDate.ofEpochDay(millis / 86_400_000)
                            if (target == ContextDateTarget.START) onStartDate(selected) else onEndDate(selected)
                        }
                        dateTarget = null
                    }) { Text("Aceptar") }
                },
                dismissButton = { TextButton(onClick = { dateTarget = null }) { Text("Cancelar") } },
            ) { DatePicker(picker) }
        }
    }
}
