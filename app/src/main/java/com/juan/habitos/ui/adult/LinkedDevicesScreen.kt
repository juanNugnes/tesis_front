package com.juan.habitos.ui.adult

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juan.habitos.ui.state.LinkedDeviceUiItem
import com.juan.habitos.ui.state.LinkedDevicesUiState
import com.juan.habitos.ui.theme.AppColors

@Composable
fun LinkedDevicesScreen(
    state: LinkedDevicesUiState,
    onRefresh: () -> Unit,
    onRequestDeactivate: (String) -> Unit,
    onConfirmDeactivate: () -> Unit,
    onCancelDeactivate: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.successMessage) { state.successMessage?.let { snackbar.showSnackbar(it) } }
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
                        Text("Dispositivos vinculados", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("Administrá los teléfonos que envían información de uso.", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                    }
                }
            }
            if (state.demoMode) {
                item { InformationCard("Vista de demostración", "Los dispositivos simulados son de solo lectura y no modifican el backend.") }
            }
            when {
                state.loading -> item {
                    Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(color = AppColors.PurpleSoft)
                    }
                }
                state.devices.isEmpty() -> item {
                    InformationCard("Sin dispositivos vinculados", "Cuando se vincule un dispositivo infantil aparecerá en este listado.")
                }
                else -> items(state.devices, key = LinkedDeviceUiItem::deviceId) { device ->
                    LinkedDeviceCard(device, state.deactivatingDeviceId == device.deviceId) {
                        onRequestDeactivate(device.deviceId)
                    }
                }
            }
            state.errorMessage?.let { message ->
                item { Text(message, color = Color(0xFFFCA5A5), fontSize = 14.sp) }
            }
            item {
                OutlinedButton(onRefresh, Modifier.fillMaxWidth().height(50.dp), enabled = !state.loading && !state.refreshing && state.deactivatingDeviceId == null) {
                    if (state.refreshing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.refreshing) "Actualizando…" else "Actualizar listado")
                }
            }
            item {
                Text(
                    "Desvincular un dispositivo no elimina el historial registrado ni desinstala la aplicación del teléfono infantil.",
                    color = Color(0xFF94A3B8), fontSize = 13.sp, lineHeight = 19.sp,
                )
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    if (state.confirmationDeviceId != null) {
        AlertDialog(
            onDismissRequest = onCancelDeactivate,
            title = { Text("¿Desvincular dispositivo?") },
            text = { Text("Este dispositivo dejará de enviar información de uso a Moderá. Los registros anteriores se conservarán y podrás seguir consultándolos desde tu cuenta.") },
            confirmButton = {
                TextButton(onConfirmDeactivate, enabled = state.deactivatingDeviceId == null) {
                    if (state.deactivatingDeviceId != null) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.deactivatingDeviceId != null) "Desvinculando…" else "Desvincular")
                }
            },
            dismissButton = { TextButton(onCancelDeactivate, enabled = state.deactivatingDeviceId == null) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun LinkedDeviceCard(device: LinkedDeviceUiItem, deactivating: Boolean, onDeactivate: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(1.dp, AppColors.Divider),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📱", fontSize = 28.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(if (device.active) "Activo" else "Inactivo", color = if (device.active) Color(0xFF86EFAC) else Color(0xFF94A3B8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("Última actividad: ${device.lastActivity ?: "Sin información"}", color = Color(0xFFCBD5E1), fontSize = 13.sp)
            if (device.active) {
                OutlinedButton(onDeactivate, Modifier.fillMaxWidth().height(48.dp), enabled = device.canDeactivate && !deactivating) {
                    Text(if (deactivating) "Desvinculando…" else "Desvincular")
                }
            }
        }
    }
}

@Composable
private fun InformationCard(title: String, message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.Card), border = BorderStroke(1.dp, AppColors.Divider), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(message, color = Color(0xFFCBD5E1), fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}
