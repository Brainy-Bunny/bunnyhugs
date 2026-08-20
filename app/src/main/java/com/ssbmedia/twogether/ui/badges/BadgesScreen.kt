package com.ssbmedia.twogether.ui.badges

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.badges.BadgeCatalog
import com.ssbmedia.twogether.badges.BadgeStatus
import com.ssbmedia.twogether.badges.BadgeType
import com.ssbmedia.twogether.data.datastore.ProximityPersistedState
import com.ssbmedia.twogether.stats.DateRange
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.stats.TogetherStats
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import com.ssbmedia.twogether.util.DateFormats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

class BadgesViewModel : ViewModel() {
    val sessions = ServiceLocator.sessionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val proximityState = ServiceLocator.proximityStateStore.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProximityPersistedState())
    val unlockDates = ServiceLocator.badgeUnlocksStore.unlockedAtByBadgeId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Persists the first-unlocked-at timestamp for any badge whose condition is newly met and doesn't
     * already have one recorded - checked every time this screen recomputes stats, since BadgeCatalog
     * itself has no notion of "when" a badge first crossed its threshold. Never overwrites an existing
     * timestamp (see BadgeUnlocksStore.recordUnlockIfNeeded). */
    fun recordNewlyUnlocked(unlockedBadgeIds: List<String>) {
        if (unlockedBadgeIds.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            unlockedBadgeIds.forEach { ServiceLocator.badgeUnlocksStore.recordUnlockIfNeeded(it, now) }
        }
    }
}

@Composable
fun BadgesScreen(
    onBack: () -> Unit,
    // UX-FIX-PLAN.md Phase 3 item 20: Badge -> the stat that earned it - same
    // onOpenHoursDetail/onOpenGapsDetail/onOpenCalendarWithArgs shapes StatsScreen's own drill-down cards
    // already use, wired here to whichever badge category earned each one (see the per-status onClick
    // below).
    onOpenHoursDetail: () -> Unit = {},
    onOpenGapsDetail: () -> Unit = {},
    onOpenCalendarWithArgs: (jumpToEpochDay: Long?, highlightStartEpochDay: Long?, highlightEndEpochDay: Long?) -> Unit = { _, _, _ -> }
) {
    val vm: BadgesViewModel = viewModel(factory = SimpleViewModelFactory { BadgesViewModel() })
    val sessions by vm.sessions.collectAsState()
    val proximityState by vm.proximityState.collectAsState()
    val unlockDates by vm.unlockDates.collectAsState()
    // lastSeenAt clamps an open session's live duration so badge progress can't be inflated by a
    // stale/orphaned open session - see StatsCalculator.effectiveOpenSessionEnd's doc.
    val stats = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.compute(sessions, lastSeenAt = proximityState.lastSeenAt)
    }
    val statuses = remember(stats) { BadgeCatalog.statuses(stats) }
    // Same DateRange lookups StatsScreen's own "Longest streak" cards already drive - see their doc there
    // for why min(...)-clamped LWW convergence, etc, isn't relevant here: this is a pure read.
    val longestDailyStreakRange: DateRange? = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.longestDailyStreakRange(sessions, lastSeenAt = proximityState.lastSeenAt)
    }
    val longestWeeklyStreakRange: DateRange? = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.longestWeeklyStreakRange(sessions, lastSeenAt = proximityState.lastSeenAt)
    }

    LaunchedEffect(statuses) {
        vm.recordNewlyUnlocked(statuses.filter { it.unlocked }.map { it.badge.id })
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Badges") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Full-width header, spanning both grid columns - inserted as a grid item (rather than
            // wrapping the grid in an outer LazyColumn) to avoid nesting two lazy-scrolling containers.
            item(span = { GridItemSpan(maxLineSpan) }) {
                BadgeProgressBarsSection(stats)
            }
            items(statuses, key = { it.badge.id }) { status ->
                // UX-FIX-PLAN.md Phase 3 item 20: Badge -> the stat that earned it. HOURS and REUNIONS
                // route to their own dedicated detail screens; DAILY_STREAK/WEEKLY_STREAK route to
                // Calendar highlighting the actual streak range (same DateRange lookups StatsScreen's own
                // "Longest streak" cards use) - null (no qualifying streak yet) means no destination, so
                // the card simply isn't clickable rather than navigating somewhere with nothing to show.
                // PERFECT_WEEKS has no dedicated drill-down screen (grid-only by design, see
                // BadgeProgressBarsSection's own doc), so it stays non-clickable too.
                val onClick: (() -> Unit)? = when (status.badge.type) {
                    BadgeType.HOURS -> onOpenHoursDetail
                    BadgeType.REUNIONS -> onOpenGapsDetail
                    BadgeType.DAILY_STREAK -> longestDailyStreakRange?.let { range ->
                        { onOpenCalendarWithArgs(null, range.start.toEpochDay(), range.end.toEpochDay()) }
                    }
                    BadgeType.WEEKLY_STREAK -> longestWeeklyStreakRange?.let { range ->
                        { onOpenCalendarWithArgs(null, range.start.toEpochDay(), range.end.toEpochDay()) }
                    }
                    BadgeType.PERFECT_WEEKS -> null
                }
                val cardColors = CardDefaults.cardColors(
                    containerColor = if (status.unlocked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
                if (onClick != null) {
                    Card(shape = MaterialTheme.shapes.large, colors = cardColors, onClick = onClick) {
                        BadgeCardContent(status, unlockDates)
                    }
                } else {
                    Card(shape = MaterialTheme.shapes.large, colors = cardColors) {
                        BadgeCardContent(status, unlockDates)
                    }
                }
            }
        }
    }
}

