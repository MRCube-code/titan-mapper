package com.titanmapper.app

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.titanmapper.feature.dashboard.DashboardRoute
import com.titanmapper.feature.devices.DevicesRoute
import com.titanmapper.feature.diagnostics.DiagnosticsRoute
import com.titanmapper.feature.macro.MacroEditorRoute
import com.titanmapper.feature.macro.MacroListRoute
import com.titanmapper.feature.mapping.MappingEditorRoute
import com.titanmapper.feature.overlay.OverlayEditorRoute
import com.titanmapper.feature.profiles.ProfilesRoute
import com.titanmapper.feature.settings.SettingsRoute
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext

/** Top-level nav destinations shown in the NavigationBar/NavigationRail. */
private enum class TopLevelRoute(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    DASHBOARD("dashboard", "Dashboard", Icons.Filled.Dashboard),
    PROFILES("profiles", "Profiles", Icons.Filled.People),
    DEVICES("devices", "Devices", Icons.Filled.Devices),
    MACROS("macros", "Macros", Icons.Filled.Layers),
    SETTINGS("settings", "Settings", Icons.Filled.Settings)
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun TitanNavHost(startRoute: String? = null, startRouteNonce: Int = 0) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val windowSizeClass = calculateWindowSizeClass(LocalContext.current as android.app.Activity)
    val useRail = windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Medium

    val topRoutes = TopLevelRoute.entries
    val isTopLevel = topRoutes.any { tl -> currentDestination?.hierarchy?.any { it.route == tl.route } == true }

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Honors a start route requested by another entry point (currently only the floating overlay
    // menu's Home/Settings/hex buttons, see MainActivity.EXTRA_START_ROUTE); keyed on the nonce
    // (not just the route string) so tapping the same hex button twice in a row still re-navigates
    // even though the route string didn't change.
    LaunchedEffect(startRouteNonce) {
        startRoute?.let { navigateTo(it) }
    }

    if (useRail) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail {
                topRoutes.forEach { dest ->
                    NavigationRailItem(
                        selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true,
                        onClick = { navigateTo(dest.route) },
                        icon = { Icon(dest.icon, dest.label) },
                        label = { Text(dest.label) }
                    )
                }
            }
            NavGraph(navController = navController, modifier = Modifier.weight(1f))
        }
    } else {
        Scaffold(
            bottomBar = {
                if (isTopLevel) {
                    NavigationBar {
                        topRoutes.forEach { dest ->
                            NavigationBarItem(
                                selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true,
                                onClick = { navigateTo(dest.route) },
                                icon = { Icon(dest.icon, dest.label) },
                                label = { Text(dest.label) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            NavGraph(navController = navController, modifier = Modifier.padding(innerPadding))
        }
    }
}

@Composable
private fun NavGraph(navController: androidx.navigation.NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController = navController, startDestination = TopLevelRoute.DASHBOARD.route, modifier = modifier) {
        composable(TopLevelRoute.DASHBOARD.route) {
            DashboardRoute(
                onOpenDevices = {
                    navController.navigate(TopLevelRoute.DEVICES.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onOpenProfiles = {
                    navController.navigate(TopLevelRoute.PROFILES.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onOpenDiagnostics = { navController.navigate("diagnostics") },
                onOpenSettings = {
                    navController.navigate(TopLevelRoute.SETTINGS.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
        composable(TopLevelRoute.PROFILES.route) {
            ProfilesRoute(
                onEditProfile = { profileId -> navController.navigate("mapping/$profileId") }
            )
        }
        composable(TopLevelRoute.DEVICES.route) {
            DevicesRoute()
        }
        composable(TopLevelRoute.MACROS.route) {
            MacroListRoute(onEditMacro = { macroId -> navController.navigate("macro/$macroId") })
        }
        composable(TopLevelRoute.SETTINGS.route) {
            SettingsRoute()
        }
        composable(
            route = "mapping/{profileId}",
            arguments = listOf(navArgument("profileId") { type = NavType.StringType })
        ) { backStackEntry ->
            val profileId = backStackEntry.arguments?.getString("profileId") ?: return@composable
            MappingEditorRoute(
                profileId = profileId,
                onBack = { navController.popBackStack() },
                onEditOverlay = { navController.navigate("overlay/$profileId") }
            )
        }
        composable(
            route = "overlay/{profileId}",
            arguments = listOf(navArgument("profileId") { type = NavType.StringType })
        ) {
            OverlayEditorRoute(
                profileId = it.arguments?.getString("profileId") ?: return@composable,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = "macro/{macroId}",
            arguments = listOf(navArgument("macroId") { type = NavType.StringType })
        ) {
            MacroEditorRoute(
                macroId = it.arguments?.getString("macroId") ?: return@composable,
                onBack = { navController.popBackStack() }
            )
        }
        composable("diagnostics") {
            DiagnosticsRoute()
        }
    }
}
