package com.solarlamp.aquariumlight.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.solarlamp.aquariumlight.ui.theme.Ocean
import kotlin.math.sin

/**
 * A stylised glass-blue fish that gently bobs, with bubbles rising past it.
 * Drawn in code so there are no image files to manage.
 */
@Composable
fun FishIllustration(modifier: Modifier = Modifier, lightOn: Boolean = true) {
    val t by rememberInfiniteTransition(label = "fish").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val phase = t * 2f * Math.PI.toFloat()

        // Halo behind the fish, warmer when the tank light is on.
        drawCircle(
            Brush.radialGradient(
                listOf((if (lightOn) Ocean.Amber else Ocean.Blue).copy(alpha = 0.28f), Color.Transparent),
                center = Offset(w * 0.55f, h * 0.5f),
                radius = w * 0.55f,
            ),
            radius = w * 0.55f,
            center = Offset(w * 0.55f, h * 0.5f),
        )

        drawBubbles(t, w, h)

        val bob = sin(phase) * h * 0.03f
        val tilt = sin(phase) * 3f

        rotate(tilt, pivot = Offset(w * 0.5f, h * 0.5f + bob)) {
            drawFish(Offset(0f, bob), w, h)
        }
    }
}

private fun DrawScope.drawFish(shift: Offset, w: Float, h: Float) {
    fun p(x: Float, y: Float) = Offset(w * x + shift.x, h * y + shift.y)

    // Tail
    val tail = Path().apply {
        moveTo(p(0.70f, 0.50f).x, p(0.70f, 0.50f).y)
        cubicTo(p(0.82f, 0.38f).x, p(0.82f, 0.38f).y, p(0.92f, 0.26f).x, p(0.92f, 0.26f).y, p(0.97f, 0.24f).x, p(0.97f, 0.24f).y)
        cubicTo(p(0.92f, 0.42f).x, p(0.92f, 0.42f).y, p(0.92f, 0.58f).x, p(0.92f, 0.58f).y, p(0.97f, 0.76f).x, p(0.97f, 0.76f).y)
        cubicTo(p(0.90f, 0.74f).x, p(0.90f, 0.74f).y, p(0.80f, 0.62f).x, p(0.80f, 0.62f).y, p(0.70f, 0.50f).x, p(0.70f, 0.50f).y)
        close()
    }
    drawPath(tail, Brush.linearGradient(listOf(Ocean.Sky.copy(alpha = 0.85f), Ocean.Blue.copy(alpha = 0.35f)), p(0.7f, 0.5f), p(1f, 0.5f)))

    // Top fin
    val fin = Path().apply {
        moveTo(p(0.36f, 0.30f).x, p(0.36f, 0.30f).y)
        cubicTo(p(0.44f, 0.10f).x, p(0.44f, 0.10f).y, p(0.58f, 0.12f).x, p(0.58f, 0.12f).y, p(0.62f, 0.32f).x, p(0.62f, 0.32f).y)
        close()
    }
    drawPath(fin, Ocean.Sky.copy(alpha = 0.55f))

    // Body
    val body = Path().apply {
        moveTo(p(0.10f, 0.52f).x, p(0.10f, 0.52f).y)
        cubicTo(p(0.18f, 0.24f).x, p(0.18f, 0.24f).y, p(0.56f, 0.20f).x, p(0.56f, 0.20f).y, p(0.74f, 0.50f).x, p(0.74f, 0.50f).y)
        cubicTo(p(0.56f, 0.80f).x, p(0.56f, 0.80f).y, p(0.18f, 0.80f).x, p(0.18f, 0.80f).y, p(0.10f, 0.52f).x, p(0.10f, 0.52f).y)
        close()
    }
    drawPath(
        body,
        Brush.linearGradient(
            listOf(Color(0xFFBFE6FF), Ocean.Sky, Ocean.Blue, Color(0xFF1B3F8F)),
            start = p(0.2f, 0.25f),
            end = p(0.65f, 0.8f),
        ),
    )
    // Glassy highlight along the back
    val shine = Path().apply {
        moveTo(p(0.20f, 0.40f).x, p(0.20f, 0.40f).y)
        cubicTo(p(0.30f, 0.28f).x, p(0.30f, 0.28f).y, p(0.48f, 0.27f).x, p(0.48f, 0.27f).y, p(0.60f, 0.36f).x, p(0.60f, 0.36f).y)
    }
    drawPath(shine, Color.White.copy(alpha = 0.45f), style = Stroke(width = w * 0.012f))

    // Side fin
    val side = Path().apply {
        moveTo(p(0.36f, 0.58f).x, p(0.36f, 0.58f).y)
        cubicTo(p(0.40f, 0.70f).x, p(0.40f, 0.70f).y, p(0.48f, 0.74f).x, p(0.48f, 0.74f).y, p(0.52f, 0.70f).x, p(0.52f, 0.70f).y)
        close()
    }
    drawPath(side, Color.White.copy(alpha = 0.35f))

    // Scales hint
    for (i in 0..2) {
        drawArc(
            Color.White.copy(alpha = 0.18f),
            startAngle = -60f, sweepAngle = 120f, useCenter = false,
            topLeft = p(0.38f + i * 0.08f, 0.40f), size = Size(w * 0.08f, h * 0.2f),
            style = Stroke(width = w * 0.006f),
        )
    }

    // Eye
    drawCircle(Color.White, radius = w * 0.035f, center = p(0.22f, 0.47f))
    drawCircle(Ocean.Abyss, radius = w * 0.02f, center = p(0.215f, 0.47f))
    drawCircle(Color.White, radius = w * 0.007f, center = p(0.21f, 0.462f))
}

