package com.solarlamp.aquariumlight.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.solarlamp.aquariumlight.data.Cloud
import com.solarlamp.aquariumlight.data.DeviceRepository
import com.solarlamp.aquariumlight.data.DeviceState
import com.solarlamp.aquariumlight.data.DeviceStatus
import com.solarlamp.aquariumlight.data.FirmwareRelease
import com.solarlamp.aquariumlight.data.HistoryType
import com.solarlamp.aquariumlight.ui.components.GlassCard
import com.solarlamp.aquariumlight.ui.components.MutedText
import com.solarlamp.aquariumlight.ui.components.PillButton
import com.solarlamp.aquariumlight.ui.components.PillStyle
import com.solarlamp.aquariumlight.ui.components.StatusChip
import com.solarlamp.aquariumlight.ui.components.VSpace
import com.solarlamp.aquariumlight.ui.theme.Ocean
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

// =========================================================
// Light time: on today, scheduled per day, scheduled left today
// =========================================================

@Composable
fun LightTimeCard(status: DeviceStatus) {
    val perDay = status.scheduledMinutesPerDay
    val left = status.scheduledMinutesLeftToday
    val onMinutes = status.onTodaySeconds?.let { (it / 60).toInt() }

    GlassCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(
                label = "On today",
                value = onMinutes?.let { formatDuration(it) } ?: "—",
                caption = if (onMinutes == null) "Needs firmware update" else "Any mode, incl. forced",
                color = Ocean.Amber,
                modifier = Modifier.weight(1f),
            )
            Stat(
                label = "Scheduled / day",
                value = if (perDay == 0) "None" else formatDuration(perDay),
                caption = "Both slots",
                color = Ocean.Text,
                modifier = Modifier.weight(1f),
            )
            Stat(
                label = "Left today",
                value = left?.let { if (it == 0) "Done" else formatDuration(it) } ?: "—",
                caption = "On the schedule",
                color = Ocean.Sky,
                modifier = Modifier.weight(1f),
            )
        }

        if (onMinutes != null && perDay > 0) {
            VSpace(16.dp)
            // Bar: on-time so far against the day's scheduled total.
            val fraction = (onMinutes.toFloat() / perDay).coerceIn(0f, 1.25f)
            Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(Ocean.Abyss.copy(alpha = 0.6f), Offset.Zero, size, r)
                drawRoundRect(
                    Brush.horizontalGradient(listOf(Ocean.Amber, Color(0xFFFFE3A3))),
                    Offset.Zero,
                    Size(size.width * (fraction / 1.25f), size.height),
                    r,
                )
                // Marker where 100% of the scheduled time sits.
                val full = size.width / 1.25f
                drawLine(Color.White.copy(alpha = 0.7f), Offset(full, -4f), Offset(full, size.height + 4f), 3f)
            }
            VSpace(6.dp)
            MutedText(
                if (onMinutes >= perDay) "On longer than scheduled today (${formatDuration(onMinutes - perDay)} extra)"
                else "${(onMinutes * 100 / perDay)}% of today's scheduled time",
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, caption: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Ocean.TextMuted, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = Ocean.TextFaint, maxLines = 2)
    }
}

// =========================================================
// Clock (RTC)
// =========================================================

private val deviceDateFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm:ss")

