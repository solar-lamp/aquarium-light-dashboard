package com.solarlamp.aquariumlight.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.solarlamp.aquariumlight.data.DeviceState
import com.solarlamp.aquariumlight.data.Link
import com.solarlamp.aquariumlight.ui.components.GlassIconButton
import com.solarlamp.aquariumlight.ui.components.StatusChip
import com.solarlamp.aquariumlight.ui.theme.Ocean
import kotlinx.coroutines.delay

/** Space for the floating nav bar so content can scroll clear of it. */
val ScreenPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp)

/** Wall-clock millis, updated every second, for "is it stale?" checks. */
@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(1000)
        value = System.currentTimeMillis()
    }
}

@Composable
fun ConnectionChip(state: DeviceState, now: Long, modifier: Modifier = Modifier) {
    val (text, color) = when {
        !state.linkOk(now) -> (if (state.isCached) "Updating…" else "Connecting") to Ocean.TextMuted
        state.deviceOnline(now) -> "Online" to Ocean.Cyan
        state.isCached && state.onlineFlag != false -> "Updating…" to Ocean.TextMuted
        state.status == null && state.onlineFlag == null -> "Waiting" to Ocean.TextMuted
        else -> "Offline" to Ocean.Coral
    }
    StatusChip(text, color, modifier)
}

@Composable
fun ScreenTitle(title: String, onBack: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, size = 48.dp)
            Spacer(Modifier.width(14.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = Ocean.Text,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

fun formatDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}
