package com.juan.habitos.ui.adult

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juan.habitos.domain.model.*
import com.juan.habitos.ui.state.ParentalScreenTimeUiState
import com.juan.habitos.ui.theme.AppColors

internal const val PARENTAL_WEEKDAY_QUESTION = "Pensando en los últimos 30 días, ¿cuánto tiempo total pasó tu hijo/a frente a pantallas en un día habitual de lunes a viernes?"
internal const val PARENTAL_WEEKEND_QUESTION = "Pensando en los últimos 30 días, ¿cuánto tiempo total pasó tu hijo/a frente a pantallas en un día habitual de fin de semana?"
internal const val PARENTAL_DEVICES_HINT = "Considerá todos los dispositivos: celular, tablet, computadora, consola y televisión"
internal const val PARENTAL_CHOICE_HINT = "Elegí la opción que mejor represente el tiempo habitual"
internal const val PARENTAL_EXPERIMENTAL_NOTICE = "Estas respuestas nos ayudan a conocer los hábitos digitales del niño/a. Son opcionales y no reemplazan las estadísticas registradas por el dispositivo"
internal const val PARENTAL_DEMO_NOTICE = "Demostración · Estas respuestas todavía no se almacenan ni modifican las recomendaciones."
internal const val PARENTAL_PERSISTED_NOTICE = "Estas respuestas opcionales se guardan de forma independiente para la estimación técnica interna. No constituyen un diagnóstico ni modifican automáticamente las recomendaciones."

@Composable fun ParentalScreenTimeSection(
    age: Int?,
    selections: ParentalScreenTimeSelections,
    onSex: (ParentalSexChoice?) -> Unit,
    onWeekday: (ParentalTimeChoice?) -> Unit,
    onWeekend: (ParentalTimeChoice?) -> Unit,
    persistedState: ParentalScreenTimeUiState? = null,
) {
    val enabled = persistedState?.editable ?: true
    FormSection("Hábitos de uso de pantallas") {
        age?.let { Text("Edad: $it años · Se modifica desde Datos del perfil", color = Color(0xFFCBD5E1), fontSize = 13.sp) }
        Text(if (persistedState == null) PARENTAL_DEMO_NOTICE else PARENTAL_PERSISTED_NOTICE, color = Color(0xFFCBD5E1), fontSize = 13.sp)
        if (persistedState?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth())
        persistedState?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        persistedState?.message?.let { Text(it, color = AppColors.PurpleSoft, fontSize = 13.sp) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sexo del niño/a", color = Color.White)
            ParentalSexChoice.entries.forEach { choice -> SingleChoice(choice.label, choice == selections.sex, enabled) { onSex(choice) } }
            TextButton(onClick = { onSex(null) }, enabled = enabled) { Text("Omitir esta pregunta", color = AppColors.PurpleSoft) }
        }
        ParentalTimeQuestion(PARENTAL_WEEKDAY_QUESTION, selections.weekday, enabled, onWeekday)
        ParentalTimeQuestion(PARENTAL_WEEKEND_QUESTION, selections.weekend, enabled, onWeekend)
    }
}

@Composable private fun ParentalTimeQuestion(title: String, selected: ParentalTimeChoice?, enabled: Boolean, onSelect: (ParentalTimeChoice?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp)
        Text(PARENTAL_DEVICES_HINT, color = Color(0xFFCBD5E1), fontSize = 13.sp)
        Text(PARENTAL_CHOICE_HINT, color = Color(0xFF94A3B8), fontSize = 13.sp)
        ParentalTimeChoice.entries.forEach { choice -> SingleChoice(choice.label, choice == selected, enabled) { onSelect(choice) } }
        TextButton(onClick = { onSelect(null) }, enabled = enabled) { Text("Omitir esta pregunta", color = AppColors.PurpleSoft) }
    }
}
