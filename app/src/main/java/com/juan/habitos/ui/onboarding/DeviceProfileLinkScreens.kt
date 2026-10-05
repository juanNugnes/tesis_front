package com.juan.habitos.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juan.habitos.ui.state.DeviceProfileLinkUiState
import com.juan.habitos.ui.theme.AppColors

@Composable
fun ChildProfileSetupScreen(onCreateProfile: () -> Unit, onLinkProfile: () -> Unit) {
    DeviceSetupFrame {
        Text("Configurá este dispositivo", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("Podés crear un perfil nuevo o vincular uno que ya existe.", color = Color(0xFFCBD5E1), lineHeight = 22.sp)
        Spacer(Modifier.height(30.dp))
        DeviceSetupButton("Crear un perfil nuevo", onClick = onCreateProfile)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onLinkProfile, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp)) {
            Text("Vincular un perfil existente", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DeviceProfileLinkScreen(
    state: DeviceProfileLinkUiState,
    onCode: (String) -> Unit,
    onLink: () -> Unit,
    onBack: () -> Unit,
) {
    DeviceSetupFrame {
        Text("Vincular perfil", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("Ingresá el código de 6 dígitos generado para este perfil.", color = Color(0xFFCBD5E1), lineHeight = 22.sp)
        Spacer(Modifier.height(26.dp))
        OutlinedTextField(
            value = state.code,
            onValueChange = onCode,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Código temporal") },
            placeholder = { Text("_ _ _ _ _ _") },
            singleLine = true,
            isError = state.errorMessage != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        state.errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(0xFFFCA5A5), fontSize = 13.sp)
        }
        Spacer(Modifier.height(22.dp))
        DeviceSetupButton(
            text = if (state.submitting) "Vinculando…" else "Vincular perfil",
            enabled = state.valid && !state.submitting,
            onClick = onLink,
        )
        OutlinedButton(onBack, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Volver", color = Color.White) }
    }
}

@Composable
private fun DeviceSetupFrame(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(AppColors.Surface).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun DeviceSetupButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
        shape = RoundedCornerShape(14.dp),
    ) { Text(text, fontWeight = FontWeight.Bold) }
}
