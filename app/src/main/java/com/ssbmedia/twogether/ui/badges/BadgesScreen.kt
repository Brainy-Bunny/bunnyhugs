package com.ssbmedia.twogether.ui.badges

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
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
    val stats = remember(sessions, proximityState.lastSeenAt, proximityState.reunionCount) {
        StatsCalculator.compute(sessions, lastSeenAt = proximityState.lastSeenAt, reunionCount = proximityState.reunionCount)
    }
    val statuses = remember(stats) { BadgeCatalog.statuses(stats) }
    // Same DateRange lookup StatsScreen's own "Longest streak" card already drives - see its doc there
    // for why min(...)-clamped LWW convergence, etc, isn't relevant here: this is a pure read.
    val longestDailyStreakRange: DateRange? = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.longestDailyStreakRange(sessions, lastSeenAt = proximityState.lastSeenAt)
    }
    // BUG fix (user-reported): WEEKLY_STREAK no longer routes to a single contiguous streak range - now
    // that it tracks cumulative weeks together (see BadgeCatalog.currentValueFor's doc), there's no one
    // "the streak" date span to highlight in Calendar; the qualifying weeks are scattered across all of
    // history, not one run. Dropped the longestWeeklyStreakRange lookup this screen used to feed that
    // now-removed click-through with (StatsCalculator.longestWeeklyStreakRange itself is unaffected and
    // still powers StatsScreen's own separate "Longest streak" card, which still means the true
    // consecutive-streak concept).

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
        // BUG fix (user-reported): was a LazyVerticalGrid(columns = GridCells.Fixed(2)) - a grid line's
        // overall height is set by its tallest item (e.g. a 2-line-wrapping title like "500 Hours
        // Together"), but Compose does NOT stretch the grid's OTHER items in that same line to fill
        // that height - a shorter card (e.g. "7 Day Streak", one line) just sits top-aligned inside the
        // taller allocated row band, leaving a visible gap of bare background below its own card
        // boundary before the next row starts. Restructured to plain chunked Rows, each with
        // Modifier.height(IntrinsicSize.Max) + each card Modifier.fillMaxHeight() - the same proven
        // per-row equalization technique StatCard/HomeScreen's quick-facts row now use (see StatCard's
        // own doc in Components.kt) - which DOES reliably stretch a Row's children to match its tallest
        // one. Loses LazyVerticalGrid's virtualization, but the badge list is bounded to a few dozen
        // entries at most (SEEDS + 2-ahead auto-extension per category, see BadgeCatalog), not a
        // performance concern at this scale - and dropping the grid entirely also fully resolves the
        // original "avoid nesting two lazy-scrolling containers" reason this screen used a grid instead
        // of a LazyColumn in the first place (see the removed comment this replaces): there's now only
        // ONE scrollable container here, not two.
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BadgeProgressBarsSection(stats)
            statuses.chunked(2).forEach { rowStatuses ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowStatuses.forEach { status ->
                        // UX-FIX-PLAN.md Phase 3 item 20: Badge -> the stat that earned it. HOURS and
                        // REUNIONS route to their own dedicated detail screens; DAILY_STREAK routes to
                        // Calendar highlighting the actual streak range (same DateRange lookup
                        // StatsScreen's own "Longest streak" card uses) - null (no qualifying streak yet)
                        // means no destination, so the card simply isn't clickable rather than navigating
                        // somewhere with nothing to show. WEEKLY_STREAK (now cumulative weeks together,
                        // see BadgeCatalog.currentValueFor's doc) and PERFECT_WEEKS both have no single
                        // date range to highlight, so both stay non-clickable - grid-only by design, same
                        // as BadgeProgressBarsSection's own doc already established for PERFECT_WEEKS.
                        val onClick: (() -> Unit)? = when (status.badge.type) {
                            BadgeType.HOURS -> onOpenHoursDetail
                            BadgeType.REUNIONS -> onOpenGapsDetail
                            BadgeType.DAILY_STREAK -> longestDailyStreakRange?.let { range ->
                                { onOpenCalendarWithArgs(null, range.start.toEpochDay(), range.end.toEpochDay()) }
                            }
                            BadgeType.WEEKLY_STREAK -> null
                            BadgeType.PERFECT_WEEKS -> null
                        }
                        val cardColors = CardDefaults.cardColors(
                            containerColor = if (status.unlocked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                        val cardModifier = Modifier.weight(1f).fillMaxHeight()
                        if (onClick != null) {
                            Card(modifier = cardModifier, shape = MaterialTheme.shapes.large, colors = cardColors, onClick = onClick) {
                                BadgeCardContent(status, unlockDates)
                            }
                        } else {
                            Card(modifier = cardModifier, shape = MaterialTheme.shapes.large, colors = cardColors) {
                                BadgeCardContent(status, unlockDates)
                            }
                        }
                    }
                    // Odd badge count on the final row: fill the second column with an empty weighted
                    // Box instead of letting the lone card stretch to double width, matching a 2-column
                    // grid's own natural behavior for a trailing incomplete row.
                    if (rowStatuses.size == 1) {
                        androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f))
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
            textAlign = TextAlign.Center,
            // BUG fix (user-reported): row height was already equalized (IntrinsicSize.Max, see this
            // Row's own comment above), but a 1-line title ("50 Hours Together") vs a 2-line one
            // ("100 Hours Together" wraps) still left every card's OWN progress text ("0/50" etc.) at a
            // different vertical offset within that equal-height card, since a plain top-aligned Column
            // gives a shorter title less space above the progress line. minLines = 2 reserves the same
            // title block height on every card regardless of whether its own title actually wraps, so
            // the progress line lands at the same y-position across an entire row.
            minLines = 2,
            maxLines = 2,
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
                    text = "on ${DateFormats.formatDateLong(unlockedDate)}",
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
    // BUG fix (user-reported "faded text" pattern, same root cause as SettingsSection's confirmed bug -
    // see its own doc in SettingsScreen.kt): containerColor = surfaceVariant with no explicit
    // contentColor defaults content color to onSurfaceVariant (muted), which BadgeProgressBarRow/
    // BadgeMaxedRow's own title Text below has no explicit color to override.
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // UX-FIX-PLAN.md Phase 3 item 21: the threshold/fraction/caption math for these 4 rows now
            // lives in BadgeCatalog.progressRows (a plain, unit-tested pure function) instead of being
            // computed inline here - Home's own compact "closest to your next badge" card
            // (HomeScreen.NextBadgeProgressCard) reuses the EXACT same BadgeCatalog.progressRows /
            // BadgeCatalog.closestToNextBadge logic, so the two surfaces can never silently drift apart.
            BadgeCatalog.progressRows(stats).forEach { row ->
                if (row.maxed) {
                    BadgeMaxedRow(emoji = row.emoji, label = row.label, caption = row.caption)
                } else {
                    BadgeProgressBarRow(
                        emoji = row.emoji,
                        label = row.label,
                        current = row.current,
                        prevThreshold = row.prevThreshold,
                        nextThreshold = row.nextThreshold,
                        caption = row.caption
                    )
                }
            }
        }
    }
}

