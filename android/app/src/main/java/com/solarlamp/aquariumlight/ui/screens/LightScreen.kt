package com.solarlamp.aquariumlight.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.solarlamp.aquariumlight.data.Cloud
import com.solarlamp.aquariumlight.data.DeviceRepository
import com.solarlamp.aquariumlight.data.HistoryType
import com.solarlamp.aquariumlight.data.DeviceStatus
import com.solarlamp.aquariumlight.data.Mode
import com.solarlamp.aquariumlight.data.Slot
import com.solarlamp.aquariumlight.data.TimeOfDay
import com.solarlamp.aquariumlight.ui.components.BulbIllustration
import com.solarlamp.aquariumlight.ui.components.GlassCard
import com.solarlamp.aquariumlight.ui.components.MutedText
import com.solarlamp.aquariumlight.ui.components.PillButton
import com.solarlamp.aquariumlight.ui.components.PillStyle
import com.solarlamp.aquariumlight.ui.components.SectionHeader
import com.solarlamp.aquariumlight.ui.components.VSpace
import com.solarlamp.aquariumlight.ui.theme.Ocean
import kotlinx.coroutines.launch

private data class Feedback(val text: String, val isError: Boolean = false)

@Composable
fun LightScreen(isAdmin: Boolean) {
    val state by DeviceRepository.state.collectAsState()
    val now by rememberNow()
    val status = state.status
    val online = state.deviceOnline(now)
    val scope = rememberCoroutineScope()

    var modeFeedback by remember { mutableStateOf<Feedback?>(null) }
    var saveFeedback by remember { mutableStateOf<Feedback?>(null) }
    var saving by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<List<Slot>?>(null) }
    var picking by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }   // slot index, editing "on"
    var confirmReset by remember { mutableStateOf(false) }

    val slots = draft ?: status?.slots?.takeIf { it.size == 2 }

    fun sendMode(mode: Mode) {
        if (!DeviceRepository.setMode(mode)) {
            modeFeedback = Feedback("Not connected. Try again in a moment.", true)
            return
        }
        modeFeedback = Feedback("Sending…")
        scope.launch {
            if (DeviceRepository.confirm { it.mode == mode }) {
                modeFeedback = Feedback("✓ ${mode.label}")
                Cloud.log(HistoryType.LIGHT, "Set the light to ${mode.label}")
            } else {
                modeFeedback = Feedback("The device didn't confirm. It may be offline.", true)
            }
        }
    }

    fun save(toSave: List<Slot>) {
        toSave.forEachIndexed { i, s ->
            if (s.enabled && s.on == s.off) {
                saveFeedback = Feedback("Slot ${i + 1}: on and off times can't be the same.", true)
                return
            }
        }
        if (!DeviceRepository.saveSchedule(toSave)) {
            saveFeedback = Feedback("Not connected. Try again in a moment.", true)
            return
        }
        saving = true
        saveFeedback = Feedback("Saving…")
        scope.launch {
            val ok = DeviceRepository.confirm { reported -> sameSchedule(reported, toSave) }
            saving = false
            if (ok) {
                draft = null
                saveFeedback = Feedback("✓ Saved on the device")
                Cloud.log(HistoryType.LIGHT, "Changed the light schedule: " + toSave.mapIndexed { i, sl ->
                    "slot ${i + 1} " + if (sl.enabled) "${sl.on}–${sl.off}" else "off"
                }.joinToString(", "))
            } else {
                saveFeedback = Feedback("The device didn't confirm. It may be offline.", true)
            }
        }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding),
    ) {
        ScreenTitle("Light", trailing = { ConnectionChip(state, now) })

        VSpace(12.dp)

        // Hero
        GlassCard(Modifier.fillMaxWidth(), glow = if (status?.lightOn == true) Ocean.Amber else null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BulbIllustration(on = status?.lightOn == true, modifier = Modifier.size(120.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        when (status?.lightOn) { true -> "On"; false -> "Off"; null -> "—" },
                        style = MaterialTheme.typography.displaySmall,
                        color = if (status?.lightOn == true) Ocean.Amber else Ocean.Text,
                    )
                    MutedText(status?.mode?.let { "Mode: ${it.label}" } ?: "Waiting for the device")
                    VSpace(4.dp)
                    MutedText(status?.clock?.let { "Device time $it" } ?: "")
                }
            }
        }

        VSpace(24.dp)
        SectionHeader("Mode")

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((mode, icon) in listOf(
                Mode.AUTO to Icons.Rounded.AutoMode,
                Mode.FORCE_ON to Icons.Rounded.WbSunny,
                Mode.FORCE_OFF to Icons.Rounded.NightsStay,
            )) {
                PillButton(
                    text = when (mode) { Mode.AUTO -> "Auto"; Mode.FORCE_ON -> "On"; Mode.FORCE_OFF -> "Off" },
                    onClick = { sendMode(mode) },
                    icon = icon,
                    selected = status?.mode == mode,
                    enabled = state.link == com.solarlamp.aquariumlight.data.Link.CONNECTED,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        FeedbackText(modeFeedback)
        MutedText(
            "Auto follows the schedule below. On and Off override it until you switch back to Auto.",
            Modifier.padding(top = 8.dp),
        )

        VSpace(24.dp)
        SectionHeader("Schedule")

        if (slots == null) {
            GlassCard(Modifier.fillMaxWidth()) { MutedText("The schedule appears once the device reports in.") }
        } else {
            GlassCard(Modifier.fillMaxWidth()) {
                DayTimeline(slots, status?.time)
            }

            VSpace(14.dp)

            slots.forEachIndexed { index, slot ->
                SlotCard(
                    index = index,
                    slot = slot,
                    editable = isAdmin,
                    onToggle = { enabled -> draft = slots.toMutableList().also { it[index] = slot.copy(enabled = enabled) } },
                    onPickOn = { picking = index to true },
                    onPickOff = { picking = index to false },
                )
                VSpace(14.dp)
            }

            if (!isAdmin) {
                MutedText("Only the admin can change the schedule.")
            } else PillButton(
                text = if (saving) "Saving…" else "Save schedule",
                onClick = { save(slots) },
                style = PillStyle.PRIMARY,
                icon = Icons.Rounded.Check,
                enabled = draft != null && !saving && state.link == com.solarlamp.aquariumlight.data.Link.CONNECTED,
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft != null && !saving) {
                TextButton(onClick = { draft = null; saveFeedback = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("Discard changes", color = Ocean.TextMuted)
                }
            }
            FeedbackText(saveFeedback)
        }

        if (isAdmin) {
        VSpace(24.dp)
        SectionHeader("Controller")

        GlassCard(Modifier.fillMaxWidth()) {
            MutedText(
                "To move the controller to a different WiFi network, restart it into setup mode. " +
                    "Then join the AquariumLight_Setup hotspot from a phone at home and enter the new network.",
            )
            VSpace(14.dp)
            PillButton(
                text = "Reconfigure WiFi",
                onClick = { confirmReset = true },
                style = PillStyle.DANGER,
                icon = Icons.Rounded.Wifi,
                enabled = online,
            )
        }
        }
    }

    // Dialogs
    val pick = picking
    if (pick != null && slots != null) {
        val (index, editingOn) = pick
        val slot = slots[index]
        TimePickDialog(
            title = "Slot ${index + 1} · ${if (editingOn) "on" else "off"}",
            initial = if (editingOn) slot.on else slot.off,
            onDismiss = { picking = null },
            onPick = { time ->
                draft = slots.toMutableList().also {
                    it[index] = if (editingOn) slot.copy(on = time) else slot.copy(off = time)
                }
                saveFeedback = null
                picking = null
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Ocean.Deep,
            title = { Text("Reconfigure WiFi?") },
            text = {
                Text(
                    "The controller will forget its WiFi and restart as the AquariumLight_Setup hotspot. " +
                        "Someone at home has to join it to enter the network details. " +
                        "The light keeps following its schedule meanwhile.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    if (DeviceRepository.resetWifi()) {
                        Cloud.log(HistoryType.LIGHT, "Restarted the controller into WiFi setup")
                    }
                }) { Text("Reconfigure", color = Ocean.Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

private fun sameSchedule(reported: DeviceStatus, sent: List<Slot>): Boolean =
    reported.slots.size == sent.size && reported.slots.zip(sent).all { (r, s) ->
        r.on == s.on && r.off == s.off && (!reported.reportsEnabledFlags || r.enabled == s.enabled)
    }

@Composable
private fun FeedbackText(feedback: Feedback?) {
    if (feedback == null) return
    Text(
        feedback.text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (feedback.isError) Ocean.Coral else Ocean.Sky,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    )
}

@Composable
private fun SlotCard(
    index: Int,
    slot: Slot,
    editable: Boolean,
    onToggle: (Boolean) -> Unit,
    onPickOn: () -> Unit,
    onPickOff: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Slot ${index + 1}", style = MaterialTheme.typography.titleLarge, color = Ocean.Text)
                MutedText(if (slot.enabled) "Active" else "Disabled")
            }
            Switch(
                checked = slot.enabled,
                onCheckedChange = onToggle,
                enabled = editable,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Ocean.Blue,
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = Ocean.Deep,
                ),
            )
        }
        VSpace(14.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeBox("On", slot.on, slot.enabled, editable, onPickOn, Modifier.weight(1f))
            TimeBox("Off", slot.off, slot.enabled, editable, onPickOff, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TimeBox(label: String, time: TimeOfDay, enabled: Boolean, editable: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(Ocean.Abyss.copy(alpha = if (enabled) 0.45f else 0.25f))
            .border(1.dp, Ocean.GlassEdgeFaint, shape)
            .clickable(enabled = enabled && editable, onClick = onClick)
            .padding(14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Ocean.TextMuted)
        Text(
            time.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = if (enabled) Ocean.Text else Ocean.TextFaint,
        )
    }
}

/** 24-hour bar: lit segments for each enabled slot, and a marker for now. */
@Composable
private fun DayTimeline(slots: List<Slot>, now: TimeOfDay?) {
    Column {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val barH = size.height * 0.55f
            val top = (size.height - barH) / 2f
            val radius = CornerRadius(barH / 2f)
            drawRoundRect(Ocean.Abyss.copy(alpha = 0.6f), Offset(0f, top), Size(size.width, barH), radius)

            fun x(minutes: Int) = size.width * minutes / 1440f
            val lit = Brush.horizontalGradient(listOf(Ocean.Amber, Color(0xFFFFE3A3)))

            for (slot in slots) {
                if (!slot.enabled || slot.on == slot.off) continue
                val ranges = if (slot.on.minutes < slot.off.minutes) listOf(slot.on.minutes to slot.off.minutes)
                else listOf(slot.on.minutes to 1440, 0 to slot.off.minutes)
                for ((a, b) in ranges) {
                    drawRoundRect(lit, Offset(x(a), top), Size(x(b) - x(a), barH), radius)
                }
            }

            if (now != null) {
                val nx = x(now.minutes)
                drawLine(Color.White, Offset(nx, 0f), Offset(nx, size.height), strokeWidth = 3f)
                drawCircle(Color.White, radius = 5f, center = Offset(nx, 0f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (h in listOf("00", "06", "12", "18", "24")) {
                Text(h, style = MaterialTheme.typography.labelMedium, color = Ocean.TextFaint)
            }
        }
    }
}
