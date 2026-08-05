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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ssbmedia.twogether.ui.badges.BadgesScreen
import com.ssbmedia.twogether.ui.battery.BatteryOptimizationGate
import com.ssbmedia.twogether.ui.calendar.CalendarScreen
import com.ssbmedia.twogether.ui.camera.CameraScreen
import com.ssbmedia.twogether.ui.capsules.CapsulesScreen
import com.ssbmedia.twogether.ui.dateideas.OurListsScreen
import com.ssbmedia.twogether.ui.home.HomeScreen
import com.ssbmedia.twogether.ui.milestones.MilestonesScreen
import com.ssbmedia.twogether.ui.moments.MomentsScreen
import com.ssbmedia.twogether.ui.settings.SettingsScreen
import com.ssbmedia.twogether.ui.stats.FavoriteDayDetailScreen
import com.ssbmedia.twogether.ui.stats.GapsDetailScreen
import com.ssbmedia.twogether.ui.stats.HoursDetailScreen
import com.ssbmedia.twogether.ui.stats.MonthlyDetailScreen
import com.ssbmedia.twogether.ui.stats.StatsScreen

private data class BottomItem(val screen: Screen, val emoji: String, val label: String)

private val bottomItems = listOf(
    BottomItem(Screen.Home, "🏠", "Home"),
    BottomItem(Screen.Calendar, "📅", "Calendar"),
    BottomItem(Screen.DateIdeas, "💌", "Our Lists"),
    BottomItem(Screen.Moments, "📸", "Moments"),
    BottomItem(Screen.Stats, "📊", "Stats")
)

@Composable
fun TwogetherNavHost(cameraTrigger: Int, onUnpaired: () -> Unit, openMilestoneId: String? = null) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    // Calendar's registered destination route is its full query-param PATTERN (see
    // Screen.Calendar.routePattern) regardless of whether the actual navigate() call included any of
    // those optional args - NavDestination.route always reflects the registered pattern, never the
    // resolved call. Stripping the "?..." here is what lets the bottom bar's plain Screen.Calendar.route
    // ("calendar") still match it for selection/visibility, exactly like every other bottom item.
    val currentRoute = backStackEntry?.destination?.route?.substringBefore("?")

    // Feature 1: covers both "first successful pairing" and "app launch while already paired" - this
    // whole NavHost only ever composes once the couple is paired, so a fresh composition of it IS
    // exactly those two moments. See BatteryOptimizationGate's doc for why re-showing on next app open
    // (rather than a one-time flag) is the intended "check again later" behavior.
    BatteryOptimizationGate()

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
                                // Feature 1 surfaced a real bug in the standard popUpTo+saveState+
                                // restoreState bottom-nav pattern: several new Stats cards now push
                                // Calendar (itself a bottom-tab root) via a plain navigate() ON TOP OF
                                // Stats, e.g. back stack [Home, Stats, Calendar]. Tapping "Stats" here
                                // used to unconditionally do
                                // navigate("stats"){popUpTo(Home,saveState=true); restoreState=true} -
                                // which pops BOTH Stats and Calendar as one saved segment and then
                                // restores that WHOLE segment (landing back on Calendar, not Stats),
                                // since Stats was the base of the very segment just saved. Verified this
                                // never bit the app before Feature 1, because no pre-existing screen
                                // pushed a bottom-tab-root on top of ANOTHER bottom-tab screen via a
                                // plain navigate - only via Home (a single-entry segment, which restores
                                // fine).
                                //
                                // Fix: if the target tab is ALREADY an ancestor in the current back
                                // stack (exactly this scenario), just pop back to that EXISTING entry -
                                // equivalent to what the on-screen back arrow already does correctly,
                                // and sidesteps the save/restore machinery entirely. Only fall back to
                                // the standard popUpTo+saveState+restoreState tab-switch when the target
                                // isn't already in the stack (the normal "switch to a sibling tab" case,
                                // which was never broken).
                                val poppedToExisting = navController.popBackStack(item.screen.route, inclusive = false)
                                if (!poppedToExisting) {
                                    navController.navigate(item.screen.route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
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
            composable(
                route = Screen.Calendar.routePattern,
                arguments = listOf(
                    navArgument("jumpToEpochDay") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("highlightStartEpochDay") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("highlightEndEpochDay") { type = NavType.LongType; defaultValue = -1L }
                )
            ) { entry ->
                val args = entry.arguments
                CalendarScreen(
                    onBack = { navController.popBackStack() },
                    jumpToEpochDay = args?.getLong("jumpToEpochDay")?.takeIf { it >= 0 },
                    highlightStartEpochDay = args?.getLong("highlightStartEpochDay")?.takeIf { it >= 0 },
                    highlightEndEpochDay = args?.getLong("highlightEndEpochDay")?.takeIf { it >= 0 }
                )
            }
            composable(Screen.DateIdeas.route) { OurListsScreen(onBack = { navController.popBackStack() }) }
            composable(Screen.Moments.route) {
                MomentsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateCamera = { navController.navigate(Screen.Camera.route) }
                )
            }
            composable(Screen.Stats.route) {
                StatsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBadges = { navController.navigate(Screen.Badges.route) },
                    onOpenHoursDetail = { navController.navigate(Screen.HoursDetail.route) },
                    onOpenCalendar = { navController.navigate(Screen.Calendar.route) },
                    onOpenMonthlyDetail = { metric -> navController.navigate(Screen.MonthlyDetail.withMetric(metric)) },
                    onOpenFavoriteDayDetail = { navController.navigate(Screen.FavoriteDayDetail.route) },
                    onOpenGapsDetail = { navController.navigate(Screen.GapsDetail.route) },
                    onOpenCalendarWithArgs = { jumpTo, highlightStart, highlightEnd ->
                        navController.navigate(Screen.Calendar.withArgs(jumpTo, highlightStart, highlightEnd))
                    }
                )
            }
            composable(Screen.HoursDetail.route) { HoursDetailScreen(onBack = { navController.popBackStack() }) }
            composable(
                route = Screen.MonthlyDetail.route,
                arguments = listOf(navArgument("metric") { type = NavType.StringType })
            ) { entry ->
                val metric = entry.arguments?.getString("metric") ?: "days"
                MonthlyDetailScreen(initialMetric = metric, onBack = { navController.popBackStack() })
            }
            composable(Screen.FavoriteDayDetail.route) { FavoriteDayDetailScreen(onBack = { navController.popBackStack() }) }
            composable(Screen.GapsDetail.route) { GapsDetailScreen(onBack = { navController.popBackStack() }) }
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
