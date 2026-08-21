package com.ssbmedia.twogether.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.stats.DateRange
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.ui.components.SectionHeader
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import com.ssbmedia.twogether.ui.components.StatCard
import com.ssbmedia.twogether.util.DateFormats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.time.format.TextStyle
import java.util.Locale

class StatsViewModel : ViewModel() {
    val sessions = ServiceLocator.sessionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val proximityState = ServiceLocator.proximityStateStore.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.ssbmedia.twogether.data.datastore.ProximityPersistedState())
}

/**
 * Feature 1: every stat card below that's meaningfully drill-down-able now takes an onClick that routes
 * to a dedicated detail screen (wired from NavGraph). Two cards ("Days together", "Longest single day",
 * "Together since") route to the existing Calendar screen instead of a new screen - either plain, or
 * jumped/highlighted via [onOpenCalendarWithArgs] - see [Screen.Calendar]'s doc for why that's a single
 * shared destination rather than three near-duplicate ones.
 */
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    onOpenBadges: () -> Unit,
    onOpenHoursDetail: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenMonthlyDetail: (metric: String) -> Unit,
    onOpenFavoriteDayDetail: () -> Unit,
    onOpenGapsDetail: () -> Unit,
    onOpenCalendarWithArgs: (jumpToEpochDay: Long?, highlightStartEpochDay: Long?, highlightEndEpochDay: Long?) -> Unit
) {
    val vm: StatsViewModel = viewModel(factory = SimpleViewModelFactory { StatsViewModel() })
    val sessions by vm.sessions.collectAsState()
    val proximityState by vm.proximityState.collectAsState()
    // lastSeenAt clamps an open session's live duration to the last confirmed sighting + absence
    // timeout - see StatsCalculator.effectiveOpenSessionEnd's doc.
    val stats = remember(sessions, proximityState.lastSeenAt, proximityState.reunionCount) {
        StatsCalculator.compute(sessions, lastSeenAt = proximityState.lastSeenAt, reunionCount = proximityState.reunionCount)
    }
    // Feature 1: the actual calendar-date span of the longest daily/weekly streak, so "Longest streak"
    // cards can jump Calendar there and highlight it - see StatsCalculator's doc for why this is a
    // separate call from stats.longestDailyStreak/longestWeeklyStreak (which only expose the length).
    val longestDailyStreakRange: DateRange? = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.longestDailyStreakRange(sessions, lastSeenAt = proximityState.lastSeenAt)
    }
    val longestWeeklyStreakRange: DateRange? = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.longestWeeklyStreakRange(sessions, lastSeenAt = proximityState.lastSeenAt)
    }
    // User-requested: "current" streak cards should be tappable too, matching "longest".
    val currentDailyStreakRange: DateRange? = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.currentDailyStreakRange(sessions, lastSeenAt = proximityState.lastSeenAt)
    }
    val currentWeeklyStreakRange: DateRange? = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.currentWeeklyStreakRange(sessions, lastSeenAt = proximityState.lastSeenAt)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Stats") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                    StatCard(
                        emoji = "💛",
                        label = "Hours together",
                        // MINOR fix (ultimate-app-review round 1, item 5): pinned to Locale.US throughout
                        // this screen - see DateFormats' own doc for why display text in this app is
                        // deliberately locale-independent; an unpinned "%.1f".format(...) would otherwise
                        // use the JVM default locale's decimal separator (e.g. "1,5" on a comma-decimal
                        // device locale).
                        value = "${"%.1f".format(Locale.US, stats.totalHoursAllTime)}h",
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        onClick = onOpenHoursDetail
                    )
                    StatCard(
                        emoji = "🗓️",
                        label = "Days together",
                        value = "${stats.totalDaysTogether}",
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        onClick = onOpenCalendar
                    )
                }
            }
            item {
                SectionHeader("Hours together")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                    StatCard(emoji = "💛", label = "All-time", value = "${"%.1f".format(Locale.US, stats.totalHoursAllTime)}h", modifier = Modifier.weight(1f))
                    StatCard(emoji = "📆", label = "This week", value = "${"%.1f".format(Locale.US, stats.totalHoursThisWeek)}h", modifier = Modifier.weight(1f))
                    StatCard(emoji = "🗓️", label = "This month", value = "${"%.1f".format(Locale.US, stats.totalHoursThisMonth)}h", modifier = Modifier.weight(1f))
                }
            }
            item {
                SectionHeader("Streaks")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                    StatCard(
                        emoji = "🔥",
                        label = "Daily streak (current)",
                        value = "${stats.currentDailyStreak}d",
                        modifier = Modifier.weight(1f),
                        onClick = currentDailyStreakRange?.let { range ->
                            { onOpenCalendarWithArgs(null, range.start.toEpochDay(), range.end.toEpochDay()) }
                        }
                    )
                    StatCard(
                        emoji = "🏆",
                        label = "Daily streak (longest)",
                        value = "${stats.longestDailyStreak}d",
                        modifier = Modifier.weight(1f),
                        onClick = longestDailyStreakRange?.let { range ->
                            { onOpenCalendarWithArgs(null, range.start.toEpochDay(), range.end.toEpochDay()) }
                        }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(top = 12.dp)) {
                    StatCard(
                        emoji = "🌟",
                        label = "Weekly streak (current)",
                        value = "${stats.currentWeeklyStreak}w",
                        modifier = Modifier.weight(1f),
                        onClick = currentWeeklyStreakRange?.let { range ->
                            { onOpenCalendarWithArgs(null, range.start.toEpochDay(), range.end.toEpochDay()) }
                        }
                    )
                    StatCard(
                        emoji = "✨",
                        label = "Weekly streak (longest)",
                        value = "${stats.longestWeeklyStreak}w",
                        modifier = Modifier.weight(1f),
                        onClick = longestWeeklyStreakRange?.let { range ->
                            { onOpenCalendarWithArgs(null, range.start.toEpochDay(), range.end.toEpochDay()) }
                        }
                    )
                }
                Text(
                    // MINOR fix (independent audit): the "current" streak cards became tappable too
                    // (see currentDailyStreakRange/currentWeeklyStreakRange), but this line still only
                    // mentioned "longest" - actively telling the user an affordance doesn't exist that
                    // now does.
                    text = "Daily streak = consecutive days together. Weekly streak is separate — consecutive weeks with any time together. Tap a streak card to see it on the calendar.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            item {
                SectionHeader("More about you two")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                    StatCard(emoji = "⏱️", label = "Longest session", value = formatMinutes(stats.longestSessionMinutes), modifier = Modifier.weight(1f))
                    StatCard(emoji = "🤗", label = "Reunions", value = "${stats.reunionCount}", modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(top = 12.dp)) {
                    StatCard(
                        emoji = "❤️",
                        label = "Favorite day",
                        value = stats.favoriteDayOfWeek?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—",
                        modifier = Modifier.weight(1f),
                        onClick = onOpenFavoriteDayDetail
                    )
                    StatCard(emoji = "📆", label = "Perfect weeks", value = "${stats.perfectWeekCount}", modifier = Modifier.weight(1f))
                }
            }
            item {
                // Feature E: more stats, all read off the same merged/deduped session timeline above -
                // see StatsCalculator's doc for exactly how each one is derived.
                SectionHeader("Your story so far")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                    StatCard(
                        emoji = "💞",
                        label = "Together since",
                        // BUG fix (user-reported): this narrative card read as a bare number ("20 08
                        // 2026") - see DateFormats.formatDateLong's own doc for why this one call site
                        // gets the human-language date while every other (much more compact) DATE call
                        // site app-wide is deliberately left on the numeric convention.
                        value = stats.togetherSince?.let { DateFormats.formatDateLong(it) } ?: "—",
                        modifier = Modifier.weight(1f),
                        // MAJOR fix (independent audit): a long month name ("20th September 2026") can't
                        // fit this half-width card's own value slot in 2 lines at default font scale on a
                        // 360dp-class phone - see StatCard's own valueMaxLines doc.
                        valueMaxLines = 3,
                        onClick = stats.togetherSince?.let { date ->
                            { onOpenCalendarWithArgs(date.toEpochDay(), null, null) }
                        }
                    )
                    StatCard(
                        // Feature 1 / Phase 1 item 5: emoji now tracks the actual day-delta being shown
                        // (not the separate hours-based Trend enum below it), so the arrow can never
                        // point a different direction than the number next to it.
                        //
                        // BUG fix (user-reported, live-verified as a labeling issue not a calculation
                        // bug): this compares "days met so far this month" against the SAME ELAPSED
                        // WINDOW last month (e.g. days 1-21 of each month, not last month's full total) -
                        // deliberately, to avoid unfairly reading "down" early in a month. The math was
                        // always correct, but nothing on screen said so, which read as contradictory
                        // against the "Most met month" card's full-month total sitting right next to it.
                        emoji = monthTrendDeltaEmoji(stats.monthTrendDeltaDays),
                        label = "This month vs last (so far)",
                        value = monthTrendDeltaLabel(stats.monthTrendDeltaDays),
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenMonthlyDetail("days") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(top = 12.dp)) {
                    StatCard(
                        emoji = "📈",
                        label = "Most met month",
                        value = stats.mostMetMonth?.let { "${monthLabel(it.yearMonth)} (${it.value.toInt()}d)" } ?: "—",
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenMonthlyDetail("days") }
                    )
                    StatCard(
                        emoji = "⏰",
                        label = "Most hours month",
                        value = stats.mostHoursMonth?.let { "${monthLabel(it.yearMonth)} (${"%.1f".format(Locale.US, it.value)}h)" } ?: "—",
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenMonthlyDetail("hours") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(top = 12.dp)) {
                    StatCard(
                        emoji = "🌞",
                        label = "Longest single day",
                        value = stats.longestSingleDay?.let { "${"%.1f".format(Locale.US, it.hours)}h" } ?: "—",
                        modifier = Modifier.weight(1f),
                        onClick = stats.longestSingleDay?.let { day ->
                            { onOpenCalendarWithArgs(day.date.toEpochDay(), null, null) }
                        }
                    )
                    StatCard(
                        emoji = "🔁",
                        label = "Avg. days between meetups",
                        value = stats.avgDaysBetweenMeetups?.let { "%.1f".format(Locale.US, it) } ?: "—",
                        modifier = Modifier.weight(1f),
                        onClick = onOpenGapsDetail
                    )
                }
                stats.longestSingleDay?.let {
                    Text(
                        text = "Longest day together was ${DateFormats.formatDateLong(it.date)}.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                stats.longestApart?.let { gap ->
                    val start = java.time.Instant.ofEpochMilli(gap.startMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    val end = java.time.Instant.ofEpochMilli(gap.endMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clickable(onClick = onOpenGapsDetail),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Longest apart: ${"%.1f".format(Locale.US, gap.days)} days (${DateFormats.formatDateLong(start)} – ${DateFormats.formatDateLong(end)}).",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
            item {
                androidx.compose.material3.OutlinedButton(onClick = onOpenBadges, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Text("🏅 View Badges")
                }
            }
        }
    }
}

private fun formatMinutes(totalMinutes: Long): String =
    com.ssbmedia.twogether.util.RelativeTime.formatDuration(totalMinutes * 60_000L)

// BUG fix (ultimate-app-review Round 2, Opus, live-confirmed): pinned to Locale.US, matching this
// app's established standard (CalendarScreen.kt's own month-name display, and this file's own
// favourite-day value below, which already uses locale-invariant English via lowercase()/
// uppercase()) - a stray Locale.getDefault() here produced a real, live-observed inconsistency on a
// non-English device: this screen's favourite-day summary card read "Monday" while
// FavoriteDayDetailScreen's chart for the same data read "lun." (MonthlyDetailScreen.kt's own doc
// comment argued a getDisplayName call like this one was "locale-appropriate, intentional display of
// a proper name" and should stay device-locale - that reasoning didn't hold once this exact
// cross-screen mismatch was found; consistency with the rest of the app wins.)
private fun monthLabel(yearMonth: java.time.YearMonth): String =
    "${yearMonth.month.getDisplayName(TextStyle.SHORT, Locale.US)} ${yearMonth.year}"

/** Phase 1 item 5: the actual day-count delta for "This month vs last" (user explicitly wants DAYS, not
 * hours) - e.g. "Down 3 days" / "Up 2 days" / "Same", replacing the old 3-way Trend enum label that
 * discarded the real number [StatsCalculator.compute] already computes. See
 * [TogetherStats.monthTrendDeltaDays]'s doc for exactly what's compared. */
private fun monthTrendDeltaLabel(delta: Int): String = when {
    delta > 0 -> "Up $delta day" + (if (delta == 1) "" else "s")
    delta < 0 -> "Down ${-delta} day" + (if (-delta == 1) "" else "s")
    else -> "Same"
}

private fun monthTrendDeltaEmoji(delta: Int): String = when {
    delta > 0 -> "📈"
    delta < 0 -> "📉"
    else -> "➡️"
}
