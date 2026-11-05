package com.solarlamp.aquariumlight.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SetMeal
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.solarlamp.aquariumlight.data.Cloud
import com.solarlamp.aquariumlight.data.Inhabitant
import com.solarlamp.aquariumlight.data.LifeType
import com.solarlamp.aquariumlight.ui.components.GlassCard
import com.solarlamp.aquariumlight.ui.components.IconBadge
import com.solarlamp.aquariumlight.ui.components.MutedText
import com.solarlamp.aquariumlight.ui.components.PillButton
import com.solarlamp.aquariumlight.ui.components.PillStyle
import com.solarlamp.aquariumlight.ui.components.VSpace
import com.solarlamp.aquariumlight.ui.theme.Ocean

@Composable
fun TankScreen() {
    val all by remember { Cloud.inhabitantsFlow() }.collectAsState(initial = emptyList())
    var tab by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Inhabitant?>(null) }

    val type = if (tab == 0) LifeType.FISH else LifeType.PLANT
    val fish = all.filter { it.type == LifeType.FISH }
    val plants = all.filter { it.type == LifeType.PLANT }
    val shown = if (type == LifeType.FISH) fish else plants

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding),
    ) {
        ScreenTitle("Fish & plants")
        VSpace(12.dp)

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CountTile(Icons.Rounded.SetMeal, "Fish", fish.sumOf { it.count }, "${fish.size} species", Modifier.weight(1f))
            CountTile(Icons.Rounded.Spa, "Plants", plants.sumOf { it.count }, "${plants.size} kinds", Modifier.weight(1f))
        }

        VSpace(20.dp)
        SegmentedTabs(listOf("Fish", "Plants"), tab, { tab = it }, Modifier.fillMaxWidth())
        VSpace(16.dp)

        if (shown.isEmpty()) {
            EmptyNote(if (type == LifeType.FISH) "No fish listed yet. Add each species with how many you have." else "No plants listed yet.")
        }

        for (item in shown) {
            GlassCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), onClick = { editing = item }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.titleLarge, color = Ocean.Text)
                        val details = listOfNotNull(
                            item.addedAt?.let { "Added ${dayLabel(it.localDate()).lowercase().let { d -> if (d == "today" || d == "yesterday") d else "on $d" }}" },
                            item.note.takeIf { it.isNotBlank() },
                        )
                        if (details.isNotEmpty()) MutedText(details.joinToString(" · "))
                    }
                    Stepper(item.count, onChange = { Cloud.changeCount(item, it) })
                }
            }
        }

        VSpace(8.dp)
        PillButton(
            if (type == LifeType.FISH) "Add fish" else "Add plant",
            { adding = true },
            style = PillStyle.PRIMARY,
            icon = Icons.Rounded.Add,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (adding) {
        InputDialog(
            title = if (type == LifeType.FISH) "Add fish" else "Add plant",
            fields = listOf(
                InputField(if (type == LifeType.FISH) "Species (e.g. Neon tetra)" else "Plant (e.g. Java fern)", capitalization = KeyboardCapitalization.Words),
                InputField("How many", "1", KeyboardType.Number),
                InputField("Note (optional)"),
            ),
            confirmText = "Add",
            onDismiss = { adding = false },
        ) { (name, count, note) ->
            if (name.isNotBlank()) Cloud.addInhabitant(type, name, count.trim().toIntOrNull()?.coerceIn(0, 9999) ?: 1, note)
            adding = false
        }
    }

    editing?.let { item ->
        var confirmDelete by remember(item.id) { mutableStateOf(false) }
        if (confirmDelete) {
            ConfirmDialog(
                title = "Remove ${item.name}?",
                text = "It will be taken off the list. To record a loss instead, lower the count.",
                confirmText = "Remove",
                danger = true,
                onDismiss = { confirmDelete = false },
            ) {
                Cloud.deleteInhabitant(item)
                editing = null
            }
        } else {
            InputDialog(
                title = "Edit ${item.type.label.lowercase()}",
                fields = listOf(
                    InputField("Name", item.name, capitalization = KeyboardCapitalization.Words),
                    InputField("Note", item.note),
                ),
                onDismiss = { editing = null },
                extraAction = {
                    TextButton(onClick = { confirmDelete = true }) { Text("Remove from list", color = Ocean.Coral) }
                },
            ) { (name, note) ->
                if (name.isNotBlank()) Cloud.updateInhabitant(item, name, note)
                editing = null
            }
        }
    }
}

@Composable
private fun CountTile(icon: ImageVector, title: String, count: Int, caption: String, modifier: Modifier) {
    GlassCard(modifier, padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        IconBadge(icon)
        VSpace(12.dp)
        Text(title, style = MaterialTheme.typography.labelMedium, color = Ocean.TextMuted)
        Text(count.toString(), style = MaterialTheme.typography.headlineMedium, color = Ocean.Text)
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = Ocean.TextFaint)
    }
}

@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(Icons.Rounded.Remove, "Fewer", enabled = value > 0) { onChange(value - 1) }
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = Ocean.Text,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 44.dp),
        )
        StepButton(Icons.Rounded.Add, "More", enabled = true) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Ocean.GlassStrong)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) Ocean.Text else Ocean.TextFaint, modifier = Modifier.size(20.dp))
    }
}
