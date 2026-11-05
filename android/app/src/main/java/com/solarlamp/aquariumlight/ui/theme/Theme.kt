package com.solarlamp.aquariumlight.ui.theme

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// =========================================================
// Palette — black with blue glass
// =========================================================

object Ocean {
    val Abyss = Color(0xFF03060B)        // page background
    val Deep = Color(0xFF071425)
    val Navy = Color(0xFF0B2545)

    val Blue = Color(0xFF3D8BFF)         // primary accent
    val Sky = Color(0xFF6FC3FF)          // highlights, active text
    val Cyan = Color(0xFF5EE6E0)         // "online"
    val Amber = Color(0xFFFFC46B)        // light on
    val Coral = Color(0xFFFF6B6B)        // danger / offline

    val Text = Color(0xFFEAF3FF)
    val TextMuted = Color(0xB3C4D6EA)
    val TextFaint = Color(0x66C4D6EA)

    // Glass layers
    val GlassTop = Color(0x2E7FB6FF)
    val GlassBottom = Color(0x0F7FB6FF)
    val GlassStrong = Color(0x404E95FF)
    val GlassEdge = Color(0x5591C8FF)
    val GlassEdgeFaint = Color(0x1491C8FF)
}

private val colors = darkColorScheme(
    primary = Ocean.Blue,
    onPrimary = Color.White,
    secondary = Ocean.Sky,
    onSecondary = Ocean.Abyss,
    tertiary = Ocean.Cyan,
    background = Ocean.Abyss,
    onBackground = Ocean.Text,
    surface = Ocean.Deep,
    onSurface = Ocean.Text,
    surfaceVariant = Ocean.Navy,
    onSurfaceVariant = Ocean.TextMuted,
    surfaceContainerHigh = Color(0xFF0E2238),
    surfaceContainerHighest = Color(0xFF13304F),
    outline = Ocean.GlassEdge,
    error = Ocean.Coral,
)

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun AquariumTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

/** Deep-water backdrop: black, with soft blue light falling from the top. */
fun Modifier.oceanBackground(): Modifier = this
    .background(Ocean.Abyss)
    .drawBehind {
        drawRect(
            Brush.radialGradient(
                colors = listOf(Ocean.Navy.copy(alpha = 0.95f), Color.Transparent),
                center = Offset(size.width * 0.15f, 0f),
                radius = size.width * 1.2f,
            )
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(Ocean.Blue.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(size.width * 0.95f, size.height * 0.35f),
                radius = size.width * 0.9f,
            )
        )
    }
