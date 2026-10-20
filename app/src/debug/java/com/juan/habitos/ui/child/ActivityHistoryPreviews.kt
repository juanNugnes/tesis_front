package com.juan.habitos.ui.child

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.juan.habitos.ui.theme.AppTheme

@Preview(name = "Sin información", widthDp = 280, showBackground = true)
@Composable private fun ActivityHistoryMissingPreview() = AppTheme { ActivityHistory(null) }

@Preview(name = "Cero · pantalla pequeña", widthDp = 280, showBackground = true)
@Composable private fun ActivityHistoryZeroPreview() = AppTheme { ActivityHistory(0) }

@Preview(name = "Una actividad", widthDp = 328, showBackground = true)
@Composable private fun ActivityHistoryOnePreview() = AppTheme { ActivityHistory(1) }

@Preview(name = "Varias · fuente grande", widthDp = 280, fontScale = 2f, showBackground = true)
@Composable private fun ActivityHistoryManyPreview() = AppTheme { ActivityHistory(12) }
