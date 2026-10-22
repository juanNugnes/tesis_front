package com.juan.habitos.ui.adult

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.juan.habitos.ui.state.*
import com.juan.habitos.ui.theme.AppTheme

@Preview(name = "Preferencias y hábitos · perfil existente", widthDp = 320, heightDp = 640, fontScale = 1.3f)
@Composable private fun ParentalPreferencesPreview() = AppTheme {
    ChildPreferencesScreen(
        ChildPreferencesUiState(childName = "Niño/a", age = 9, loaded = true), {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, {},
        onSimulatedScreenHabits = {},
    )
}
