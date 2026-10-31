package com.trailguidegps.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * How the app looks right now: dark (for night and the tent) and sunlight (high contrast and
 * bigger text, for reading in bright sun). The colors below follow these, so every screen
 * changes together.
 */
object Look {
    var dark by mutableStateOf(false)
    var sun by mutableStateOf(false)
}

object TG {
    val Ink get() = when { Look.dark -> Color(0xFFE6EBE7); Look.sun -> Color(0xFF000000); else -> Color(0xFF1F2421) }
    val Muted get() = when { Look.dark -> Color(0xFFB4BCB7); Look.sun -> Color(0xFF2B312D); else -> Color(0xFF4C544F) }
    val Line get() = when { Look.dark -> Color(0xFF3A413D); Look.sun -> Color(0xFF9AA19C); else -> Color(0xFFD4D7D2) }
    val Chip get() = if (Look.dark) Color(0xFF2A302D) else Color(0xFFF1F2EF)
    /** Page and panel background. */
    val Surface get() = if (Look.dark) Color(0xFF181C1A) else Color.White
    /** Cards sitting on a page. */
    val Card get() = if (Look.dark) Color(0xFF232926) else Color(0xFFF7F8F4)
    val CardGreen get() = if (Look.dark) Color(0xFF1F2E22) else Color(0xFFEFF5EC)
    val CardYellow get() = if (Look.dark) Color(0xFF3A3220) else Color(0xFFFFF4D6)
    val Accent get() = if (Look.dark) Color(0xFFE2763F) else Color(0xFFB4491A)
    val Blue get() = if (Look.dark) Color(0xFF64A5F5) else Color(0xFF1A73E8)
    val Water get() = if (Look.dark) Color(0xFF6FB0E6) else Color(0xFF2E7DBF)
    /** Water in text (lighter in the dark). */
    val WaterText get() = if (Look.dark) Color(0xFF8CC4F0) else Color(0xFF1F5FA8)
    /** Cautions in text: seasonal water, after dark, no water. */
    val Warn get() = if (Look.dark) Color(0xFFF0B060) else Color(0xFF8A4B00)
    val Ok = Color(0xFF2E8B57)
    val Danger get() = if (Look.dark) Color(0xFFEF6A6A) else Color(0xFFC62828)
    val Night = Color(0xFF121514)
    val NightCard = Color(0xFF1E2321)
    val NightLine = Color(0xFF343A37)
    val NightMuted = Color(0xFFB9C0BC)
}

@Composable
fun TrailGuideTheme(content: @Composable () -> Unit) {
    val scheme = if (Look.dark) darkColorScheme(
        primary = TG.Accent, onPrimary = Color.White, secondary = TG.Blue,
        surface = TG.Surface, onSurface = TG.Ink, background = TG.Surface, onBackground = TG.Ink,
        surfaceVariant = TG.Card, onSurfaceVariant = TG.Muted, outline = TG.Line, surfaceContainerHigh = TG.Card,
        surfaceContainerLow = TG.Surface, surfaceContainer = TG.Card,
    ) else lightColorScheme(
        primary = TG.Accent,
        onPrimary = Color.White,
        secondary = TG.Blue,
        surface = Color.White,
        onSurface = TG.Ink,
        background = Color(0xFFEFEEE2),
    )
    // Sunlight mode makes all text a little bigger.
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * if (Look.sun) 1.12f else 1f)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