/** The badge card's inner content, factored out so both the clickable and non-clickable [Card] overloads
 * in [BadgesScreen] above (Material3 gives them structurally different signatures, so the same Card
 * instance can't conditionally take an onClick) render identically. */
@Composable
private fun BadgeCardContent(status: BadgeStatus, unlockDates: Map<String, Long>) {
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = status.badge.emoji,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.graphicsLayer {
                alpha = if (status.unlocked) 1f else 0.35f
            }
        )
        Text(
            text = status.badge.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = status.progressLabel,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp)
        )
        if (status.unlocked) {
            val unlockedAt = unlockDates[status.badge.id]
            if (unlockedAt != null) {
                val unlockedDate = remember(unlockedAt) {
                    Instant.ofEpochMilli(unlockedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                }
                Text(
                    text = "on ${DateFormats.formatDate(unlockedDate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
    }
}

/**
 * The 4 headline progress bars the owner asked for - one each for Hours/Daily streak/Weekly streak/
 * Reunions (deliberately NOT Perfect weeks, which stays grid-only) - showing how far along the couple is
 * toward their next not-yet-earned badge in that category, with a countdown caption underneath. Sits
 * above the badge grid itself as a full-width grid item (see the `item(span = ...)` call in
 * [BadgesScreen]).
 */
@Composable
private fun BadgeProgressBarsSection(stats: TogetherStats) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hours is the one category tracked as a fraction rather than a whole count, so it's the
            // only row whose current value/caption keep 1-decimal precision instead of rounding to a
            // whole number - see BadgeProgressBarRow's doc.
            val hoursCurrent = stats.totalHoursAllTime.toInt()
            if (BadgeCatalog.isMaxed(BadgeType.HOURS, hoursCurrent)) {
                val badge = BadgeCatalog.maxedBadge(BadgeType.HOURS)!!
                BadgeMaxedRow(emoji = badge.emoji, label = "Hours", caption = "${badge.title} 💛")
            } else {
                val (hoursPrev, hoursNext) = BadgeCatalog.nextAndPrevThreshold(BadgeType.HOURS, hoursCurrent)
                BadgeProgressBarRow(
                    emoji = "💛",
                    label = "Hours",
                    current = stats.totalHoursAllTime,
                    prevThreshold = hoursPrev,
                    nextThreshold = hoursNext,
                    caption = "${"%.1f".format((hoursNext - stats.totalHoursAllTime).coerceAtLeast(0.0))}h to your next badge"
                )
            }

            if (BadgeCatalog.isMaxed(BadgeType.DAILY_STREAK, stats.longestDailyStreak)) {
                val badge = BadgeCatalog.maxedBadge(BadgeType.DAILY_STREAK)!!
                BadgeMaxedRow(emoji = badge.emoji, label = "Days", caption = "${badge.title} 💛")
            } else {
                val (daysPrev, daysNext) = BadgeCatalog.nextAndPrevThreshold(BadgeType.DAILY_STREAK, stats.longestDailyStreak)
                val daysRemaining = (daysNext - stats.longestDailyStreak).coerceAtLeast(0)
                BadgeProgressBarRow(
                    emoji = "🔥",
                    label = "Days",
                    current = stats.longestDailyStreak.toDouble(),
                    prevThreshold = daysPrev,
                    nextThreshold = daysNext,
                    // BUG fix: "1 days to your next badge" was reachable whenever exactly 1 more day
                    // would complete the streak.
                    caption = "$daysRemaining day" + (if (daysRemaining == 1) "" else "s") + " to your next badge"
                )
            }

            if (BadgeCatalog.isMaxed(BadgeType.WEEKLY_STREAK, stats.longestWeeklyStreak)) {
                val badge = BadgeCatalog.maxedBadge(BadgeType.WEEKLY_STREAK)!!
                BadgeMaxedRow(emoji = badge.emoji, label = "Week Streak", caption = "${badge.title} 💛")
            } else {
                val (weeksPrev, weeksNext) = BadgeCatalog.nextAndPrevThreshold(BadgeType.WEEKLY_STREAK, stats.longestWeeklyStreak)
                val weeksRemaining = (weeksNext - stats.longestWeeklyStreak).coerceAtLeast(0)
                BadgeProgressBarRow(
                    emoji = "🌟",
                    label = "Week Streak",
                    current = stats.longestWeeklyStreak.toDouble(),
                    prevThreshold = weeksPrev,
                    nextThreshold = weeksNext,
                    caption = "$weeksRemaining week" + (if (weeksRemaining == 1) "" else "s") + " to your next badge"
                )
            }

            if (BadgeCatalog.isMaxed(BadgeType.REUNIONS, stats.reunionCount)) {
                val badge = BadgeCatalog.maxedBadge(BadgeType.REUNIONS)!!
                BadgeMaxedRow(emoji = badge.emoji, label = "Reunions", caption = "${badge.title} 💛")
            } else {
                val (reunionsPrev, reunionsNext) = BadgeCatalog.nextAndPrevThreshold(BadgeType.REUNIONS, stats.reunionCount)
                val reunionsRemaining = (reunionsNext - stats.reunionCount).coerceAtLeast(0)
                BadgeProgressBarRow(
                    emoji = "🤗",
                    label = "Reunions",
                    current = stats.reunionCount.toDouble(),
                    prevThreshold = reunionsPrev,
                    nextThreshold = reunionsNext,
                    caption = "$reunionsRemaining reunion" + (if (reunionsRemaining == 1) "" else "s") + " to your next badge"
                )
            }
        }
    }
}

