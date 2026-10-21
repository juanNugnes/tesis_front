package com.juan.habitos.ui.child

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.juan.habitos.BuildConfig
import com.juan.habitos.R
import com.juan.habitos.data.mock.*

/** Activity scope retains the per-profile initial-entry guard across rotation and navigation. */
@Composable internal fun AutomaticActivitiesDemoSection(profileId: String, controls: Boolean = false) {
    if (!automaticActivitiesDemoEnabled(BuildConfig.DEBUG, BuildConfig.AUTOMATIC_ACTIVITIES_DEMO_MODE) || profileId.isBlank()) return
    key(profileId) {
        val context = LocalContext.current
        val activity = remember(context) { context.demoActivity() }
        val vm: AutomaticActivitiesDemoViewModel = viewModel(
            viewModelStoreOwner = activity,
            key = "automatic-activities-demo-$profileId",
            factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AutomaticActivitiesDemoViewModel(profileId) as T
            },
        )
        val state by vm.state.collectAsStateWithLifecycle()
        if (controls) AutomaticDemoControls(state, vm)
        else {
            LaunchedEffect(vm) { vm.enterExperience() }
            state.offer?.let { if (state.dialogVisible) AutomaticOfferDialog(it, vm) }
        }
    }
}

private tailrec fun Context.demoActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.demoActivity()
    else -> error("Automatic activity demo requires an activity")
}

