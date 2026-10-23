package com.juan.habitos.ui.child

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Unbounded height and wrapping: the existing home LazyColumn owns vertical scrolling. */
@Composable internal fun ActivityInstructions(description: String) {
    val presentation = remember(description) { parseActivityInstructions(description) }
    if (!presentation.structured) {
        InstructionText(description)
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        presentation.blocks.forEachIndexed { index, block ->
            when (block) {
                is InstructionBlock.Prose -> InstructionText(block.source)
                is InstructionBlock.Heading -> InstructionHeading(block.text)
                is InstructionBlock.Context -> Surface(
                    color = Color.White.copy(.08f), shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color.White.copy(.12f)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InstructionHeading(block.heading)
                        InstructionText(block.displayText)
                    }
                }
                is InstructionBlock.Step -> Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Surface(color = Color(0xFFB9F6D1), contentColor = Color(0xFF24543A), shape = RoundedCornerShape(10.dp)) {
                            Box(Modifier.defaultMinSize(minWidth = 36.dp, minHeight = 36.dp).padding(8.dp), contentAlignment = Alignment.Center) {
                                Text(block.number, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        InstructionText(block.text, Modifier.weight(1f))
                    }
                    if (presentation.blocks.getOrNull(index + 1) is InstructionBlock.Step) {
                        HorizontalDivider(Modifier.padding(top = 12.dp), color = Color.White.copy(.16f))
                    }
                }
            }
        }
    }
}

@Composable private fun InstructionHeading(text: String) {
    Text(text.uppercase(Locale.forLanguageTag("es")), color = Color(0xFFB9F6D1), fontSize = 14.sp, fontWeight = FontWeight.Bold)
}

@Composable private fun InstructionText(text: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    Text(text, modifier, color = Color.White.copy(.88f), fontSize = 16.sp, lineHeight = 24.sp, softWrap = true, textAlign = TextAlign.Start)
}
