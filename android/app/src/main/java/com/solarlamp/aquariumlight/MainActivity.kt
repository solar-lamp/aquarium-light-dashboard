package com.solarlamp.aquariumlight

import android.os.Bundle
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
import androidx.compose.material.icons.rounded.History
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
import com.solarlamp.aquariumlight.ui.screens.ComingSoonScreen
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
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val context = LocalContext.current

    // First launch: go straight to Settings to enter the broker login.
    LaunchedEffect(Unit) {
        if (!AppSettings(context).hasCredentials) nav.navigate(Routes.SETTINGS)
    }

    Box(Modifier.fillMaxSize().oceanBackground()) {
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
            composable(Routes.LIGHT) { LightScreen() }
            composable(Routes.FEEDING) {
                ComingSoonScreen(
                    title = "Feeding",
                    icon = Icons.Rounded.SetMeal,
                    summary = "Never feed twice, never forget.",
                    features = listOf(
                        "Daily feeding times with reminders on the phones you choose",
                        "One tap \"Fed\": everyone's reminder clears at once",
                        "Feeding log with who fed, when, and an optional note",
                        "Ready for an automatic feeder later",
                    ),
                )
            }
            composable(Routes.CARE) {
                ComingSoonScreen(
                    title = "Care",
                    icon = Icons.Rounded.CleaningServices,
                    summary = "Maintenance and water tests in one place.",
                    features = listOf(
                        "Water change, glass clean and full tank clean, with due-date reminders",
                        "Each filter as its own slot, with when it was last cleaned and by whom",
                        "Water test log: pH, ammonia, nitrite, nitrate, temperature",
                        "Charts to spot trends early",
                    ),
                )
            }
            composable(Routes.TANK) {
                ComingSoonScreen(
                    title = "Fish & plants",
                    icon = Icons.Rounded.Spa,
                    summary = "Everything living in the tank.",
                    features = listOf(
                        "Each species with its name and count",
                        "Date added and notes",
                        "Additions and losses recorded in history",
                    ),
                )
            }
            composable(Routes.HISTORY) {
                ComingSoonScreen(
                    title = "History",
                    icon = Icons.Rounded.History,
                    summary = "Every change, by everyone.",
                    features = listOf(
                        "Light changes, feedings, maintenance and tank changes in one feed",
                        "Who did it and when, from any phone",
                        "Filter by type or by person",
                    ),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { if (!nav.popBackStack()) nav.switchTab(Routes.HOME) })
            }
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
