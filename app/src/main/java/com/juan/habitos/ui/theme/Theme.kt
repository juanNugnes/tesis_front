package com.juan.habitos.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object AppColors { val Background = Color(0xFF0B0A1A); val Surface = Color(0xFF0F172A); val Card = Color(0xFF1E293B); val Divider = Color(0xFF334155); val Purple = Color(0xFF7C3AED); val PurpleUi = Color(0xFF6D28D9); val PurpleSoft = Color(0xFFA78BFA); val Success = Color(0xFF10B981) }
object Spacing { val xs = 4.dp; val sm = 8.dp; val md = 16.dp; val lg = 24.dp; val xl = 32.dp }
object Corners { val small = 12.dp; val medium = 20.dp; val large = 32.dp }
private val Colors = darkColorScheme(primary = AppColors.Purple, secondary = AppColors.PurpleSoft, background = AppColors.Background, surface = AppColors.Surface, surfaceVariant = AppColors.Card, outline = AppColors.Divider, onPrimary = Color.White, onBackground = Color.White, onSurface = Color.White)
@Composable fun AppTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = Colors, typography = Typography(), content = content) }
