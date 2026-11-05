package com.solarlamp.aquariumlight.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.solarlamp.aquariumlight.ui.theme.Ocean

data class NavItem(val route: String, val label: String, val icon: ImageVector)

/** Floating glass pill with round icon buttons; the active one fills with blue. */
@Composable
fun FloatingNavBar(
    items: List<NavItem>,
    current: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .shadow(30.dp, RoundedCornerShape(50), ambientColor = Color.Black, spotColor = Color.Black)
            .glass(RoundedCornerShape(50))
            .background(Ocean.Abyss.copy(alpha = 0.55f))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in items) {
            val selected = item.route == current
            val tint by animateColorAsState(if (selected) Color.White else Ocean.TextMuted, label = "tint")

            Box(
                Modifier
                    .size(52.dp)
                    .then(
                        if (selected) Modifier
                            .shadow(14.dp, CircleShape, ambientColor = Ocean.Blue, spotColor = Ocean.Blue)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Ocean.Blue, Ocean.Sky)))
                        else Modifier.clip(CircleShape)
                    )
                    .clickable { onSelect(item.route) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(24.dp))
            }
        }
    }
}
