package com.solarlamp.aquariumlight.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.solarlamp.aquariumlight.ui.components.GlassCard
import com.solarlamp.aquariumlight.ui.components.IconBadge
import com.solarlamp.aquariumlight.ui.components.MutedText
import com.solarlamp.aquariumlight.ui.components.StatusChip
import com.solarlamp.aquariumlight.ui.components.VSpace
import com.solarlamp.aquariumlight.ui.theme.Ocean

/** Placeholder for a section that arrives in a later update. */
@Composable
fun ComingSoonScreen(
    title: String,
    icon: ImageVector,
    summary: String,
    features: List<String>,
    onBack: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding),
    ) {
        ScreenTitle(title, onBack = onBack)
        VSpace(16.dp)

        GlassCard(Modifier.fillMaxWidth(), glow = Ocean.Blue) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon)
                Spacer(Modifier.width(12.dp))
                StatusChip("Coming next", Ocean.Sky)
            }
            VSpace(16.dp)
            Text(summary, style = MaterialTheme.typography.titleLarge, color = Ocean.Text)
            VSpace(16.dp)
            for (feature in features) {
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Ocean.Sky, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    MutedText(feature)
                }
            }
        }

        VSpace(16.dp)
        MutedText(
            "This section is shared between everyone in the family and stays in sync across phones. " +
                "It's switched on once the shared database is set up.",
        )
    }
}