@Composable private fun AutomaticOfferDialog(offer: AutomaticDemoOffer, vm: AutomaticActivitiesDemoViewModel) {
    LaunchedEffect(offer.id) { vm.presented(offer.id) }
    Dialog(onDismissRequest = { vm.dismissDialog(offer.id) }) {
        Surface(shape = RoundedCornerShape(30.dp), color = Color(0xFFF8F5FF)) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .85f)
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Surface(color = Color(0xFFE2F7EF), shape = RoundedCornerShape(24.dp)) {
                        Image(painterResource(R.drawable.ic_activity_history_plant), contentDescription = null, modifier = Modifier.padding(8.dp).size(72.dp))
                    }
                    IconButton(onClick = { vm.dismissDialog(offer.id) }, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar oferta", tint = Color(0xFF65458F))
                    }
                }
                Text("¡Tengo una idea para vos!", color = Color(0xFF56348A), fontSize = 23.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(
                    if (offer.scenario == AutomaticDemoScenario.DAYTIME) "¿Hacemos una pausa?" else "Se acerca el momento de descansar. ¿Te gustaría hacer algo tranquilo?",
                    color = Color(0xFF60526F), fontSize = 16.sp, textAlign = TextAlign.Center,
                )
                Surface(color = Color(0xFFE2F7EF), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(offer.title, color = Color(0xFF24543A), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(offer.description, color = Color(0xFF437357), fontSize = 15.sp)
                    }
                }
                when (offer.stage) {
                    AutomaticDemoStage.GENERATED, AutomaticDemoStage.PRESENTED -> {
                        Button(onClick = { vm.accept(offer.id) }, enabled = offer.stage == AutomaticDemoStage.PRESENTED,
                            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C45A4))) { Text("¡Quiero hacerlo!") }
                        TextButton(onClick = { vm.reject(offer.id) }, enabled = offer.stage == AutomaticDemoStage.PRESENTED) { Text("No, gracias", color = Color(0xFF65458F)) }
                    }
                    AutomaticDemoStage.SELECTED -> {
                        Text("¡Elegiste esta idea! ¿Empezamos?", color = Color(0xFF60526F))
                        Button(onClick = { vm.start(offer.id) }, modifier = Modifier.fillMaxWidth()) { Text("Empezar actividad") }
                    }
                    AutomaticDemoStage.STARTED -> {
                        Text("Tomate tu tiempo y disfrutá la actividad.", color = Color(0xFF60526F))
                        Button(onClick = { vm.finish(offer.id) }, modifier = Modifier.fillMaxWidth()) { Text("Finalizar actividad") }
                    }
                    else -> Unit
                }
                Text(AUTOMATIC_DEMO_LABEL, color = Color(0xFF70657E), fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable private fun AutomaticDemoControls(state: AutomaticActivitiesDemoState, vm: AutomaticActivitiesDemoViewModel) {
    var expanded by rememberSaveable(state.profileId) { mutableStateOf(false) }
    var showPolicy by rememberSaveable(state.profileId) { mutableStateOf(false) }
    val busy = state.offer?.stage in listOf(AutomaticDemoStage.GENERATED, AutomaticDemoStage.PRESENTED, AutomaticDemoStage.SELECTED, AutomaticDemoStage.STARTED)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(if (expanded) "Opciones de demostración ↑" else "Opciones de demostración ↓", color = Color.White.copy(.85f))
        }
        if (expanded) ChildCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(AUTOMATIC_DEMO_LABEL, color = Color(0xFFFFE08A), fontSize = 12.sp)
                if (busy) DemoControl("Volver a la actividad simulada", true, vm::reopenDialog)
                DemoControl("Simular oferta diurna", !busy && !state.paused) { vm.simulate(AutomaticDemoScenario.DAYTIME) }
                DemoControl("Simular oferta previa al descanso", !busy && !state.paused) { vm.simulate(AutomaticDemoScenario.BEDTIME) }
                DemoControl("Simular oportunidad no habilitada", !busy && !state.paused) { vm.simulate(AutomaticDemoScenario.INELIGIBLE) }
                DemoControl("Simular espera compartida", !busy && !state.paused) { vm.simulate(AutomaticDemoScenario.WAITING) }
                DemoControl("Restablecer demostración", true, vm::reset)
                if (state.paused) Text("Pausa ilustrativa de 24 horas por dos rechazos explícitos. Restablecé para repetir.", color = Color(0xFFFFE08A))
                when (state.scenario) {
                    AutomaticDemoScenario.INELIGIBLE -> Text("Sin oferta: elegibilidad desconocida en este escenario ficticio. Corresponde abstenerse.", color = Color.White)
                    AutomaticDemoScenario.WAITING -> Text("Sin nueva oferta: espera compartida ilustrativa de 120 minutos desde primera entrega confirmada. No hay temporizador real.", color = Color.White)
                    else -> Unit
                }
                state.offer?.let { offer ->
                    Text(offer.reason, color = Color.White.copy(.8f), fontSize = 13.sp)
                    Text("Recorrido local: " + offer.events.joinToString(" → ") { it.demoLabel() }, color = Color.White.copy(.8f), fontSize = 13.sp)
                }
                TextButton(onClick = { showPolicy = !showPolicy }) { Text(if (showPolicy) "Ocultar reglas de referencia" else "Ver reglas de referencia", color = Color(0xFFB9F6E1)) }
                if (showPolicy) Text(
                    "Referencia del coordinador de Analytics, no implementada aquí como motor: " +
                        "pausa diurna tras 45 minutos continuos, de 08:00 a antes de las 20:00; descanso desde 60 minutos antes hasta su horario; " +
                        "espera compartida de 120 minutos desde la primera entrega confirmada; presupuesto de 3 ofertas (máximo 2 diurnas y 1 de descanso), tanto por día local como en las últimas 24 horas; " +
                        "vigencia inicial de 10 minutos desde generación y respuesta hasta 10 minutos desde primera entrega; dos rechazos explícitos pausan 24 horas. " +
                        "Las solicitudes voluntarias no consumen ese presupuesto. Cada escenario es otra oportunidad ficticia; los rechazos se conservan hasta restablecer. " +
                        "Aceptar, empezar o finalizar no prueba su realización. No se consulta Analytics ni se envían o guardan actividades reales. Cerrar el diálogo no es rechazar. " +
                        "Estos ejemplos no ejecutan temporizadores ni verifican elegibilidad o captura; las actividades no provienen del catálogo real.",
                    color = Color.White.copy(.8f), fontSize = 13.sp,
                )
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
