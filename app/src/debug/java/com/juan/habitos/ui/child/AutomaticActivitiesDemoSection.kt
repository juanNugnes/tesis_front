package com.juan.habitos.ui.child

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.juan.habitos.BuildConfig
import com.juan.habitos.data.mock.*

@Composable internal fun AutomaticActivitiesDemoSection(profileId: String) {
    if (!automaticActivitiesDemoEnabled(BuildConfig.DEBUG, BuildConfig.AUTOMATIC_ACTIVITIES_DEMO_MODE) || profileId.isBlank()) return
    key(profileId) {
        val vm: AutomaticActivitiesDemoViewModel = viewModel(
            key = "automatic-activities-demo-$profileId",
            factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AutomaticActivitiesDemoViewModel(profileId) as T
            },
        )
        val state by vm.state.collectAsStateWithLifecycle()
        var expanded by rememberSaveable(profileId) { mutableStateOf(false) }
        var showPolicy by rememberSaveable(profileId) { mutableStateOf(false) }
        val offer = state.offer
        val busy = offer?.stage in listOf(AutomaticDemoStage.GENERATED, AutomaticDemoStage.PRESENTED, AutomaticDemoStage.SELECTED, AutomaticDemoStage.STARTED)
        ChildCard(containerColor = Color(0xFF163F53).copy(.94f), borderColor = Color(0xFF9CE8D2)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ofertas automáticas · demostración", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(AUTOMATIC_DEMO_LABEL, color = Color(0xFFFFE08A), fontWeight = FontWeight.Bold)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Ocultar controles" else "Demostración de ofertas automáticas", color = Color(0xFFB9F6E1))
                }
                if (expanded) {
                    Text("Cada botón representa otra oportunidad ficticia, no evalúa el teléfono. Restablecé para cambiar una oferta activa. Los rechazos se conservan hasta restablecer.", color = Color.White.copy(.82f))
                    DemoControl("Simular oferta diurna", !busy && !state.paused) { vm.simulate(AutomaticDemoScenario.DAYTIME) }
                    DemoControl("Simular oferta previa al descanso", !busy && !state.paused) { vm.simulate(AutomaticDemoScenario.BEDTIME) }
                    DemoControl("Simular oportunidad no habilitada", !busy && !state.paused) { vm.simulate(AutomaticDemoScenario.INELIGIBLE) }
                    DemoControl("Simular espera compartida", !busy && !state.paused) { vm.simulate(AutomaticDemoScenario.WAITING) }
                    DemoControl("Restablecer demostración", true, vm::reset)
                    TextButton(onClick = { showPolicy = !showPolicy }) { Text(if (showPolicy) "Ocultar reglas de referencia" else "Ver reglas de referencia", color = Color(0xFFB9F6E1)) }
                    if (showPolicy) Text(
                        "Referencia del coordinador de Analytics, no implementada aquí como motor: " +
                            "pausa diurna tras 45 minutos continuos, de 08:00 a antes de las 20:00; descanso desde 60 minutos antes hasta su horario; " +
                            "espera compartida de 120 minutos desde la primera entrega confirmada; presupuesto de 3 ofertas (máximo 2 diurnas y 1 de descanso), tanto por día local como en las últimas 24 horas; " +
                            "vigencia inicial de 10 minutos desde generación y respuesta hasta 10 minutos desde primera entrega; dos rechazos explícitos pausan 24 horas. " +
                            "Las solicitudes voluntarias no consumen ese presupuesto. Estos ejemplos no ejecutan temporizadores ni verifican elegibilidad o captura.",
                        color = Color.White.copy(.8f), fontSize = 13.sp,
                    )
                }
                if (state.paused) {
                    Text("Pausa ilustrativa de 24 horas por dos rechazos explícitos. No se cambia el reloj ni se programa una pausa real. Restablecé para repetir.", color = Color(0xFFFFE08A))
                }
                when (state.scenario) {
                    AutomaticDemoScenario.INELIGIBLE -> Text("Sin oferta automática: en este escenario ficticio la elegibilidad es desconocida. Corresponde abstenerse; no se genera ni se presenta una actividad.", color = Color.White)
                    AutomaticDemoScenario.WAITING -> Text("Sin nueva oferta: espera compartida ilustrativa de 120 minutos desde la primera entrega confirmada. No hay un temporizador real.", color = Color.White)
                    null -> Text("Activá un escenario desde los controles. No se generan ofertas automáticamente.", color = Color.White.copy(.8f))
                    else -> Unit
                }
                offer?.let { current ->
                    // Local presentation acknowledgement only; idempotent, never an HTTP delivery.
                    LaunchedEffect(current.id) { vm.presented(current.id) }
                    HorizontalDivider(color = Color.White.copy(.2f))
                    Text(current.reason, color = Color(0xFFB9F6E1), fontSize = 13.sp)
                    if (current.stage in listOf(AutomaticDemoStage.GENERATED, AutomaticDemoStage.PRESENTED, AutomaticDemoStage.SELECTED, AutomaticDemoStage.STARTED)) {
                        Text(current.message, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(current.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(current.description, color = Color.White.copy(.86f))
                    }
                    when (current.stage) {
                        AutomaticDemoStage.GENERATED -> Text("Oferta generada (simulada)", color = Color.White)
                        AutomaticDemoStage.PRESENTED -> {
                            Button(onClick = { vm.accept(current.id) }, modifier = Modifier.fillMaxWidth()) { Text("Aceptar actividad") }
                            DemoControl("No, gracias", true) { vm.reject(current.id) }
                        }
                        AutomaticDemoStage.SELECTED -> {
                            Text("Actividad seleccionada · todavía no declaraste el inicio.", color = Color.White)
                            Button(onClick = { vm.start(current.id) }, modifier = Modifier.fillMaxWidth()) { Text("Empezar actividad") }
                        }
                        AutomaticDemoStage.STARTED -> {
                            Text("Inicio declarado · recorrido de demostración", color = Color.White)
                            Button(onClick = { vm.finish(current.id) }, modifier = Modifier.fillMaxWidth()) { Text("Finalizar actividad") }
                        }
                        AutomaticDemoStage.COMPLETED -> Text("¡Muy bien! Cerraste el recorrido de demostración. Finalización declarada; no demuestra que se realizó la actividad.", color = Color(0xFFB9F6E1))
                        AutomaticDemoStage.REJECTED -> Text("No, gracias · rechazo guardado solo en esta demostración (${state.rejections}/2).", color = Color.White)
                    }
                    Text("Recorrido local: " + current.events.joinToString(" → ") { it.demoLabel() }, color = Color.White.copy(.8f), fontSize = 13.sp)
                }
                Text("Actividades ilustrativas: no provienen del catálogo ni de una evaluación real de Analytics. Aceptar, empezar o finalizar no prueba su realización. No se envían ni guardan como actividades reales.", color = Color.White.copy(.7f), fontSize = 13.sp)
            }
        }
    }
}

@Composable private fun DemoControl(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB9F6E1))) { Text(label) }
}

private fun AutomaticDemoEvent.demoLabel(): String = when (this) {
    AutomaticDemoEvent.GENERATED -> "generada"
    AutomaticDemoEvent.PRESENTED -> "presentada"
    AutomaticDemoEvent.ACCEPTED -> "aceptada"
    AutomaticDemoEvent.START_DECLARED -> "inicio declarado"
    AutomaticDemoEvent.COMPLETION_DECLARED -> "finalización declarada"
    AutomaticDemoEvent.REJECTED -> "rechazada"
}
