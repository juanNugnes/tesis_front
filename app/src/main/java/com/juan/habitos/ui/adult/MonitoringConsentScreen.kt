package com.juan.habitos.ui.adult

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juan.habitos.domain.model.RepresentativeVerificationStatus
import com.juan.habitos.ui.state.MonitoringConsentUiState
import com.juan.habitos.ui.state.PRIVACY_NOTICE_VERSION
import com.juan.habitos.ui.state.SameDeviceMonitoringStage
import com.juan.habitos.ui.state.SameDeviceMonitoringUiState
import com.juan.habitos.ui.theme.AppColors

@Composable
fun SameDeviceMonitoringSetupScreen(
    state: SameDeviceMonitoringUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(AppColors.Surface).statusBarsPadding().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Text("←", color = Color.White, fontSize = 24.sp) }
            Column {
                Text("Autorización de monitoreo", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Acceso protegido para la persona adulta responsable.", color = Color(0xFFCBD5E1), fontSize = 13.sp)
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.Card),
            border = BorderStroke(1.dp, AppColors.Divider),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(color = AppColors.PurpleSoft)
                    Text(
                        if (state.stage == SameDeviceMonitoringStage.LINKING_PROFILE) "Vinculando el perfil infantil…" else "Comprobando la vinculación…",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("Este proceso no modifica la identidad ni las métricas guardadas en el dispositivo.", color = Color(0xFFCBD5E1), fontSize = 14.sp)
                } else if (!state.ready) {
                    Text(state.errorMessage.orEmpty(), color = Color(0xFFFCA5A5), fontSize = 14.sp)
                    if (!state.adultAuthenticationRequired) {
                        Button(onRetry, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)) {
                            Text("Reintentar")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MonitoringConsentScreen(
    state: MonitoringConsentUiState,
    onGrant: () -> Unit,
    onRequestRevoke: () -> Unit,
    onConfirmRevoke: () -> Unit,
    onCancelRevoke: () -> Unit,
    onAcceptanceChanged: (Boolean) -> Unit,
    onShowInformation: () -> Unit,
    onHideInformation: () -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().background(AppColors.Surface).statusBarsPadding(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Text("←", color = Color.White, fontSize = 24.sp) }
                Column {
                    Text("Autorización de monitoreo", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Administrá el permiso para registrar el uso del dispositivo.", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                }
            }
        }
        item { MonitoringNoticeCard(onShowInformation) }
        if (state.loading) {
            item { Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = AppColors.PurpleSoft) } }
        } else {
            item {
                ConsentStatusCard(state, onGrant, onRequestRevoke, onAcceptanceChanged)
            }
        }
        state.errorMessage?.let { message ->
            item { Text(message, color = Color(0xFFFCA5A5), fontSize = 14.sp) }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }

    if (state.showRevokeConfirmation) {
        AlertDialog(
            onDismissRequest = onCancelRevoke,
            title = { Text("Desactivar monitoreo") },
            text = { Text("¿Querés revocar este consentimiento? El backend no admitirá nuevas métricas si el perfil queda sin otra autorización vigente. Los registros anteriores no se eliminan automáticamente.") },
            confirmButton = {
                TextButton(onConfirmRevoke, enabled = !state.submitting) {
                    Text(if (state.submitting) "Desactivando…" else "Revocar")
                }
            },
            dismissButton = { TextButton(onCancelRevoke, enabled = !state.submitting) { Text("Cancelar") } },
        )
    }
    if (state.showDataTreatmentInformation) {
        AlertDialog(
            onDismissRequest = onHideInformation,
            title = { Text("Tratamiento de datos · versión $PRIVACY_NOTICE_VERSION") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Información actual del MVP. No reemplaza ni renueva un consentimiento que ya esté activo.")
                    monitoringNoticeDetails.forEach { section ->
                        Text(section.title, fontWeight = FontWeight.Bold)
                        Text(section.body)
                    }
                }
            },
            confirmButton = { TextButton(onHideInformation) { Text("Cerrar") } },
        )
    }
}