@Composable
fun ClockCard(state: DeviceState, online: Boolean, isAdmin: Boolean) {
    val status = state.status
    var feedback by remember { mutableStateOf<String?>(null) }

    GlassCard(Modifier.fillMaxWidth()) {
        if (status?.timeSource == null) {
            Text(status?.clock?.let { "Device time $it" } ?: "—", style = MaterialTheme.typography.titleLarge, color = Ocean.Text)
            VSpace(6.dp)
            MutedText("Clock details (date, coin cell, drift) appear after the next firmware update.")
            return@GlassCard
        }

        // Device time vs this phone, measured when the report arrived.
        val deviceTime = status.dateTime
        val phoneAtReport = if (state.statusAt > 0) {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(state.statusAt), ZoneId.systemDefault())
        } else null
        val driftSeconds = if (deviceTime != null && phoneAtReport != null) {
            Duration.between(phoneAtReport, deviceTime).seconds
        } else null

        Text(
            deviceTime?.format(deviceDateFormat) ?: "Time not set",
            style = MaterialTheme.typography.titleLarge,
            color = if (deviceTime == null) Ocean.Coral else Ocean.Text,
        )
        VSpace(10.dp)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                driftSeconds == null -> Unit
                abs(driftSeconds) < 30 -> StatusChip("In sync", Ocean.Cyan)
                else -> StatusChip(
                    "${formatDuration((abs(driftSeconds) / 60).toInt().coerceAtLeast(1))} ${if (driftSeconds > 0) "ahead" else "behind"}",
                    Ocean.Amber,
                )
            }
            StatusChip(
                when (status.timeSource) {
                    "rtc" -> "From RTC"
                    "ntp" -> "From internet"
                    else -> "No valid time"
                },
                if (status.timeSource == "none") Ocean.Coral else Ocean.Sky,
            )
        }

        VSpace(14.dp)
        InfoRow("Coin cell", when {
            status.rtcFound == false -> "RTC not detected; using internet time only"
            status.rtcLostPower == true -> "Likely flat or missing: the RTC had lost its time at the last power-up. Replace the CR2032 cell."
            else -> "OK"
        }, warn = status.rtcFound == false || status.rtcLostPower == true)
        InfoRow("Last internet sync", when (val ago = status.ntpAgoSeconds) {
            null, -1L -> "Not since the last restart"
            else -> if (ago < 60) "Just now" else "${formatDuration((ago / 60).toInt())} ago"
        })
        status.rtcTemp?.let { InfoRow("RTC temperature", "%.1f °C".format(it)) }
        status.uptimeSeconds?.let { up ->
            InfoRow("Running for", formatDuration((up / 60).toInt()) + (status.resetReason?.let { " · last restart: $it" } ?: ""))
        }

        if (isAdmin) {
            VSpace(14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    "Sync from internet",
                    {
                        feedback = if (DeviceRepository.syncTime()) {
                            Cloud.log(HistoryType.LIGHT, "Asked the controller to re-sync its clock")
                            "Requested. The time updates within a minute if the device is online."
                        } else "Not connected."
                    },
                    icon = Icons.Rounded.CloudSync,
                    enabled = online,
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    "Set from phone",
                    {
                        feedback = if (DeviceRepository.setTimeFromPhone()) {
                            Cloud.log(HistoryType.LIGHT, "Set the controller clock from a phone")
                            "Clock set to this phone's time."
                        } else "Not connected."
                    },
                    icon = Icons.Rounded.PhoneAndroid,
                    enabled = online,
                    modifier = Modifier.weight(1f),
                )
            }
            feedback?.let { MutedText(it, Modifier.padding(top = 10.dp)) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Ocean.TextMuted, modifier = Modifier.weight(0.42f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (warn) Ocean.Amber else Ocean.Text, modifier = Modifier.weight(0.58f))
    }
}

// =========================================================
// Firmware (admin)
// =========================================================

@Composable
fun FirmwareCard(status: DeviceStatus?, online: Boolean) {
    val latest by produceState<String?>(null) { value = FirmwareRelease.latestVersion() }
    var requested by remember { mutableStateOf(false) }
    val current = status?.firmware
    val updateAvailable = latest != null && current != null && current != latest

    // Clear "requested" once the device reports a new version or an error.
    LaunchedEffect(current, status?.updateMessage) {
        if (current == latest || (status?.updateMessage?.startsWith("Failed") == true)) requested = false
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Text("Firmware", style = MaterialTheme.typography.titleLarge, color = Ocean.Text)
        VSpace(6.dp)
        InfoRow("On the controller", current ?: "Older than 1.x (no OTA yet)")
        InfoRow("Latest", latest ?: "Checking…")

        status?.updateMessage?.takeIf { it.isNotBlank() }?.let {
            InfoRow("Last update", it, warn = it.startsWith("Failed"))
        }

        VSpace(12.dp)
        when {
            current == null -> MutedText(
                "This controller still runs firmware without remote updates. Flash the latest firmware once over USB; after that, updates install from here.",
            )
            requested -> MutedText("Updating… The controller downloads the update, restarts and reconnects in about a minute. The light keeps its state.")
            updateAvailable -> PillButton(
                "Update to $latest",
                {
                    if (DeviceRepository.updateFirmware()) {
                        requested = true
                        Cloud.log(HistoryType.LIGHT, "Started a controller firmware update ($current → $latest)")
                    }
                },
                style = PillStyle.PRIMARY,
                icon = Icons.Rounded.SystemUpdate,
                enabled = online,
                modifier = Modifier.fillMaxWidth(),
            )
            latest != null -> MutedText("Up to date.")
        }
    }
}