/** Shown instead of [BadgeProgressBarRow] once a category has reached its absolute ceiling (see
 * BadgeCatalog.CAPS/isMaxed) - a full, non-informational bar (there's no "next badge" left to count down
 * to) plus celebratory copy, rather than the "0.0h to your next badge" a literal-minded countdown would
 * otherwise show once maxed out. */
@Composable
private fun BadgeMaxedRow(emoji: String, label: String, caption: String) {
    Column {
        Text(
            text = "$emoji $label",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        LinearProgressIndicator(
            progress = { 1f },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** One progress-bar row: a label, a fraction-of-the-way-to-[nextThreshold] bar, and a countdown caption.
 * [current] is always passed as a Double (even for the 3 whole-number categories) purely so Hours - the
 * one category genuinely tracked as a fraction of an hour - can share this same row instead of a
 * near-duplicate Int-only version; the caller decides whether its own caption text needs decimal
 * precision (Hours) or a whole number (everything else). */
@Composable
private fun BadgeProgressBarRow(emoji: String, label: String, current: Double, prevThreshold: Int, nextThreshold: Int, caption: String) {
    Column {
        Text(
            text = "$emoji $label",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        val span = (nextThreshold - prevThreshold).coerceAtLeast(1)
        val fraction = ((current - prevThreshold) / span).toFloat().coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
