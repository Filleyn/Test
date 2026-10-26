package com.trailguidegps.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object TG {
    val Ink = Color(0xFF1F2421)
    val Muted = Color(0xFF4C544F)
    val Line = Color(0xFFD4D7D2)
    val Chip = Color(0xFFF1F2EF)
    val Accent = Color(0xFFB4491A)
    val Blue = Color(0xFF1A73E8)
    val Water = Color(0xFF2E7DBF)
    val Ok = Color(0xFF2E8B57)
    val Danger = Color(0xFFC62828)
    val Night = Color(0xFF121514)
    val NightCard = Color(0xFF1E2321)
    val NightLine = Color(0xFF343A37)
    val NightMuted = Color(0xFFB9C0BC)
}

@Composable
fun TrailGuideTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = TG.Accent,
            onPrimary = Color.White,
            secondary = TG.Blue,
            surface = Color.White,
            onSurface = TG.Ink,
            background = Color(0xFFEFEEE2),
        ),
        content = content,
    )
}