private fun DrawScope.drawBubbles(t: Float, w: Float, h: Float) {
    val bubbles = listOf(0.08f to 0.0f, 0.18f to 0.35f, 0.86f to 0.15f, 0.78f to 0.6f, 0.30f to 0.75f)
    for ((x, offset) in bubbles) {
        val progress = (t + offset) % 1f
        val y = h * (1.05f - progress * 1.1f)
        val r = w * (0.012f + offset * 0.02f)
        drawCircle(Ocean.Sky.copy(alpha = 0.5f * (1f - progress)), radius = r, center = Offset(w * x, y), style = Stroke(w * 0.004f))
    }
}

/** A light bulb that glows amber when on. */
@Composable
fun BulbIllustration(on: Boolean, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "bulb").animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "pulse",
    )

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h * 0.42f)
        val r = w * 0.26f

        if (on) {
            drawCircle(
                Brush.radialGradient(listOf(Ocean.Amber.copy(alpha = 0.55f * pulse), Color.Transparent), center, r * 2.6f),
                radius = r * 2.6f,
                center = center,
            )
        }

        // Glass globe
        drawCircle(
            Brush.radialGradient(
                if (on) listOf(Color(0xFFFFF4D6), Ocean.Amber, Color(0xFFE08A1E))
                else listOf(Color(0x557FB6FF), Color(0x2A7FB6FF), Color(0x147FB6FF)),
                center = center - Offset(r * 0.3f, r * 0.3f),
                radius = r * 1.4f,
            ),
            radius = r,
            center = center,
        )
        drawCircle(
            if (on) Color.White.copy(alpha = 0.5f) else Ocean.GlassEdge,
            radius = r,
            center = center,
            style = Stroke(w * 0.012f),
        )
        // Filament
        val filament = Path().apply {
            moveTo(center.x - r * 0.35f, center.y + r * 0.15f)
            cubicTo(center.x - r * 0.15f, center.y - r * 0.35f, center.x + r * 0.15f, center.y - r * 0.35f, center.x + r * 0.35f, center.y + r * 0.15f)
        }
        drawPath(filament, if (on) Color.White else Ocean.TextFaint, style = Stroke(w * 0.015f))

        // Base
        val baseTop = center.y + r * 0.92f
        for (i in 0..2) {
            drawRoundRect(
                if (on) Color(0xFFCFD8E3) else Color(0xFF5A6B80),
                topLeft = Offset(center.x - r * 0.45f, baseTop + i * r * 0.22f),
                size = Size(r * 0.9f, r * 0.16f),
                cornerRadius = CornerRadius(r * 0.08f),
            )
        }
    }
}
