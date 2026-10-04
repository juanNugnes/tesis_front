package com.juan.habitos.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juan.habitos.ui.theme.AppColors

val profileAvatars = listOf("🦊", "🐸", "🐶", "🐱", "🐧", "🦁", "🐨", "🦋")

@Composable
fun ProfileAvatarSelector(selected: String?, onSelect: (String) -> Unit) {
    profileAvatars.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
            row.forEach { avatar ->
                Surface(
                    onClick = { onSelect(avatar) },
                    modifier = Modifier.weight(1f).aspectRatio(1.25f),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFF1F5F9),
                    border = if (selected == avatar) BorderStroke(3.dp, AppColors.Purple) else null,
                ) {
                    Box(contentAlignment = Alignment.Center) { Text(avatar, fontSize = 30.sp) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
fun ProfileAgeSelector(selected: Int?, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
        (6..12).forEach { age ->
            Surface(
                onClick = { onSelect(age) },
                modifier = Modifier.weight(1f).height(52.dp),
                color = if (selected == age) AppColors.Purple else Color(0xFFF1F5F9),
                shape = RoundedCornerShape(12.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("$age", color = if (selected == age) Color.White else Color.DarkGray, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
