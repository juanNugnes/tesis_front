package com.juan.habitos.ui.child

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.juan.habitos.ui.state.ActivityState
import com.juan.habitos.ui.state.SuggestedActivityUiModel
import com.juan.habitos.ui.theme.AppTheme

// Layout fixture only: not Analytics content and never offered in the application.
private val instructionsPreviewActivity = SuggestedActivityUiModel(
    "preview-instructions", "✨", "Una idea para explorar",
    "Materiales:\n- Papel y lápices.\n\nPasos:\n" + (1..12).joinToString("\n\n") { "$it. Leé la consigna completa y explorá la idea a tu manera, con calma." },
)

@Preview(name = "Consignas largas · pantalla pequeña", widthDp = 320, heightDp = 640, fontScale = 1.3f)
@Composable private fun SuggestedInstructionsPreview() = InstructionsPreview(ActivityState.Suggested(instructionsPreviewActivity))

@Preview(name = "Consignas en curso · texto ampliado", widthDp = 320, heightDp = 640, fontScale = 2f)
@Composable private fun RunningInstructionsPreview() = InstructionsPreview(ActivityState.Running(instructionsPreviewActivity))

@Composable private fun InstructionsPreview(state: ActivityState) = AppTheme {
    LazyColumn(Modifier.fillMaxSize().background(Color(0xFF4F46E5)), contentPadding = PaddingValues(16.dp)) {
        item { ActivitySuggestionCard("🦊", state, false, {}, {}, {}, {}, {}) }
    }
}
