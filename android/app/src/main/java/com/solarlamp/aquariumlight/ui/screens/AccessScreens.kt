package com.solarlamp.aquariumlight.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.solarlamp.aquariumlight.data.Access
import com.solarlamp.aquariumlight.data.Cloud
import com.solarlamp.aquariumlight.data.Member
import com.solarlamp.aquariumlight.data.MemberStatus
import com.solarlamp.aquariumlight.ui.components.FishIllustration
import com.solarlamp.aquariumlight.ui.components.GlassCard
import com.solarlamp.aquariumlight.ui.components.IconBadge
import com.solarlamp.aquariumlight.ui.components.MutedText
import com.solarlamp.aquariumlight.ui.components.PillButton
import com.solarlamp.aquariumlight.ui.components.PillStyle
import com.solarlamp.aquariumlight.ui.components.SectionHeader
import com.solarlamp.aquariumlight.ui.components.StatusChip
import com.solarlamp.aquariumlight.ui.components.VSpace
import com.solarlamp.aquariumlight.ui.theme.Ocean
import kotlinx.coroutines.launch

/** Everything before someone is approved: loading, sign-in, waiting, declined. */
@Composable
fun AccessGate(access: Access) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        FishIllustration(Modifier.fillMaxWidth().height(260.dp))
        VSpace(12.dp)

        when (access) {
            Access.Loading -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = Ocean.Sky)
                }
            }

            Access.SignedOut -> {
                Text("Your aquarium,", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light), color = Ocean.Text)
                Text("together.", style = MaterialTheme.typography.displaySmall, color = Ocean.Text)
                VSpace(12.dp)
                MutedText("Control the light, share feeding and maintenance, and see everything the family does, from anywhere.")
                VSpace(28.dp)
                PillButton(
                    text = if (busy) "Signing in…" else "Sign in with Google",
                    onClick = {
                        val activity = context as? Activity ?: return@PillButton
                        busy = true
                        error = null
                        scope.launch {
                            error = Cloud.signIn(activity)
                            busy = false
                        }
                    },
                    style = PillStyle.PRIMARY,
                    icon = Icons.AutoMirrored.Rounded.Login,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = Ocean.Coral, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                }
            }

            is Access.Waiting -> {
                GlassCard(Modifier.fillMaxWidth(), glow = Ocean.Blue) {
                    IconBadge(Icons.Rounded.HourglassTop)
                    VSpace(14.dp)
                    Text("Waiting for approval", style = MaterialTheme.typography.headlineSmall, color = Ocean.Text)
                    VSpace(6.dp)
                    MutedText("Signed in as ${access.email}. The admin has been asked to approve you. This screen opens up as soon as they do.")
                }
                VSpace(20.dp)
                SignOutButton()
            }

            is Access.Declined -> {
                GlassCard(Modifier.fillMaxWidth()) {
                    IconBadge(Icons.Rounded.Close, Ocean.Coral)
                    VSpace(14.dp)
                    Text("No access", style = MaterialTheme.typography.headlineSmall, color = Ocean.Text)
                    VSpace(6.dp)
                    MutedText("${access.email} doesn't have access to this aquarium. Ask the admin if you think this is a mistake.")
                }
                VSpace(20.dp)
                SignOutButton()
            }

            is Access.Approved -> Unit
        }
    }
}

@Composable
fun SignOutButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    PillButton(
        text = "Sign out",
        onClick = { scope.launch { Cloud.signOut(context) } },
        icon = Icons.AutoMirrored.Rounded.Logout,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Admin: approve requests and see who has access. */
@Composable
fun MembersScreen(onBack: () -> Unit) {
    val members by remember { Cloud.membersFlow() }.collectAsState(initial = null)
    var removing by remember { mutableStateOf<Member?>(null) }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding),
    ) {
        ScreenTitle("Members", onBack = onBack)

        val list = members
        if (list == null) {
            EmptyNote("Loading…")
            return@Column
        }

        val pending = list.filter { it.status == MemberStatus.PENDING }
        val approved = list.filter { it.status == MemberStatus.APPROVED }
        val others = list.filter { it.status == MemberStatus.DECLINED || it.status == MemberStatus.REMOVED }

        VSpace(12.dp)
        SectionHeader("Requests")
        if (pending.isEmpty()) {
            MutedText("No one is waiting. When someone installs the app and signs in, they appear here.")
        }
        for (m in pending) {
            GlassCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), glow = Ocean.Blue) {
                MemberHeader(m)
                VSpace(14.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Approve", { Cloud.setMemberStatus(m, MemberStatus.APPROVED) },
                        style = PillStyle.PRIMARY, icon = Icons.Rounded.Check, modifier = Modifier.weight(1f))
                    PillButton("Decline", { Cloud.setMemberStatus(m, MemberStatus.DECLINED) },
                        style = PillStyle.DANGER, icon = Icons.Rounded.Close, modifier = Modifier.weight(1f))
                }
            }
        }

        VSpace(20.dp)
        SectionHeader("Has access")
        for (m in approved) {
            GlassCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                MemberHeader(m)
                if (!m.isAdmin) {
                    VSpace(12.dp)
                    PillButton("Remove access", { removing = m }, style = PillStyle.DANGER, icon = Icons.Rounded.PersonRemove)
                }
            }
        }

        if (others.isNotEmpty()) {
            VSpace(20.dp)
            SectionHeader("Declined or removed")
            for (m in others) {
                GlassCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    MemberHeader(m)
                    VSpace(12.dp)
                    PillButton("Give access", { Cloud.setMemberStatus(m, MemberStatus.APPROVED) }, icon = Icons.Rounded.Check)
                }
            }
        }
    }

    removing?.let { m ->
        ConfirmDialog(
            title = "Remove ${m.displayName}?",
            text = "${m.email} will lose access straight away. You can give it back later.",
            confirmText = "Remove",
            danger = true,
            onDismiss = { removing = null },
        ) {
            Cloud.setMemberStatus(m, MemberStatus.REMOVED)
            removing = null
        }
    }
}

@Composable
private fun MemberHeader(m: Member) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(m.displayName, style = MaterialTheme.typography.titleLarge, color = Ocean.Text)
            MutedText(m.email)
            Spacer(Modifier.size(2.dp))
            Text(
                when {
                    m.status == MemberStatus.PENDING -> m.requestedAt?.let { "Asked ${relativeTime(it)}" } ?: "Asked just now"
                    m.lastSeen != null -> "Last active ${relativeTime(m.lastSeen)}"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Ocean.TextFaint,
            )
        }
        if (m.isAdmin) StatusChip("Admin", Ocean.Amber)
    }
}
