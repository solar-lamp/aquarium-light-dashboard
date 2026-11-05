package com.solarlamp.aquariumlight.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.solarlamp.aquariumlight.ui.theme.Ocean

private val glassFill = Brush.linearGradient(listOf(Ocean.GlassTop, Ocean.GlassBottom))
private val glassEdge = Brush.linearGradient(listOf(Ocean.GlassEdge, Ocean.GlassEdgeFaint))

/** Frosted blue glass panel. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(28.dp), glow: Color? = null): Modifier {
    val glowed = if (glow != null) {
        this.shadow(24.dp, shape, ambientColor = glow, spotColor = glow)
    } else this
    return glowed
        .clip(shape)
        .background(glassFill)
        .border(1.dp, glassEdge, shape)
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    glow: Color? = null,
    padding: PaddingValues = PaddingValues(20.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .glass(shape, glow)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

enum class PillStyle { PRIMARY, GLASS, DANGER }

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.GLASS,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val shape = RoundedCornerShape(50)
    val (fill, textColor) = when {
        style == PillStyle.PRIMARY || selected ->
            Brush.horizontalGradient(listOf(Ocean.Blue, Ocean.Sky)) to Color.White
        style == PillStyle.DANGER ->
            Brush.horizontalGradient(listOf(Ocean.Coral.copy(alpha = 0.22f), Ocean.Coral.copy(alpha = 0.12f))) to Ocean.Coral
        else -> glassFill to Ocean.Text
    }

    Row(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .then(if (selected) Modifier.shadow(16.dp, shape, ambientColor = Ocean.Blue, spotColor = Ocean.Blue) else Modifier)
            .clip(shape)
            .background(fill)
            .border(1.dp, if (style == PillStyle.DANGER) SolidCoralEdge else glassEdge, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = textColor, style = MaterialTheme.typography.labelLarge)
    }
}

private val SolidCoralEdge = Brush.linearGradient(listOf(Ocean.Coral.copy(alpha = 0.5f), Ocean.Coral.copy(alpha = 0.2f)))

/** Round glass button holding an icon, like the bell in the header. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    Box(
        modifier
            .size(size)
            .glass(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Ocean.Text, modifier = Modifier.size(22.dp))
    }
}

/** Small rounded label with a coloured dot, e.g. "● Online". */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

/** Round tinted icon badge used at the top of small tiles. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = Ocean.Sky, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = Ocean.Text,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Ocean.TextMuted, modifier = modifier)
}

@Composable
fun BigValue(text: String, modifier: Modifier = Modifier, color: Color = Ocean.Text) {
    Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color, modifier = modifier)
}

@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))
