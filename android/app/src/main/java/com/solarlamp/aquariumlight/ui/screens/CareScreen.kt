package com.solarlamp.aquariumlight.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Window
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.solarlamp.aquariumlight.AppSettings
import com.solarlamp.aquariumlight.Reminders
import com.solarlamp.aquariumlight.data.CareTask
import com.solarlamp.aquariumlight.data.Cloud
import com.solarlamp.aquariumlight.data.TaskKind
import com.solarlamp.aquariumlight.data.WaterParam
import com.solarlamp.aquariumlight.data.WaterTest
import com.solarlamp.aquariumlight.data.formatNumber
import com.solarlamp.aquariumlight.ui.components.GlassCard
import com.solarlamp.aquariumlight.ui.components.IconBadge
import com.solarlamp.aquariumlight.ui.components.MutedText
import com.solarlamp.aquariumlight.ui.components.PillButton
import com.solarlamp.aquariumlight.ui.components.PillStyle
import com.solarlamp.aquariumlight.ui.components.SectionHeader
import com.solarlamp.aquariumlight.ui.components.StatusChip
import com.solarlamp.aquariumlight.ui.components.VSpace
import com.solarlamp.aquariumlight.ui.theme.Ocean
import kotlin.math.abs

@Composable
fun CareScreen() {
    var tab by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding),
    ) {
        ScreenTitle("Care")
        VSpace(12.dp)
        SegmentedTabs(listOf("Maintenance", "Water tests"), tab, { tab = it }, Modifier.fillMaxWidth())
        VSpace(20.dp)
        if (tab == 0) MaintenanceTab() else WaterTab()
    }
}

// =========================================================
// Maintenance
// =========================================================

@Composable
private fun MaintenanceTab() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val tasks by remember { Cloud.tasksFlow() }.collectAsState(initial = null)
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CareTask?>(null) }
    var pickingDateFor by remember { mutableStateOf<CareTask?>(null) }
    var remindersOn by remember { mutableStateOf(settings.careReminders) }

    LaunchedEffect(tasks) { tasks?.let { Cloud.seedTasksIfEmpty(it) } }

    val list = tasks ?: run {
        EmptyNote("Loading…")
        return
    }

    val due = list.count { it.lastDoneAt != null && it.isDue }
    MutedText(
        when {
            list.isEmpty() -> "No tasks yet."
            due == 0 -> "All caught up."
            due == 1 -> "1 task is due."
            else -> "$due tasks are due."
        },
    )
    VSpace(12.dp)

    for (task in list) {
        TaskCard(
            task = task,
            onDone = { Cloud.markDone(task) },
            onDoneEarlier = { pickingDateFor = task },
            onEdit = { editing = task },
        )
        VSpace(12.dp)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton("Add task", { adding = true }, icon = Icons.Rounded.Add, modifier = Modifier.weight(1f))
        PillButton(
            "Add filter",
            {
                val n = list.count { it.kind == TaskKind.FILTER } + 1
                Cloud.addTask("Filter $n", TaskKind.FILTER, 14, (list.maxOfOrNull { it.order } ?: 0L) + 1)
            },
            icon = Icons.Rounded.FilterAlt,
            modifier = Modifier.weight(1f),
        )
    }

    VSpace(16.dp)
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remind me when tasks are due", style = MaterialTheme.typography.titleMedium, color = Ocean.Text)
                MutedText("A daily check at 09:30 on this phone.")
            }
            Switch(
                checked = remindersOn,
                onCheckedChange = {
                    remindersOn = it
                    settings.careReminders = it
                    Reminders.rescheduleAll(context)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Ocean.Blue, checkedThumbColor = Color.White, uncheckedTrackColor = Ocean.Deep),
            )
        }
    }

    if (adding) {
        InputDialog(
            title = "New task",
            fields = listOf(
                InputField("Name", capitalization = KeyboardCapitalization.Sentences),
                InputField("Every how many days", "7", KeyboardType.Number),
            ),
            confirmText = "Add",
            onDismiss = { adding = false },
        ) { (name, days) ->
            val interval = days.trim().toIntOrNull()?.coerceIn(1, 365) ?: 7
            if (name.isNotBlank()) {
                val kind = if (name.contains("filter", ignoreCase = true)) TaskKind.FILTER else TaskKind.OTHER
                Cloud.addTask(name, kind, interval, (list.maxOfOrNull { it.order } ?: 0L) + 1)
            }
            adding = false
        }
    }

    editing?.let { task ->
        var confirmDelete by remember(task.id) { mutableStateOf(false) }
        if (confirmDelete) {
            ConfirmDialog(
                title = "Delete \"${task.name}\"?",
                text = "Its history entries stay, but the task and its last-done date are removed.",
                confirmText = "Delete",
                danger = true,
                onDismiss = { confirmDelete = false },
            ) {
                Cloud.deleteTask(task)
                confirmDelete = false
                editing = null
            }
        } else {
            InputDialog(
                title = "Edit task",
                fields = listOf(
                    InputField("Name", task.name),
                    InputField("Every how many days", task.intervalDays.toString(), KeyboardType.Number),
                ),
                onDismiss = { editing = null },
                extraAction = {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete task", color = Ocean.Coral) }
                },
            ) { (name, days) ->
                val interval = days.trim().toIntOrNull()?.coerceIn(1, 365) ?: task.intervalDays
                if (name.isNotBlank()) Cloud.updateTask(task, name, interval)
                editing = null
            }
        }
    }

    pickingDateFor?.let { task ->
        DatePickDialog(onDismiss = { pickingDateFor = null }) { date ->
            Cloud.markDone(task, date)
            pickingDateFor = null
        }
    }
}

