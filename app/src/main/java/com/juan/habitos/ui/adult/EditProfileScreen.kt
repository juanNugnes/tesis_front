package com.juan.habitos.ui.adult

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juan.habitos.ui.components.ProfileAgeSelector
import com.juan.habitos.ui.components.ProfileAvatarSelector
import com.juan.habitos.ui.state.EditProfileUiState
import com.juan.habitos.ui.theme.AppColors

@Composable
fun EditProfileScreen(
    state: EditProfileUiState,
    onName: (String) -> Unit,
    onAge: (Int) -> Unit,
    onAvatar: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.errorMessage) { state.errorMessage?.let { snackbar.showSnackbar(it) } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = AppColors.Surface) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).background(AppColors.Surface).statusBarsPadding(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onBack, enabled = !state.submitting) { Text("←", color = Color.White, fontSize = 24.sp) }
                    Column {
                        Text("Editar perfil", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("Actualizá los datos básicos del perfil infantil.", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                    }
                }
            }
            if (!state.loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.loaded) {
                item {
                    EditProfileSection("NOMBRE") {
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = onName,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Nombre") },
                            singleLine = true,
                            isError = state.name.isBlank(),
                            supportingText = if (state.name.isBlank()) ({ Text("Ingresá un nombre.") }) else null,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                            colors = editProfileTextFieldColors(),
                        )
                    }
                }
                item { EditProfileSection("EDAD") { ProfileAgeSelector(state.age, onAge) } }
                item { EditProfileSection("AVATAR") { ProfileAvatarSelector(state.avatar, onAvatar) } }
                item {
                    Button(
                        onClick = onSave,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        enabled = state.valid && !state.submitting,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(if (state.submitting) "Guardando…" else "Guardar cambios", fontWeight = FontWeight.Bold) }
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

@Composable
private fun EditProfileSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(1.dp, AppColors.Divider),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun editProfileTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = AppColors.PurpleSoft,
    unfocusedBorderColor = AppColors.Divider,
    focusedSupportingTextColor = Color(0xFF94A3B8),
    unfocusedSupportingTextColor = Color(0xFF94A3B8),
)
