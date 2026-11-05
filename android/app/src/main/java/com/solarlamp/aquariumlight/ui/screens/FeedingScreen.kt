package com.solarlamp.aquariumlight.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.SetMeal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.solarlamp.aquariumlight.AppSettings
import com.solarlamp.aquariumlight.Reminders
import com.solarlamp.aquariumlight.data.Cloud
import com.solarlamp.aquariumlight.data.Feeding
import com.solarlamp.aquariumlight.data.TimeOfDay
import com.solarlamp.aquariumlight.ui.components.GlassCard
import com.solarlamp.aquariumlight.ui.components.IconBadge
import com.solarlamp.aquariumlight.ui.components.MutedText
import com.solarlamp.aquariumlight.ui.components.PillButton
import com.solarlamp.aquariumlight.ui.components.PillStyle
import com.solarlamp.aquariumlight.ui.components.SectionHeader
import com.solarlamp.aquariumlight.ui.components.StatusChip
import com.solarlamp.aquariumlight.ui.components.VSpace
import com.solarlamp.aquariumlight.ui.theme.Ocean
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class SlotState { DONE, MISSED, UPCOMING }

data class FeedingSlot(val time: TimeOfDay, val state: SlotState, val feeding: Feeding?)

/**
 * Today's feeding slots. A feeding counts for a slot if it happened from two hours
 * before that slot up to two hours before the next one.
 */
