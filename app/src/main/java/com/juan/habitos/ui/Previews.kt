package com.juan.habitos.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.juan.habitos.domain.model.*
import com.juan.habitos.ui.adult.AdultDashboardScreen
import com.juan.habitos.ui.child.ChildHomeScreen
import com.juan.habitos.ui.onboarding.ProfileScreen
import com.juan.habitos.ui.state.*
import com.juan.habitos.ui.theme.AppTheme

@Preview(showBackground = true) @Composable private fun ProfilePreview() = AppTheme { ProfileScreen(ProfileUiState("Sofía", "🦊", 9, setOf(SchoolSchedule.MORNING), BedtimeRange.FROM_21_TO_22), {}, {}, {}, {}, {}, {}) }
@Preview(showBackground = true) @Composable private fun ChildPreview() = AppTheme { ChildHomeScreen(ChildHomeUiState(profile = Profile("Sofía","🦊",9), usage = DailyUsage(165,180,8), apps = listOf(AppUsage("YouTube Kids","",80,"","▶️"))), {}, {}, {}, {}) }
@Preview(showBackground = true) @Composable private fun AdultPreview() = AppTheme { AdultDashboardScreen(AdultDashboardUiState(Profile("Sofía","🦊",9), usage = DailyUsage(165,180,8)), {}, {}) }
