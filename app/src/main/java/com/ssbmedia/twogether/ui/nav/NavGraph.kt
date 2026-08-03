package com.ssbmedia.twogether.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ssbmedia.twogether.ui.badges.BadgesScreen
import com.ssbmedia.twogether.ui.calendar.CalendarScreen
import com.ssbmedia.twogether.ui.camera.CameraScreen
import com.ssbmedia.twogether.ui.capsules.CapsulesScreen
import com.ssbmedia.twogether.ui.dateideas.DateIdeasScreen
import com.ssbmedia.twogether.ui.home.HomeScreen
import com.ssbmedia.twogether.ui.milestones.MilestonesScreen
import com.ssbmedia.twogether.ui.moments.MomentsScreen
import com.ssbmedia.twogether.ui.settings.SettingsScreen
import com.ssbmedia.twogether.ui.stats.StatsScreen

private data class BottomItem(val screen: Screen, val emoji: String, val label: String)

private val bottomItems = listOf(
    BottomItem(Screen.Home, "🏠", "Home"),
    BottomItem(Screen.Calendar, "📅", "Calendar"),
    BottomItem(Screen.DateIdeas, "💌", "Ideas"),
    BottomItem(Screen.Moments, "📸", "Moments"),
    BottomItem(Screen.Stats, "📊", "Stats")
)

@Composable
fun TwogetherNavHost(cameraTrigger: Int, onUnpaired: () -> Unit, openMilestoneId: String? = null) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(cameraTrigger) {
        if (cameraTrigger > 0) {
            navController.navigate(Screen.Camera.route) { launchSingleTop = true }
        }
    }

    // Feature F: tapping a milestone's yearly notification opens the app straight into the Milestones
    // screen with that milestone's retrospective pre-opened (see MilestonesScreen's initialMilestoneId).
    LaunchedEffect(openMilestoneId) {
        if (openMilestoneId != null) {
            navController.navigate(Screen.Milestones.route) { launchSingleTop = true }
        }
    }

    Scaffold(
        bottomBar = {
            if (bottomItems.any { it.screen.route == currentRoute }) {
                NavigationBar {
                    bottomItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.screen.route,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(Screen.Home.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Text(item.emoji) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = androidx.compose.ui.Modifier.padding(padding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateCalendar = { navController.navigate(Screen.Calendar.route) },
                    onNavigateDateIdeas = { navController.navigate(Screen.DateIdeas.route) },
                    onNavigateMoments = { navController.navigate(Screen.Moments.route) },
                    onNavigateStats = { navController.navigate(Screen.Stats.route) },
                    onNavigateCapsules = { navController.navigate(Screen.Capsules.route) },
                    onNavigateBadges = { navController.navigate(Screen.Badges.route) },
                    onNavigateCamera = { navController.navigate(Screen.Camera.route) },
                    onNavigateMilestones = { navController.navigate(Screen.Milestones.route) }
                )
            }
            composable(Screen.Calendar.route) { CalendarScreen(onBack = { navController.popBackStack() }) }
            composable(Screen.DateIdeas.route) { DateIdeasScreen(onBack = { navController.popBackStack() }) }
            composable(Screen.Moments.route) { MomentsScreen(onBack = { navController.popBackStack() }) }
            composable(Screen.Stats.route) { StatsScreen(onBack = { navController.popBackStack() }, onOpenBadges = { navController.navigate(Screen.Badges.route) }) }
            composable(Screen.Capsules.route) { CapsulesScreen(onBack = { navController.popBackStack() }) }
            composable(Screen.Badges.route) { BadgesScreen(onBack = { navController.popBackStack() }) }
            composable(Screen.Milestones.route) {
                MilestonesScreen(onBack = { navController.popBackStack() }, initialMilestoneId = openMilestoneId)
            }
            composable(Screen.Settings.route) { SettingsScreen(onBack = { navController.popBackStack() }, onUnpaired = onUnpaired) }
            composable(Screen.Camera.route) {
                CameraScreen(
                    onSaved = { navController.popBackStack(Screen.Home.route, false) },
                    onCancel = { navController.popBackStack() }
                )
            }
        }
    }
}