fun todaySlots(times: List<TimeOfDay>, feedings: List<Feeding>, now: Instant = Instant.now()): List<FeedingSlot> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    fun at(t: TimeOfDay) = today.atTime(t.hour, t.minute).atZone(zone).toInstant()
    val window = Reminders.FED_WINDOW_HOURS * 3600

    return times.mapIndexed { i, t ->
        val start = at(t).minusSeconds(window)
        val end = times.getOrNull(i + 1)?.let { at(it).minusSeconds(window) }
            ?: today.plusDays(1).atStartOfDay(zone).toInstant()
        val fed = feedings.firstOrNull { !it.at.isBefore(start) && it.at.isBefore(end) }
        FeedingSlot(
            time = t,
            state = when {
                fed != null -> SlotState.DONE
                now.isAfter(at(t)) -> SlotState.MISSED
                else -> SlotState.UPCOMING
            },
            feeding = fed,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedingScreen() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val times by remember { Cloud.feedingTimesFlow() }.collectAsState(initial = emptyList())
    val feedings by remember { Cloud.feedingsFlow() }.collectAsState(initial = emptyList())
    val now by rememberNow()

    var noteDialog by remember { mutableStateOf(false) }
    var addingTime by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Feeding?>(null) }
    var remindersOn by remember { mutableStateOf(settings.feedingReminders) }

    val slots = todaySlots(times, feedings, Instant.ofEpochMilli(now))
    val lastFeeding = feedings.firstOrNull()
    val nextSlot = slots.firstOrNull { it.state != SlotState.DONE }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding),
    ) {
        ScreenTitle("Feeding")
        VSpace(12.dp)

        // Hero: the big "Fed" button
        GlassCard(Modifier.fillMaxWidth(), glow = Ocean.Blue) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.SetMeal)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            nextSlot == null && times.isNotEmpty() -> "All fed for today"
                            nextSlot != null && nextSlot.state == SlotState.MISSED -> "Feeding due (${nextSlot.time})"
                            nextSlot != null -> "Next feeding ${nextSlot.time}"
                            else -> "No feeding times set"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = if (nextSlot?.state == SlotState.MISSED) Ocean.Amber else Ocean.Text,
                    )
                    MutedText(lastFeeding?.let { "Last fed ${relativeTime(it.at)} by ${it.byName}" } ?: "No feedings logged yet")
                }
            }
            VSpace(18.dp)
            PillButton(
                text = "Fed",
                onClick = { Cloud.logFeeding("") },
                style = PillStyle.PRIMARY,
                icon = Icons.Rounded.Check,
                modifier = Modifier.fillMaxWidth(),
            )
            VSpace(8.dp)
            PillButton(
                text = "Fed, with a note",
                onClick = { noteDialog = true },
                icon = Icons.Rounded.EditNote,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Today
        if (slots.isNotEmpty()) {
            VSpace(24.dp)
            SectionHeader("Today")
            GlassCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                for (slot in slots) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(slot.time.toString(), style = MaterialTheme.typography.titleLarge, color = Ocean.Text)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            when (slot.state) {
                                SlotState.DONE -> "${slot.feeding!!.byName} · ${slot.feeding.at.clock()}"
                                SlotState.MISSED -> "Not fed yet"
                                SlotState.UPCOMING -> "Upcoming"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ocean.TextMuted,
                            modifier = Modifier.weight(1f),
                        )
                        when (slot.state) {
                            SlotState.DONE -> StatusChip("Fed", Ocean.Cyan)
                            SlotState.MISSED -> StatusChip("Due", Ocean.Amber)
                            SlotState.UPCOMING -> StatusChip("Later", Ocean.TextMuted)
                        }
                    }
                }
            }
        }

        // Times and reminders
        VSpace(24.dp)
        SectionHeader("Feeding times")
        GlassCard(Modifier.fillMaxWidth()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in times) {
                    TimeChip(t.toString()) { Cloud.setFeedingTimes(times - t) }
                }
                AddChip { addingTime = true }
            }
            VSpace(16.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Remind me on this phone", style = MaterialTheme.typography.titleMedium, color = Ocean.Text)
                    MutedText("Skipped automatically if someone has already fed.")
                }
                Switch(
                    checked = remindersOn,
                    onCheckedChange = {
                        remindersOn = it
                        settings.feedingReminders = it
                        Reminders.rescheduleAll(context)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Ocean.Blue, checkedThumbColor = Color.White, uncheckedTrackColor = Ocean.Deep),
                )
            }
        }

        // Log
        VSpace(24.dp)
        SectionHeader("Log")
        if (feedings.isEmpty()) EmptyNote("Feedings appear here once someone taps Fed.")
        GlassCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            for (f in feedings.take(30)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { deleting = f }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${f.byName} fed", style = MaterialTheme.typography.titleMedium, color = Ocean.Text)
                        if (f.note.isNotBlank()) MutedText(f.note)
                    }
                    Text(relativeTime(f.at), style = MaterialTheme.typography.bodyMedium, color = Ocean.TextFaint)
                }
            }
        }
    }

    if (noteDialog) {
        InputDialog(
            title = "Fed the fish",
            fields = listOf(InputField("Note (food, amount…)")),
            confirmText = "Log feeding",
            onDismiss = { noteDialog = false },
        ) { (note) ->
            Cloud.logFeeding(note)
            noteDialog = false
        }
    }

    if (addingTime) {
        TimePickDialog(
            title = "Add a feeding time",
            initial = TimeOfDay.of(8, 0),
            onDismiss = { addingTime = false },
        ) { t ->
            Cloud.setFeedingTimes(times + t)
            addingTime = false
        }
    }

    deleting?.let { f ->
        ConfirmDialog(
            title = "Remove this entry?",
            text = "${f.byName}'s feeding ${relativeTime(f.at)} will be removed from the log.",
            confirmText = "Remove",
            danger = true,
            onDismiss = { deleting = null },
        ) {
            Cloud.deleteFeeding(f)
            deleting = null
        }
    }
}

@Composable
private fun TimeChip(text: String, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(Ocean.GlassStrong)
            .border(1.dp, Ocean.GlassEdge, shape)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Ocean.Text)
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Rounded.Close,
            contentDescription = "Remove $text",
            tint = Ocean.TextMuted,
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(50)).clickable(onClick = onRemove).padding(5.dp),
        )
    }
}

@Composable
fun AddChip(label: String = "Add", onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .border(1.dp, Ocean.Sky.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = Ocean.Sky, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Ocean.Sky)
    }
}