@Composable
private fun TaskCard(task: CareTask, onDone: () -> Unit, onDoneEarlier: () -> Unit, onEdit: () -> Unit) {
    val days = task.daysUntilDue()
    val overdue = days != null && days < 0
    GlassCard(Modifier.fillMaxWidth(), glow = if (overdue) Ocean.Amber else null, onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                when (task.kind) {
                    TaskKind.WATER_CHANGE -> Icons.Rounded.WaterDrop
                    TaskKind.GLASS -> Icons.Rounded.Window
                    TaskKind.FILTER -> Icons.Rounded.FilterAlt
                    else -> Icons.Rounded.CleaningServices
                },
                if (overdue) Ocean.Amber else Ocean.Sky,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(task.name, style = MaterialTheme.typography.titleLarge, color = Ocean.Text)
                MutedText("Every ${task.intervalDays} days")
            }
            when {
                days == null -> StatusChip("Not logged", Ocean.TextMuted)
                days < 0 -> StatusChip("${abs(days)}d overdue", Ocean.Amber)
                days == 0L -> StatusChip("Due today", Ocean.Amber)
                else -> StatusChip("In ${days}d", Ocean.Cyan)
            }
        }
        VSpace(12.dp)
        MutedText(
            task.lastDoneAt?.let { "Last done ${daysAgo(it)}" + (task.lastDoneBy?.let { by -> " by $by" } ?: "") }
                ?: "Never logged. Mark it done to start tracking.",
        )
        VSpace(12.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Done today", onDone, style = PillStyle.PRIMARY, icon = Icons.Rounded.Check, modifier = Modifier.weight(1f))
            PillButton("Earlier…", onDoneEarlier, icon = Icons.Rounded.Event, modifier = Modifier.weight(1f))
        }
    }
}

