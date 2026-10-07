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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juan.habitos.domain.model.RepresentativeVerificationStatus
import com.juan.habitos.ui.state.MonitoringConsentUiState
import com.juan.habitos.ui.state.PRIVACY_NOTICE_VERSION
import com.juan.habitos.ui.theme.AppColors

@Composable
fun MonitoringConsentScreen(
    state: MonitoringConsentUiState,
    onGrant: () -> Unit,
    onRequestRevoke: () -> Unit,
    onConfirmRevoke: () -> Unit,
    onCancelRevoke: () -> Unit,
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
                    Text("Privacidad y monitoreo", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Administrá el permiso para registrar el uso del dispositivo.", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                }
            }
        }
        if (state.loading) {
            item { Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = AppColors.PurpleSoft) } }
        } else {
            item {
                ConsentStatusCard(state, onGrant, onRequestRevoke)
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
            text = { Text("¿Querés revocar el consentimiento? El dispositivo dejará de enviar nueva información de uso para este perfil.") },
            confirmButton = {
                TextButton(onConfirmRevoke, enabled = !state.submitting) {
                    Text(if (state.submitting) "Desactivando…" else "Revocar")
                }
            },
            dismissButton = { TextButton(onCancelRevoke, enabled = !state.submitting) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ConsentStatusCard(
    state: MonitoringConsentUiState,
    onGrant: () -> Unit,
    onRequestRevoke: () -> Unit,
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
                    StatusText("Monitoreo desactivado", "El consentimiento fue revocado. No se enviará nueva información de uso para este perfil.")
                    Text("Aviso de privacidad: versión $PRIVACY_NOTICE_VERSION", color = Color(0xFF94A3B8), fontSize = 13.sp)
                    Button(
                        onClick = onGrant,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = state.canGrant,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                    ) { Text(if (state.submitting) "Habilitando…" else "Aceptar y habilitar monitoreo", fontWeight = FontWeight.Bold) }
                }
                state.verificationMissing -> {
                    StatusText("Autorización no disponible", "No encontramos un estado de autorización para este perfil. Contactá al equipo responsable para continuar.")
                }
                state.verificationStatus == RepresentativeVerificationStatus.PENDING -> {
                    StatusText("Autorización pendiente", "Tu autorización está pendiente de verificación. Cuando la verificación esté aprobada vas a poder habilitar el monitoreo.")
                }
                state.verificationStatus == RepresentativeVerificationStatus.REJECTED -> {
                    StatusText("Autorización no aprobada", "No pudimos validar la autorización para este perfil. El monitoreo permanece desactivado.")
                }
                state.verificationStatus == RepresentativeVerificationStatus.APPROVED -> {
                    StatusText("Monitoreo de uso", "Moderá utilizará métricas de uso del dispositivo para las funcionalidades informadas. La decisión es voluntaria y puede revocarse después.")
                    Text("Aviso de privacidad: versión $PRIVACY_NOTICE_VERSION", color = Color(0xFF94A3B8), fontSize = 13.sp)
                    Button(
                        onClick = onGrant,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = state.canGrant,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                    ) { Text(if (state.submitting) "Habilitando…" else "Aceptar y habilitar monitoreo", fontWeight = FontWeight.Bold) }
                }
                else -> StatusText("Monitoreo no disponible", "No pudimos determinar si el monitoreo puede habilitarse.")
            }
        }
    }
}

@Composable
private fun StatusText(title: String, body: String) {
    Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    Text(body, color = Color(0xFFCBD5E1), fontSize = 14.sp, lineHeight = 20.sp)
}
