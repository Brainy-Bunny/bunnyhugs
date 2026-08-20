package com.ssbmedia.twogether.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
fun TwogetherNavHost(
    cameraTrigger: Int,
    onUnpaired: () -> Unit,
    openMilestoneId: String? = null,
    // Item 24 (UX-FIX-PLAN.md): tap-through target for a list/list-item reminder notification - see
    // Notifications.EXTRA_OPEN_LIST_ID's own doc. Mirrors openMilestoneId exactly, including the
    // latch-then-navigate-then-consume shape below.
    openListId: String? = null,
    onCameraTriggerConsumed: () -> Unit = {},
    onMilestoneIdConsumed: () -> Unit = {},
    // Item 16 (camera overhaul), point 5: MainActivity's dispatchKeyEvent is a plain Activity method, not
    // a composable, so it can't read NavController state directly - it needs a plain boolean flag telling
    // it whether volume-key presses should be routed to the camera shutter (see AppEvents.
    // cameraShutterRequests) right now, versus behaving as completely normal volume keys everywhere else
    // in the app. This callback is how that flag gets kept in sync with the actual current destination.
    onCameraScreenActiveChanged: (Boolean) -> Unit = {},
    onListIdConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    // Calendar's registered destination route is its full query-param PATTERN (see
    // Screen.Calendar.routePattern) regardless of whether the actual navigate() call included any of
    // those optional args - NavDestination.route always reflects the registered pattern, never the
    // resolved call. Stripping the "?..." here is what lets the bottom bar's plain Screen.Calendar.route
    // ("calendar") still match it for selection/visibility, exactly like every other bottom item.
    val currentRoute = backStackEntry?.destination?.route?.substringBefore("?")

    LaunchedEffect(currentRoute) {
        onCameraScreenActiveChanged(currentRoute == Screen.Camera.route)
    }
    // Safety net: if this WHOLE NavHost is torn down while Camera happened to be the active destination
    // (e.g. MainActivity's PIN-lock branch swap, or a config change tearing down and recreating this
    // composable), make sure the flag doesn't get stuck true with no LaunchedEffect left alive to ever
    // flip it back - that would silently route ALL future volume-key presses to a now-gone camera
    // shutter, everywhere else in the app, until the next time Camera happens to be visited again.
    DisposableEffect(Unit) {
        onDispose { onCameraScreenActiveChanged(false) }
    }

    // Feature 1: covers both "first successful pairing" and "app launch while already paired" - this
    // whole NavHost only ever composes once the couple is paired, so a fresh composition of it IS
    // exactly those two moments. See BatteryOptimizationGate's doc for why re-showing on next app open
    // (rather than a one-time flag) is the intended "check again later" behavior.
    BatteryOptimizationGate()

    // BUG fix: an independent audit round found neither trigger was ever cleared at its source
    // (MainActivity's cameraTrigger/milestoneTrigger) after being consumed here - so if this WHOLE
    // NavHost was ever torn down and recomposed fresh (e.g. the PIN-lock branch swap in MainActivity's
    // own `when`, or a config change), a brand-new LaunchedEffect instance would see the same
    // still-non-null/still-positive value as if it were a genuinely new notification tap, silently
    // re-navigating to Camera or re-opening an old milestone's retrospective with no user action at all.
    // Calling back up to clear the source value the moment it's consumed means any FUTURE non-null value
    // is guaranteed to be a real new tap, not a stale leftover surviving recomposition.
    LaunchedEffect(cameraTrigger) {
        if (cameraTrigger > 0) {
            navController.navigate(Screen.Camera.route) { launchSingleTop = true }
            onCameraTriggerConsumed()
        }
    }

    // Feature F: tapping a milestone's yearly notification opens the app straight into the Milestones
    // screen with that milestone's retrospective pre-opened (see MilestonesScreen's initialMilestoneId).
    //
    // BUG fix: an independent review round caught that the cameraTrigger fix's exact pattern (clear the
    // source in the SAME LaunchedEffect that calls navigate()) breaks THIS trigger specifically, and
    // live-reproduced it: navigate() only schedules the Milestones destination to compose on a LATER
    // recomposition, not synchronously - so by the time MilestonesScreen actually composes and reads
    // openMilestoneId (passed straight through as its initialMilestoneId param), onMilestoneIdConsumed()
    // had already nulled the source, and the retrospective never opened at all. Latching the id into
    // this LOCAL remembered value FIRST, and passing THAT (not the live openMilestoneId param) down to
    // MilestonesScreen below, means clearing the upstream source immediately afterward is now safe -
    // MilestonesScreen no longer depends on openMilestoneId staying non-null past this point.
    // BUG fix: was plain `remember` - a second independent review round found that rotating the device
    // WHILE the notification-opened retrospective was showing silently dropped it, since the nav back
    // stack itself is restored (rememberNavController's own state is saveable) but this latch wasn't,
    // so MilestonesScreen recomposed with initialMilestoneId already null. rememberSaveable (a plain
    // String survives a Bundle natively, no custom Saver needed) fixes that.
    var latchedMilestoneId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openMilestoneId) {
        if (openMilestoneId != null) {
            latchedMilestoneId = openMilestoneId
            navController.navigate(Screen.Milestones.route) { launchSingleTop = true }
            onMilestoneIdConsumed()
        }
    }

    // Item 24 (UX-FIX-PLAN.md): tapping a list/list-item reminder notification opens "Our Lists" with
    // that list auto-expanded (see OurListsScreen's initialExpandListId param) - exact same
    // latch-then-navigate-then-consume shape as the milestone trigger just above, for the exact same
    // reason (navigate() only schedules the destination to compose later, so the source must stay
    // readable until OurListsScreen actually composes and reads it).
    var latchedListId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openListId) {
        if (openListId != null) {
            latchedListId = openListId
            navController.navigate(Screen.DateIdeas.route) { launchSingleTop = true }
            onListIdConsumed()
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
                    onNavigateMilestones = { navController.navigate(Screen.Milestones.route) },
                    // UX-FIX-PLAN.md Phase 3 item 20: Home's throwback/on-this-day cards -> the actual
                    // Moment/date, same Calendar.withArgs/Moments.withArgs deep-link pattern Stats already
                    // uses for its own drill-down cards.
                    onOpenCalendar = { day -> navController.navigate(Screen.Calendar.withArgs(jumpToEpochDay = day)) },
                    onOpenMoments = { day -> navController.navigate(Screen.Moments.withArgs(jumpToEpochDay = day)) }
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
                    highlightEndEpochDay = args?.getLong("highlightEndEpochDay")?.takeIf { it >= 0 },
                    onOpenMoments = { day -> navController.navigate(Screen.Moments.withArgs(jumpToEpochDay = day)) }
                )
            }
            composable(Screen.DateIdeas.route) {
                OurListsScreen(onBack = { navController.popBackStack() }, initialExpandListId = latchedListId)
            }
            composable(
                route = Screen.Moments.routePattern,
                arguments = listOf(navArgument("jumpToEpochDay") { type = NavType.LongType; defaultValue = -1L })
            ) { entry ->
                MomentsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateCamera = { navController.navigate(Screen.Camera.route) },
                    jumpToEpochDay = entry.arguments?.getLong("jumpToEpochDay")?.takeIf { it >= 0 },
                    onOpenCalendar = { day -> navController.navigate(Screen.Calendar.withArgs(jumpToEpochDay = day)) }
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
            composable(Screen.Capsules.route) {
                CapsulesScreen(
                    onBack = { navController.popBackStack() },
                    // UX-FIX-PLAN.md Phase 3 item 20: Time Capsule -> the day it unlocked.
                    onOpenCalendar = { day -> navController.navigate(Screen.Calendar.withArgs(jumpToEpochDay = day)) }
                )
            }
            composable(Screen.Badges.route) {
                BadgesScreen(
                    onBack = { navController.popBackStack() },
                    // UX-FIX-PLAN.md Phase 3 item 20: Badge -> the stat that earned it - same
                    // onOpenHoursDetail/onOpenGapsDetail/onOpenCalendarWithArgs pattern StatsScreen's own
                    // drill-down cards already use.
                    onOpenHoursDetail = { navController.navigate(Screen.HoursDetail.route) },
                    onOpenGapsDetail = { navController.navigate(Screen.GapsDetail.route) },
                    onOpenCalendarWithArgs = { jumpTo, highlightStart, highlightEnd ->
                        navController.navigate(Screen.Calendar.withArgs(jumpTo, highlightStart, highlightEnd))
                    }
                )
            }
            composable(Screen.Milestones.route) {
                MilestonesScreen(
                    onBack = { navController.popBackStack() },
                    initialMilestoneId = latchedMilestoneId,
                    // BUG fix: see MilestonesScreen's own doc - without this, latchedMilestoneId stayed
                    // set forever (this local `remember` only resets on a full NavHost teardown), so
                    // every later Milestones visit kept reopening the same notification's retrospective.
                    onInitialMilestoneConsumed = { latchedMilestoneId = null },
                    // UX-FIX-PLAN.md Phase 3 item 20: Milestone -> Calendar date.
                    onOpenCalendar = { day -> navController.navigate(Screen.Calendar.withArgs(jumpToEpochDay = day)) }
                )
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
