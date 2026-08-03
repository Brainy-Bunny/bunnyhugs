package com.ssbmedia.twogether.ui.nav

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Home : Screen("home")
    data object Camera : Screen("camera")
    data object Moments : Screen("moments")
    data object Stats : Screen("stats")

    /**
     * [route] stays the plain base path ("calendar") so simple call sites (bottom nav, "Days together"
     * card, etc) can keep navigating with just `navController.navigate(Screen.Calendar.route)` - the
     * three query args below are all optional (see [routePattern]'s composable registration), so Nav
     * Compose resolves the omitted-query "calendar" navigation against their default (`-1` = "none")
     * values with no extra ceremony at those call sites.
     *
     * The three drill-down cases from Feature 1 (streak highlighting, jump-to-date) use [withArgs]
     * instead: [jumpToEpochDay] opens Calendar on that date's month with that day's detail dialog
     * pre-opened; [highlightStartEpochDay]/[highlightEndEpochDay] additionally paint a distinct
     * highlight over an inclusive date range (e.g. "this is the streak being shown").
     */
    data object Calendar : Screen("calendar") {
        const val routePattern =
            "calendar?jumpToEpochDay={jumpToEpochDay}&highlightStartEpochDay={highlightStartEpochDay}&highlightEndEpochDay={highlightEndEpochDay}"

        fun withArgs(
            jumpToEpochDay: Long? = null,
            highlightStartEpochDay: Long? = null,
            highlightEndEpochDay: Long? = null
        ): String {
            val params = buildList {
                jumpToEpochDay?.let { add("jumpToEpochDay=$it") }
                highlightStartEpochDay?.let { add("highlightStartEpochDay=$it") }
                highlightEndEpochDay?.let { add("highlightEndEpochDay=$it") }
            }
            return if (params.isEmpty()) route else "$route?${params.joinToString("&")}"
        }
    }

    data object DateIdeas : Screen("date_ideas")
    data object Capsules : Screen("capsules")
    data object Badges : Screen("badges")
    data object Milestones : Screen("milestones")
    data object Settings : Screen("settings")

    /** Feature 1: "Hours together" card drill-down - a horizontally-paged bar chart of hours/day. */
    data object HoursDetail : Screen("hours_detail")

    /**
     * Feature 1: shared drill-down for BOTH "Most met month" and "Most hours month" cards - a swipeable
     * monthly bar chart with a days/hours toggle, pre-selected per [metric] ("days" or "hours") so each
     * card opens the same screen already on the metric it represents.
     */
    data object MonthlyDetail : Screen("monthly_detail/{metric}") {
        fun withMetric(metric: String) = "monthly_detail/$metric"
    }

    /** Feature 1: "Favorite day" card drill-down - Mon-Sun bar chart of distinct meetup-day counts. */
    data object FavoriteDayDetail : Screen("favorite_day_detail")

    /** Feature 1: "Longest apart" / "Avg. days between meetups" card drill-down - a sorted gap timeline. */
    data object GapsDetail : Screen("gaps_detail")
}
