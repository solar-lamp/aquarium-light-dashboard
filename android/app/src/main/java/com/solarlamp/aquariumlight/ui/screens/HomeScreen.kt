package com.solarlamp.aquariumlight.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SetMeal
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.solarlamp.aquariumlight.data.Access
import com.solarlamp.aquariumlight.data.Cloud
import com.solarlamp.aquariumlight.data.LifeType
import com.solarlamp.aquariumlight.data.formatNumber
import com.solarlamp.aquariumlight.data.DeviceRepository
import com.solarlamp.aquariumlight.data.Mode
import com.solarlamp.aquariumlight.data.nextChange
import com.solarlamp.aquariumlight.ui.components.FishIllustration
import com.solarlamp.aquariumlight.ui.components.GlassCard
import com.solarlamp.aquariumlight.ui.components.GlassIconButton
import com.solarlamp.aquariumlight.ui.components.IconBadge
import com.solarlamp.aquariumlight.ui.components.MutedText
import com.solarlamp.aquariumlight.ui.components.PillButton
import com.solarlamp.aquariumlight.ui.components.PillStyle
import com.solarlamp.aquariumlight.ui.components.SectionHeader
import com.solarlamp.aquariumlight.ui.components.VSpace
import com.solarlamp.aquariumlight.ui.theme.Ocean
import java.util.Calendar

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLight: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val access by Cloud.access.collectAsState()
    val name = (access as? Access.Approved)?.member?.displayName.orEmpty()
    val feedingTimes by remember { Cloud.feedingTimesFlow() }.collectAsState(initial = emptyList())
    val feedings by remember { Cloud.feedingsFlow(limit = 10) }.collectAsState(initial = emptyList())
    val tasks by remember { Cloud.tasksFlow() }.collectAsState(initial = emptyList())
    val tests by remember { Cloud.waterTestsFlow(limit = 1) }.collectAsState(initial = emptyList())
    val life by remember { Cloud.inhabitantsFlow() }.collectAsState(initial = emptyList())
    val state by DeviceRepository.state.collectAsState()
    val now by rememberNow()
    val status = state.status
    val online = state.deviceOnline(now)

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding),
    ) {
        // Header: avatar + greeting, bell for history
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, onOpenSettings)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (name.isEmpty()) "Hello 👋" else "Hello $name 👋",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ocean.TextMuted,
                )
                Text(greeting(), style = MaterialTheme.typography.titleMedium, color = Ocean.Text)
            }
            GlassIconButton(Icons.Rounded.History, "Activity history", onOpenHistory)
        }

        VSpace(24.dp)

        // Headline, styled like the reference: light words + bold words
        val (lead, bold) = when {
            status == null -> "Connecting to" to "your tank."
            !online -> "Your tank is" to "offline."
            status.lightOn -> "Lights are" to "on."
            else -> "Lights are" to "off."
        }
        Text(lead, style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light), color = Ocean.Text)
        Text(bold, style = MaterialTheme.typography.displaySmall, color = Ocean.Text)

        VSpace(20.dp)

        // Hero tank card
        GlassCard(
            Modifier.fillMaxWidth(),
            glow = if (status?.lightOn == true) Ocean.Amber else Ocean.Blue,
            onClick = onOpenLight,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Main tank", style = MaterialTheme.typography.labelMedium, color = Ocean.TextMuted)
                    Text(
                        when (status?.lightOn) {
                            true -> "Light on"
                            false -> "Light off"
                            null -> "—"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (status?.lightOn == true) Ocean.Amber else Ocean.Text,
                    )
                    VSpace(4.dp)
                    MutedText(status?.mode?.label?.let { "Mode: $it" } ?: "Mode: —")
                    VSpace(10.dp)
                    ConnectionChip(state, now)
                }
                FishIllustration(Modifier.size(150.dp), lightOn = status?.lightOn == true)
            }

            VSpace(16.dp)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val lightOn = status?.lightOn == true
                PillButton(
                    text = if (lightOn) "Turn off" else "Turn on",
                    onClick = { DeviceRepository.setMode(if (lightOn) Mode.FORCE_OFF else Mode.FORCE_ON) },
                    style = PillStyle.PRIMARY,
                    icon = Icons.Rounded.PowerSettingsNew,
                    enabled = online,
                    modifier = Modifier.weight(1f),
                )
                if (status?.mode != null && status.mode != Mode.AUTO) {
                    PillButton(
                        text = "Back to Auto",
                        onClick = { DeviceRepository.setMode(Mode.AUTO) },
                        icon = Icons.Rounded.AutoMode,
                        enabled = online,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        VSpace(14.dp)

        // Small tiles
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val next = status?.takeIf { it.mode == Mode.AUTO }?.nextChange()
            Tile(
                icon = Icons.Rounded.Schedule,
                title = "Next change",
                value = next?.let { "${if (it.turnsOn) "On" else "Off"} at ${it.at}" } ?: "—",
                caption = when {
                    next != null -> "in ${formatDuration(next.inMinutes)}"
                    status?.mode != null && status.mode != Mode.AUTO -> "Schedule paused"
                    else -> "No schedule"
                },
                modifier = Modifier.weight(1f),
                onClick = onOpenLight,
            )
            Tile(
                icon = Icons.Rounded.Router,
                title = "Controller",
                value = status?.clock?.take(5) ?: "--:--",
                caption = if (online) "Device clock" else (state.linkError ?: "Not responding"),
                tint = if (online) Ocean.Cyan else Ocean.Coral,
                modifier = Modifier.weight(1f),
            )
        }

        VSpace(26.dp)

        SectionHeader("Today")

        val slots = todaySlots(feedingTimes, feedings, java.time.Instant.ofEpochMilli(now))
        val nextSlot = slots.firstOrNull { it.state != SlotState.DONE }
        val dueTasks = tasks.filter { it.lastDoneAt != null && it.isDue }
        val latestTest = tests.firstOrNull()
        val fishCount = life.filter { it.type == LifeType.FISH }.sumOf { it.count }
        val plantCount = life.filter { it.type == LifeType.PLANT }.sumOf { it.count }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Tile(
                icon = Icons.Rounded.SetMeal,
                title = "Feeding",
                value = when {
                    nextSlot != null && nextSlot.state == SlotState.MISSED -> "Due now"
                    nextSlot != null -> "Next ${nextSlot.time}"
                    feedingTimes.isNotEmpty() -> "All fed"
                    else -> "No times set"
                },
                caption = feedings.firstOrNull()?.let { "Last: ${it.byName}, ${relativeTime(it.at)}" } ?: "Nothing logged yet",
                tint = if (nextSlot?.state == SlotState.MISSED) Ocean.Amber else Ocean.Sky,
                modifier = Modifier.weight(1f),
            ) { onOpen("feeding") }
            Tile(
                icon = Icons.Rounded.CleaningServices,
                title = "Maintenance",
                value = if (dueTasks.isEmpty()) "All good" else "${dueTasks.size} due",
                caption = dueTasks.firstOrNull()?.name ?: tasks.mapNotNull { t -> t.daysUntilDue()?.let { t to it } }
                    .minByOrNull { it.second }?.let { (t, d) -> "${t.name} in ${d}d" } ?: "Log tasks in Care",
                tint = if (dueTasks.isEmpty()) Ocean.Sky else Ocean.Amber,
                modifier = Modifier.weight(1f),
            ) { onOpen("care") }
        }
        VSpace(14.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Tile(
                icon = Icons.Rounded.Spa,
                title = "In the tank",
                value = "$fishCount fish",
                caption = "$plantCount plants",
                modifier = Modifier.weight(1f),
            ) { onOpen("tank") }
            Tile(
                icon = Icons.Rounded.Science,
                title = "Water",
                value = latestTest?.ph?.let { "pH ${formatNumber(it)}" }
                    ?: latestTest?.let { "Tested" } ?: "Not tested",
                caption = latestTest?.let { "Tested ${daysAgo(it.at)}" } ?: "Log a test in Care",
                modifier = Modifier.weight(1f),
            ) { onOpen("care") }
        }
    }
}

@Composable
private fun Avatar(name: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Ocean.Blue, Ocean.Navy)))
            .border(1.dp, Ocean.GlassEdge, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull()?.uppercase() ?: "☺",
            style = MaterialTheme.typography.titleLarge,
            color = Ocean.Text,
        )
    }
}

@Composable
private fun Tile(
    icon: ImageVector,
    title: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = Ocean.Sky,
    onClick: (() -> Unit)? = null,
) {
    GlassCard(modifier, padding = androidx.compose.foundation.layout.PaddingValues(16.dp), onClick = onClick) {
        IconBadge(icon, tint)
        VSpace(14.dp)
        Text(title, style = MaterialTheme.typography.labelMedium, color = Ocean.TextMuted)
        Text(value, style = MaterialTheme.typography.titleLarge, color = Ocean.Text, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = Ocean.TextFaint, maxLines = 2)
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}
