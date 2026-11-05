package com.solarlamp.aquariumlight

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.SetMeal
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.solarlamp.aquariumlight.data.DeviceRepository
import com.solarlamp.aquariumlight.ui.components.FloatingNavBar
import com.solarlamp.aquariumlight.ui.components.NavItem
import com.solarlamp.aquariumlight.data.Access
import com.solarlamp.aquariumlight.data.Cloud
import com.solarlamp.aquariumlight.ui.screens.AccessGate
import com.solarlamp.aquariumlight.ui.screens.CareScreen
import com.solarlamp.aquariumlight.ui.screens.FeedingScreen
import com.solarlamp.aquariumlight.ui.screens.HistoryScreen
import com.solarlamp.aquariumlight.ui.screens.MembersScreen
import com.solarlamp.aquariumlight.ui.screens.TankScreen
import com.solarlamp.aquariumlight.ui.screens.HomeScreen
import com.solarlamp.aquariumlight.ui.screens.LightScreen
import com.solarlamp.aquariumlight.ui.screens.SettingsScreen
import com.solarlamp.aquariumlight.ui.theme.AquariumTheme
import com.solarlamp.aquariumlight.ui.theme.Ocean
import com.solarlamp.aquariumlight.ui.theme.oceanBackground

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Ocean.Abyss.copy(alpha = 0f).toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Ocean.Abyss.copy(alpha = 0f).toArgb()),
        )
        super.onCreate(savedInstanceState)

        setContent {
            AquariumTheme {
                AppRoot()
            }
        }

        MonitorService.sync(this)
    }

    // The live connection only runs while the app is visible.
    override fun onStart() {
        super.onStart()
        DeviceRepository.start(this)
    }

    override fun onStop() {
        DeviceRepository.stop()
        super.onStop()
    }
}

private object Routes {
    const val HOME = "home"
    const val LIGHT = "light"
    const val FEEDING = "feeding"
    const val CARE = "care"
    const val TANK = "tank"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val MEMBERS = "members"
}

private val tabs = listOf(
    NavItem(Routes.HOME, "Home", Icons.Rounded.Home),
    NavItem(Routes.LIGHT, "Light", Icons.Rounded.Lightbulb),
    NavItem(Routes.FEEDING, "Feeding", Icons.Rounded.SetMeal),
    NavItem(Routes.CARE, "Care", Icons.Rounded.CleaningServices),
    NavItem(Routes.TANK, "Fish & plants", Icons.Rounded.Spa),
)

@Composable
private fun AppRoot() {
    val access by Cloud.access.collectAsState()
    val context = LocalContext.current

    Box(Modifier.fillMaxSize().oceanBackground()) {
        when (val a = access) {
            is Access.Approved -> ApprovedApp(isAdmin = a.member.isAdmin)
            else -> Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) { AccessGate(a) }
        }
    }

    // Once approved: refresh last-active, sync the broker login, keep reminders current.
    val approved = access is Access.Approved
    LaunchedEffect(approved) {
        if (!approved) return@LaunchedEffect
        Cloud.onAppOpened(context)
        Reminders.rescheduleAll(context)
    }
    LaunchedEffect(approved) {
        if (!approved) return@LaunchedEffect
        Cloud.feedingTimesFlow().collect { Reminders.updateFeedingTimes(context, it) }
    }
}

@Composable
private fun ApprovedApp(isAdmin: Boolean) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val context = LocalContext.current

    // Reminders and alerts need notification permission (Android 13+). Ask once.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Admin's first run on this phone: go set up the broker login.
        if (isAdmin && !AppSettings(context).hasCredentials) nav.navigate(Routes.SETTINGS)
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenHistory = { nav.navigate(Routes.HISTORY) },
                    onOpenLight = { nav.switchTab(Routes.LIGHT) },
                    onOpen = { nav.switchTab(it) },
                )
            }
            composable(Routes.LIGHT) { LightScreen(isAdmin) }
            composable(Routes.FEEDING) { FeedingScreen() }
            composable(Routes.CARE) { CareScreen() }
            composable(Routes.TANK) { TankScreen() }
            composable(Routes.HISTORY) { HistoryScreen(onBack = { nav.popBackStack() }, isAdmin = isAdmin) }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { if (!nav.popBackStack()) nav.switchTab(Routes.HOME) },
                    onOpenMembers = { nav.navigate(Routes.MEMBERS) },
                )
            }
            composable(Routes.MEMBERS) { MembersScreen(onBack = { nav.popBackStack() }) }
        }

        if (route in tabs.map { it.route }) {
            FloatingNavBar(
                items = tabs,
                current = route,
                onSelect = { nav.switchTab(it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            )
        }
    }
}

/** Bottom-bar navigation: one copy of each tab, state kept when switching. */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
