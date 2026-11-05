package com.solarlamp.aquariumlight.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.solarlamp.aquariumlight.data.TimeOfDay
import com.solarlamp.aquariumlight.ui.components.MutedText
import com.solarlamp.aquariumlight.ui.theme.Ocean
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// =========================================================
// Time formatting
// =========================================================

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")

fun Instant.localDate(): LocalDate = atZone(ZoneId.systemDefault()).toLocalDate()

fun Instant.clock(): String = atZone(ZoneId.systemDefault()).format(clockFormat)

/** "just now", "12 min ago", "Today 08:05", "Yesterday 20:10", "Mon 3 Nov 09:00". */
fun relativeTime(at: Instant, now: Instant = Instant.now()): String {
    val minutes = ChronoUnit.MINUTES.between(at, now)
    if (minutes in 0..0) return "just now"
    if (minutes in 1..59) return "$minutes min ago"
    return "${dayLabel(at.localDate())} ${at.clock()}"
}

fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(dayFormat)
}

/** "today", "yesterday", "3 days ago". */
fun daysAgo(at: Instant): String {
    val days = ChronoUnit.DAYS.between(at.localDate(), LocalDate.now())
    return when (days) {
        0L -> "today"
        1L -> "yesterday"
        else -> "$days days ago"
    }
}

// =========================================================
// Segmented tabs
// =========================================================

@Composable
fun SegmentedTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(Ocean.Abyss.copy(alpha = 0.5f))
            .border(1.dp, Ocean.GlassEdgeFaint, shape)
            .padding(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .then(
                        if (active) Modifier.background(Brush.horizontalGradient(listOf(Ocean.Blue, Ocean.Sky)))
                        else Modifier
                    )
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) Ocean.Text else Ocean.TextMuted)
            }
        }
    }
}

/** Horizontally scrolling filter chips. */
@Composable
fun FilterChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            val shape = RoundedCornerShape(50)
            Box(
                Modifier
                    .clip(shape)
                    .background(if (active) Ocean.Blue else Ocean.GlassBottom)
                    .border(1.dp, if (active) Ocean.Sky else Ocean.GlassEdgeFaint, shape)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (active) Ocean.Text else Ocean.TextMuted)
            }
        }
    }
}

@Composable
fun EmptyNote(text: String) {
    MutedText(text, Modifier.fillMaxWidth().padding(vertical = 24.dp))
}

// =========================================================
// Dialogs
// =========================================================

data class InputField(
    val label: String,
    val initial: String = "",
    val keyboard: KeyboardType = KeyboardType.Text,
    val capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
)

/** A dialog with one or more text fields. [onConfirm] gets the values in order. */
@Composable
fun InputDialog(
    title: String,
    fields: List<InputField>,
    confirmText: String = "Save",
    onDismiss: () -> Unit,
    extraAction: (@Composable () -> Unit)? = null,
    onConfirm: (List<String>) -> Unit,
) {
    val values = remember { mutableStateListOf(*fields.map { it.initial }.toTypedArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ocean.Deep,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                fields.forEachIndexed { i, field ->
                    OutlinedTextField(
                        value = values[i],
                        onValueChange = { values[i] = it },
                        label = { Text(field.label) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = field.keyboard,
                            capitalization = field.capitalization,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                extraAction?.invoke()
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(values.toList()) }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    danger: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ocean.Deep,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText, color = if (danger) Ocean.Coral else Ocean.Sky) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickDialog(
    title: String,
    initial: TimeOfDay,
    onDismiss: () -> Unit,
    onPick: (TimeOfDay) -> Unit,
) {
    val pickerState = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ocean.Deep,
        title = { Text(title) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onPick(TimeOfDay.of(pickerState.hour, pickerState.minute)) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickDialog(onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                if (millis != null) {
                    val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    onPick(if (picked.isAfter(today)) today else picked)
                }
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}