// =========================================================
// Water tests
// =========================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaterTab() {
    val tests by remember { Cloud.waterTestsFlow() }.collectAsState(initial = emptyList())
    var logging by remember { mutableStateOf(false) }
    var chartParam by remember { mutableStateOf(WaterParam.NITRATE) }
    var deleting by remember { mutableStateOf<WaterTest?>(null) }

    PillButton(
        "Log a water test",
        { logging = true },
        style = PillStyle.PRIMARY,
        icon = Icons.Rounded.Science,
        modifier = Modifier.fillMaxWidth(),
    )
    VSpace(20.dp)

    if (tests.isEmpty()) {
        EmptyNote("No tests yet. Log one after testing with your kit; any values you skip are left blank.")
        LogDialog(logging) { logging = false }
        return
    }

    // Latest value of each parameter
    SectionHeader("Latest")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (param in WaterParam.entries) {
            val latest = tests.firstOrNull { param.valueOf(it) != null }
            val value = latest?.let { param.valueOf(it) }
            val healthy = value == null || value in param.healthy
            GlassCard(
                Modifier.width(150.dp),
                padding = PaddingValues(14.dp),
                onClick = { chartParam = param },
            ) {
                Text(param.label, style = MaterialTheme.typography.labelMedium, color = Ocean.TextMuted)
                Text(
                    value?.let { formatNumber(it) + if (param.unit.isNotEmpty()) " ${param.unit}" else "" } ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (healthy) Ocean.Text else Ocean.Amber,
                )
                Text(
                    latest?.let { daysAgo(it.at) } ?: "not tested",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ocean.TextFaint,
                )
            }
        }
    }

    // Trend
    VSpace(24.dp)
    SectionHeader("Trend")
    FilterChips(WaterParam.entries.map { it.label }, WaterParam.entries.indexOf(chartParam)) {
        chartParam = WaterParam.entries[it]
    }
    VSpace(12.dp)
    GlassCard(Modifier.fillMaxWidth()) {
        val points = tests.mapNotNull { t -> chartParam.valueOf(t)?.let { t.at.toEpochMilli() to it } }.reversed()
        if (points.size < 2) {
            MutedText("Log at least two ${chartParam.label} readings to see a trend.")
        } else {
            TrendChart(points, chartParam)
            VSpace(8.dp)
            MutedText(
                "Healthy range: ${formatNumber(chartParam.healthy.start)}–${formatNumber(chartParam.healthy.endInclusive)}" +
                    (if (chartParam.unit.isNotEmpty()) " ${chartParam.unit}" else "") + " (shaded)",
            )
        }
    }

    // History
    VSpace(24.dp)
    SectionHeader("All tests")
    GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 8.dp)) {
        for (t in tests.take(40)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Row {
                    Text(relativeTime(t.at), style = MaterialTheme.typography.titleMedium, color = Ocean.Text, modifier = Modifier.weight(1f))
                    Text("by ${t.byName}", style = MaterialTheme.typography.bodyMedium, color = Ocean.TextFaint)
                }
                MutedText(
                    WaterParam.entries.mapNotNull { p ->
                        p.valueOf(t)?.let { "${p.label} ${formatNumber(it)}" }
                    }.joinToString(" · "),
                )
                if (t.note.isNotBlank()) MutedText(t.note)
                TextButton(onClick = { deleting = t }) { Text("Remove", color = Ocean.TextFaint) }
            }
        }
    }

    LogDialog(logging) { logging = false }

    deleting?.let { t ->
        ConfirmDialog(
            title = "Remove this test?",
            text = "The test from ${relativeTime(t.at)} will be removed.",
            confirmText = "Remove",
            danger = true,
            onDismiss = { deleting = null },
        ) {
            Cloud.deleteWaterTest(t)
            deleting = null
        }
    }
}

@Composable
private fun LogDialog(show: Boolean, onClose: () -> Unit) {
    if (!show) return
    val params = WaterParam.entries
    InputDialog(
        title = "Water test",
        fields = params.map {
            InputField(if (it.unit.isEmpty()) it.label else "${it.label} (${it.unit})", keyboard = KeyboardType.Decimal)
        } + InputField("Note"),
        confirmText = "Save",
        onDismiss = onClose,
    ) { values ->
        val readings = params.mapIndexedNotNull { i, p ->
            values[i].trim().replace(',', '.').toDoubleOrNull()?.let { p to it }
        }.toMap()
        if (readings.isNotEmpty()) Cloud.addWaterTest(readings, values.last())
        onClose()
    }
}

/** Simple line chart with the healthy range shaded. */
@Composable
private fun TrendChart(points: List<Pair<Long, Double>>, param: WaterParam) {
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        val xs = points.map { it.first }
        val ys = points.map { it.second }
        val minX = xs.min()
        val maxX = xs.max().coerceAtLeast(minX + 1)
        val minY = minOf(ys.min(), param.healthy.start)
        val maxY = maxOf(ys.max(), param.healthy.endInclusive).let { if (it == minY) minY + 1 else it }
        val pad = 8f

        fun x(v: Long) = pad + (size.width - 2 * pad) * (v - minX) / (maxX - minX).toFloat()
        fun y(v: Double) = size.height - pad - (size.height - 2 * pad) * ((v - minY) / (maxY - minY)).toFloat()

        // Healthy band
        drawRect(
            Ocean.Cyan.copy(alpha = 0.10f),
            topLeft = Offset(0f, y(param.healthy.endInclusive)),
            size = androidx.compose.ui.geometry.Size(size.width, y(param.healthy.start) - y(param.healthy.endInclusive)),
        )

        val path = Path()
        points.forEachIndexed { i, (px, py) ->
            if (i == 0) path.moveTo(x(px), y(py)) else path.lineTo(x(px), y(py))
        }
        drawPath(path, Ocean.Sky, style = Stroke(width = 5f))
        points.forEach { (px, py) ->
            val ok = py in param.healthy
            drawCircle(if (ok) Ocean.Sky else Ocean.Amber, radius = 8f, center = Offset(x(px), y(py)))
        }
    }
}