/** Shown instead of [BadgeProgressBarRow] once a category has reached its absolute ceiling (see
 * BadgeCatalog.CAPS/isMaxed) - a full, non-informational bar (there's no "next badge" left to count down
 * to) plus celebratory copy, rather than the "0.0h to your next badge" a literal-minded countdown would
 * otherwise show once maxed out. */
/** Non-private: reused directly by HomeScreen.NextBadgeProgressCard (UX-FIX-PLAN.md Phase 3 item 21) so
 * Home's compact card renders identically to this screen's own maxed-out row instead of a second
 * hand-copied version. */
@Composable
internal fun BadgeMaxedRow(emoji: String, label: String, caption: String) {
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

/** One progress-bar row: a label, a fraction-of-the-way-to-[nextThreshold] bar, and a countdown caption
 * with the actual goal number shown at the bar's end (e.g. "2 weeks to go" ... "12 Weeks Together") -
 * user-reported: the bar used to show a countdown with no visible target, so there was no way to tell
 * WHICH badge/number it was counting down toward without leaving this card. [current] is always passed
 * as a Double (even for the 3 whole-number categories) purely so Hours - the one category genuinely
 * tracked as a fraction of an hour - can share this same row instead of a near-duplicate Int-only
 * version; the caller decides whether its own caption text needs decimal precision (Hours) or a whole
 * number (everything else). */
/** Non-private: reused directly by HomeScreen.NextBadgeProgressCard - see [BadgeMaxedRow]'s doc above for
 * why. */
@Composable
internal fun BadgeProgressBarRow(emoji: String, label: String, current: Double, prevThreshold: Int, nextThreshold: Int, caption: String) {
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = "Goal: $nextThreshold $label",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
