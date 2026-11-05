package com.solarlamp.aquariumlight.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SetMeal
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.solarlamp.aquariumlight.data.Cloud
import com.solarlamp.aquariumlight.data.HistoryEntry
import com.solarlamp.aquariumlight.data.HistoryType
import com.solarlamp.aquariumlight.ui.components.GlassCard
import com.solarlamp.aquariumlight.ui.components.IconBadge
import com.solarlamp.aquariumlight.ui.components.MutedText
import com.solarlamp.aquariumlight.ui.components.SectionHeader
import com.solarlamp.aquariumlight.ui.components.VSpace
import com.solarlamp.aquariumlight.ui.theme.Ocean

@Composable
fun HistoryScreen(onBack: () -> Unit, isAdmin: Boolean) {
    val entries by remember { Cloud.historyFlow() }.collectAsState(initial = null)
    var filter by remember { mutableIntStateOf(0) }
    var personFilter by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<HistoryEntry?>(null) }

    val types = listOf<HistoryType?>(null) + HistoryType.entries

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding),
    ) {
        ScreenTitle("History", onBack = onBack)
        VSpace(8.dp)
        FilterChips(listOf("All") + HistoryType.entries.map { it.label }, filter) { filter = it }

        val list = entries
        if (list == null) {
            EmptyNote("Loading…")
            return@Column
        }

        val people = list.mapNotNull { it.byName?.takeIf { n -> n.isNotBlank() } }.distinct()
        if (people.size > 1) {
            VSpace(8.dp)
            val options = listOf("Everyone") + people
            FilterChips(options, personFilter?.let { options.indexOf(it) } ?: 0) {
                personFilter = if (it == 0) null else options[it]
            }
        }

        val shown = list.filter { e ->
            (types[filter] == null || e.type == types[filter]) &&
                (personFilter == null || e.byName == personFilter)
        }

        if (shown.isEmpty()) {
            EmptyNote("Nothing here yet. Light changes, feedings, maintenance and tank changes from every phone show up here.")
        }

        // Group by day
        shown.groupBy { it.at.localDate() }.forEach { (day, dayEntries) ->
            VSpace(16.dp)
            SectionHeader(dayLabel(day))
            GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 6.dp)) {
                for (e in dayEntries) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isAdmin) { deleting = e }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        IconBadge(iconFor(e.type), if (e.type == HistoryType.DEVICE) Ocean.TextMuted else Ocean.Sky)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.text, style = MaterialTheme.typography.bodyLarge, color = Ocean.Text)
                            MutedText(
                                listOfNotNull(e.byName?.takeIf { it.isNotBlank() }, e.at.clock()).joinToString(" · "),
                            )
                        }
                    }
                }
            }
        }
    }

    deleting?.let { e ->
        ConfirmDialog(
            title = "Delete this entry?",
            text = e.text,
            confirmText = "Delete",
            danger = true,
            onDismiss = { deleting = null },
        ) {
            Cloud.deleteHistory(e)
            deleting = null
        }
    }
}

private fun iconFor(type: HistoryType): ImageVector = when (type) {
    HistoryType.LIGHT -> Icons.Rounded.Lightbulb
    HistoryType.FEEDING -> Icons.Rounded.SetMeal
    HistoryType.CARE -> Icons.Rounded.CleaningServices
    HistoryType.WATER -> Icons.Rounded.Science
    HistoryType.TANK -> Icons.Rounded.Spa
    HistoryType.MEMBERS -> Icons.Rounded.Group
    HistoryType.DEVICE -> Icons.Rounded.Router
}
