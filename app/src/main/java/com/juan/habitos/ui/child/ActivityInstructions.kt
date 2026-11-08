package com.juan.habitos.ui.child

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Unbounded height and wrapping: the existing home LazyColumn owns vertical scrolling. */
@Composable internal fun ActivityInstructions(activityId: String, activityEmoji: String, description: String) {
    val presentation = remember(description) { parseActivityInstructions(description) }
    if (!presentation.supportsStepNavigation) {
        // Conservative fallback: unknown formats are displayed byte-for-byte as before.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(activityEmoji, fontSize = 40.sp)
            InstructionText(description)
        }
        return
    }

    val steps = presentation.steps
    var currentStepIndex by rememberSaveable(activityId, description) { mutableIntStateOf(0) }
    val safeStepIndex = currentStepIndex.coerceIn(steps.indices)
    val navigation = ActivityStepNavigation(activityId, steps.size, safeStepIndex)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        presentation.blocks.forEach { block ->
            when (block) {
                is InstructionBlock.Prose -> if (block.source.isNotBlank()) InstructionText(block.source)
                is InstructionBlock.Context -> InstructionContextCard(block)
                is InstructionBlock.Heading,
                is InstructionBlock.Step -> Unit
            }
        }

        Surface(
            color = Color(0xFFF9FAFF),
            contentColor = Color(0xFF24205F),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFFDCD8FF)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        navigation.progressLabel,
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF5B21B6),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    StepProgressDots(steps.size, safeStepIndex)
                }

                Surface(
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(76.dp),
                    color = Color(0xFFFFF1BE),
                    shape = CircleShape,
                    border = BorderStroke(2.dp, Color(0xFFFFD166)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(activityEmoji, fontSize = 39.sp)
                    }
                }

                AnimatedContent(
                    targetState = safeStepIndex,
                    transitionSpec = {
                        (slideInHorizontally { it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 4 } + fadeOut())
                    },
                    label = "activity-step",
                ) { index ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Surface(
                            modifier = Modifier.sizeIn(minWidth = 42.dp, minHeight = 42.dp),
                            color = Color(0xFFB9F6D1),
                            contentColor = Color(0xFF24543A),
                            shape = CircleShape,
                        ) {
                            Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                                Text(steps[index].number, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(13.dp))
                        Text(
                            steps[index].text,
                            modifier = Modifier.weight(1f),
                            color = Color(0xFF24205F),
                            fontSize = 17.sp,
                            lineHeight = 25.sp,
                            softWrap = true,
                        )
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (navigation.canGoBack) {
                        OutlinedButton(
                            onClick = { currentStepIndex = navigation.previous().currentStepIndex },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFF6D5BD0)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4F3DB7)),
                        ) {
                            Text("Paso anterior", textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (navigation.canGoForward) {
                        Button(
                            onClick = { currentStepIndex = navigation.next().currentStepIndex },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFD166),
                                contentColor = Color(0xFF4D3800),
                            ),
                        ) {
                            Text("Siguiente paso", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun InstructionContextCard(context: InstructionBlock.Context) {
    val isMaterials = context.heading.equals("Materiales", ignoreCase = true)
    Surface(
        color = if (isMaterials) Color(0xFFDFF7E8) else Color.White.copy(.09f),
        contentColor = if (isMaterials) Color(0xFF214E35) else Color.White,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (isMaterials) Color(0xFF9DDDB8) else Color.White.copy(.15f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(if (isMaterials) "🎒" else "💡", fontSize = 25.sp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    if (isMaterials) "¿Qué necesitás?" else context.heading.uppercase(Locale.forLanguageTag("es")),
                    color = if (isMaterials) Color(0xFF17603A) else Color(0xFFB9F6D1),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    context.text,
                    color = if (isMaterials) Color(0xFF214E35) else Color.White.copy(.88f),
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    softWrap = true,
                )
            }
        }
    }
}

@Composable private fun StepProgressDots(stepCount: Int, selectedIndex: Int) {
    Row(
        Modifier.widthIn(max = 112.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(stepCount) { index ->
            Box(
                Modifier
                    .size(if (index == selectedIndex) 11.dp else 8.dp)
                    .background(
                        if (index <= selectedIndex) Color(0xFF6D5BD0) else Color(0xFFD7D2F5),
                        CircleShape,
                    )
            )
        }
    }
}

@Composable private fun InstructionText(text: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    Text(
        text,
        modifier,
        color = Color.White.copy(.88f),
        fontSize = 16.sp,
        lineHeight = 24.sp,
        softWrap = true,
        textAlign = TextAlign.Start,
    )
}