@Composable private fun MonitoringNoticeCard(onShowInformation: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(1.dp, AppColors.Divider), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Información sobre el monitoreo", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Aviso para nuevas autorizaciones: versión $PRIVACY_NOTICE_VERSION", color = Color(0xFF94A3B8), fontSize = 13.sp)
            monitoringNoticeSummary.forEach { section -> StatusText(section.title, section.body) }
            TextButton(onShowInformation, Modifier.fillMaxWidth()) { Text("Ver información sobre el tratamiento de datos", color = AppColors.PurpleSoft) }
        }
    }
}

@Composable
private fun ConsentStatusCard(
    state: MonitoringConsentUiState,
    onGrant: () -> Unit,
    onRequestRevoke: () -> Unit,
    onAcceptanceChanged: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(1.dp, AppColors.Divider),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.monitoringEnabled -> {
                    StatusText("Monitoreo habilitado", "El consentimiento está activo y el perfil puede recibir información de uso del dispositivo.")
                    Text("Aviso de privacidad aceptado: versión ${state.consent?.privacyNoticeVersion}", color = Color(0xFF94A3B8), fontSize = 13.sp)
                    OutlinedButton(onRequestRevoke, Modifier.fillMaxWidth().height(50.dp), enabled = state.canRevoke) {
                        Text("Revocar consentimiento")
                    }
                }
                state.monitoringRevoked -> {
                    StatusText("Monitoreo desactivado", "Este consentimiento fue revocado. Los registros anteriores se conservan; el backend requiere una autorización vigente para admitir nuevas métricas.")
                    Text("Último aviso aceptado: versión ${state.consent?.privacyNoticeVersion}", color = Color(0xFF94A3B8), fontSize = 13.sp)
                }
                state.verificationMissing -> {
                    StatusText("Verificación pendiente", "La verificación de la persona adulta todavía no está registrada. Para el MVP, contactá al equipo responsable para completar el procedimiento manual.")
                }
                state.verificationStatus == RepresentativeVerificationStatus.PENDING -> {
                    StatusText("Autorización pendiente", "Tu autorización está pendiente de verificación. Cuando la verificación esté aprobada vas a poder habilitar el monitoreo.")
                }
                state.verificationStatus == RepresentativeVerificationStatus.REJECTED -> {
                    StatusText("Autorización no aprobada", "No pudimos validar la autorización para este perfil. El monitoreo permanece desactivado.")
                }
                state.verificationStatus == RepresentativeVerificationStatus.APPROVED -> {
                    StatusText("Monitoreo de uso", "La decisión es voluntaria. Revisá la información antes de autorizar; podés revocar el consentimiento después.")
                }
                else -> StatusText("Monitoreo no disponible", "No pudimos determinar si el monitoreo puede habilitarse.")
            }
            if (!state.monitoringEnabled && state.verificationStatus == RepresentativeVerificationStatus.APPROVED && !state.verificationMissing) {
                Text("Nueva autorización: aviso versión $PRIVACY_NOTICE_VERSION", color = Color(0xFF94A3B8), fontSize = 13.sp)
                Row(
                    Modifier.fillMaxWidth().toggleable(value = state.explicitlyAccepted, enabled = state.canAcceptNotice,
                        role = Role.Checkbox, onValueChange = onAcceptanceChanged).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = state.explicitlyAccepted, onCheckedChange = null, enabled = state.canAcceptNotice)
                    Text(MONITORING_ACCEPTANCE_TEXT, color = Color(0xFFCBD5E1), fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
                Button(onGrant, Modifier.fillMaxWidth(), enabled = state.canGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(if (state.submitting) "Habilitando…" else "Aceptar y habilitar monitoreo", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StatusText(title: String, body: String) {
    Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    Text(body, color = Color(0xFFCBD5E1), fontSize = 14.sp, lineHeight = 20.sp)
}
