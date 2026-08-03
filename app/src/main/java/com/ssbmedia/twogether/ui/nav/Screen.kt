package com.ssbmedia.twogether.ui.nav

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Home : Screen("home")
    data object Camera : Screen("camera")
    data object Moments : Screen("moments")
    data object Stats : Screen("stats")
    data object Calendar : Screen("calendar")
    data object DateIdeas : Screen("date_ideas")
    data object Capsules : Screen("capsules")
    data object Badges : Screen("badges")
    data object Settings : Screen("settings")
}
