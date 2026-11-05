package com.solarlamp.aquariumlight.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.solarlamp.aquariumlight.AppSettings
import com.solarlamp.aquariumlight.MonitorService
import com.solarlamp.aquariumlight.data.Access
import com.solarlamp.aquariumlight.data.Cloud
import com.solarlamp.aquariumlight.data.DeviceRepository
import com.solarlamp.aquariumlight.data.MemberStatus
import com.solarlamp.aquariumlight.ui.components.GlassCard
import com.solarlamp.aquariumlight.ui.components.MutedText
import com.solarlamp.aquariumlight.ui.components.SectionHeader
import com.solarlamp.aquariumlight.ui.components.VSpace
import com.solarlamp.aquariumlight.ui.theme.Ocean

private enum class Field { NAME, HOST, USERNAME, PASSWORD, PORT }

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenMembers: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }

    // Bumped after every change so the screen re-reads the stored values.
    var version by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Field?>(null) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }

    // Refresh the battery row when coming back from Android's dialog.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) batteryExempt = isBatteryExempt(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { MonitorService.sync(context) }

    val access by Cloud.access.collectAsState()
    val me = (access as? Access.Approved)?.member
    val isAdmin = me?.isAdmin == true
    val members by remember { Cloud.membersFlow() }.collectAsState(initial = emptyList())
    val pendingCount = members.count { it.status == MemberStatus.PENDING }

    fun brokerChanged() {
        Cloud.publishBroker(context)
        version++
        DeviceRepository.restart()
        MonitorService.sync(context, reconnect = true)
    }

    // Re-read stored values whenever something is changed on this screen.
    val current = remember(version) { Snapshot(settings) }
    val alertsOn = current.alertsOn

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding),
    ) {
        ScreenTitle("Settings", onBack = onBack)

        VSpace(12.dp)
        SectionHeader("Account")
        GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 6.dp)) {
            ValueRow("Your name", me?.displayName ?: "—") { editing = Field.NAME }
            RowDivider()
            ValueRow("Signed in as", me?.email ?: "—", onClick = null)
            if (isAdmin) {
                RowDivider()
                ValueRow(
                    "Members",
                    if (pendingCount > 0) "$pendingCount waiting for approval" else "Approve and manage who has access",
                    onClick = onOpenMembers,
                )
            }
        }
        VSpace(12.dp)
        SignOutButton()

        if (!isAdmin) {
            VSpace(20.dp)
            SectionHeader("Connection")
            MutedText("Connection details are shared by the admin automatically.")
        }

        if (isAdmin) {
        VSpace(20.dp)
        SectionHeader("Broker")
        GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 6.dp)) {
            ValueRow("Host", current.host) { editing = Field.HOST }
            RowDivider()
            ValueRow("Username", current.username.ifEmpty { "Not set" }) { editing = Field.USERNAME }
            RowDivider()
            ValueRow("Password", if (current.hasPassword) "Saved" else "Not set") { editing = Field.PASSWORD }
            RowDivider()
            ValueRow("Port", current.port.toString()) { editing = Field.PORT }
        }
        if (!current.hasCredentials) {
            MutedText("Enter your HiveMQ username and password to connect.", Modifier.padding(top = 10.dp))
        }
        }

        VSpace(20.dp)
        SectionHeader("Notifications")
        GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 6.dp)) {
            SwitchRow(
                "Background alerts",
                "Stay connected while the app is closed, on any network. Shows a small permanent notification.",
                alertsOn,
            ) { on ->
                settings.alertsEnabled = on
                version++
                if (on && needsNotificationPermission(context)) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    MonitorService.sync(context)
                }
            }
            RowDivider()
            SwitchRow("Device offline / back online", null, current.notifyOnline, enabled = alertsOn) {
                settings.notifyOnline = it; version++
            }
            RowDivider()
            SwitchRow("Light turned on / off", null, current.notifyLight, enabled = alertsOn) {
                settings.notifyLight = it; version++
            }
            RowDivider()
            SwitchRow("Mode changed", null, current.notifyMode, enabled = alertsOn) {
                settings.notifyMode = it; version++
            }
            RowDivider()
            ValueRow(
                "Run in background reliably",
                if (batteryExempt) "Allowed" else "Tap so Android doesn't delay alerts",
                enabled = alertsOn,
            ) { requestBatteryExemption(context) }
            RowDivider()
            ValueRow("Sound and style", "Android settings for each alert type") {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        }

        VSpace(20.dp)
        SectionHeader("About")
        GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 6.dp)) {
            ValueRow("Version", appVersion(context), onClick = null)
        }
    }

    when (editing) {
        null -> Unit
        Field.NAME -> EditDialog("Your name", me?.displayName.orEmpty(), KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            onDismiss = { editing = null }) { Cloud.setMyName(it); editing = null }
        Field.HOST -> EditDialog("Host", settings.host, plainKeyboard(KeyboardType.Uri),
            onDismiss = { editing = null }) { settings.host = it; brokerChanged(); editing = null }
        Field.USERNAME -> EditDialog("Username", settings.username, plainKeyboard(KeyboardType.Ascii),
            onDismiss = { editing = null }) { settings.username = it; brokerChanged(); editing = null }
        Field.PASSWORD -> EditDialog("Password", settings.password, plainKeyboard(KeyboardType.Password),
            secret = true, onDismiss = { editing = null }) { settings.password = it; brokerChanged(); editing = null }
        Field.PORT -> EditDialog("Port", settings.tlsPort.toString(), plainKeyboard(KeyboardType.Number),
            onDismiss = { editing = null }) {
            it.trim().toIntOrNull()?.takeIf { p -> p in 1..65535 }?.let { p -> settings.tlsPort = p; brokerChanged() }
            editing = null
        }
    }
}

private data class Snapshot(
    val name: String,
    val host: String,
    val username: String,
    val hasPassword: Boolean,
    val port: Int,
    val hasCredentials: Boolean,
    val alertsOn: Boolean,
    val notifyOnline: Boolean,
    val notifyLight: Boolean,
    val notifyMode: Boolean,
) {
    constructor(s: AppSettings) : this(
        s.name, s.host, s.username, s.password.isNotEmpty(), s.tlsPort, s.hasCredentials,
        s.alertsEnabled, s.notifyOnline, s.notifyLight, s.notifyMode,
    )
}

/** Usernames and passwords are case-sensitive: no capitalisation or autocorrect. */
private fun plainKeyboard(type: KeyboardType) = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = type,
)

@Composable
private fun ValueRow(title: String, value: String, enabled: Boolean = true, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ocean.Text)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = Ocean.TextMuted, maxLines = 2)
        }
        if (onClick != null) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Ocean.TextFaint)
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ocean.Text)
            if (subtitle != null) MutedText(subtitle)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Ocean.Blue,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Ocean.Deep,
            ),
        )
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Ocean.GlassEdgeFaint)

@Composable
private fun EditDialog(
    title: String,
    initial: String,
    keyboard: KeyboardOptions,
    secret: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ocean.Deep,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = keyboard,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

private fun isBatteryExempt(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

@SuppressLint("BatteryLife")   // sideloaded app; this is the point of the setting
private fun requestBatteryExemption(context: Context) {
    if (isBatteryExempt(context)) return
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
    )
}

private fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
} catch (e: PackageManager.NameNotFoundException) {
    "?"
}
