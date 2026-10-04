package com.ssbmedia.twogether.ui.badges

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.data.datastore.AppSettings
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
    // Day-based badges (streaks, days together) bucket by the user's day start - see AppSettings.dayStartHour.
    // Nullable until DataStore emits: the default (4 AM) must never be used to RECORD an unlock, because
    // recordUnlockIfNeeded is first-write-wins and a wrong-day pass would persist a permanently wrong date.
    val loadedSettings by ServiceLocator.settingsStore.settings.collectAsState(initial = null)
    val dayStartHour = (loadedSettings ?: AppSettings()).dayStartHour
    val stats = remember(sessions, proximityState.lastSeenAt, proximityState.reunionCount, dayStartHour) {
        StatsCalculator.compute(
            sessions, lastSeenAt = proximityState.lastSeenAt, reunionCount = proximityState.reunionCount, dayStartHour = dayStartHour
        )
    }
    val statuses = remember(stats) { BadgeCatalog.statuses(stats) }
    // Same DateRange lookup StatsScreen's own "Longest streak" card already drives - see its doc there
    // for why min(...)-clamped LWW convergence, etc, isn't relevant here: this is a pure read.
    val longestDailyStreakRange: DateRange? = remember(sessions, proximityState.lastSeenAt, dayStartHour) {
        StatsCalculator.longestDailyStreakRange(sessions, lastSeenAt = proximityState.lastSeenAt, dayStartHour = dayStartHour)
    }
    // BUG fix (user-reported): WEEKLY_STREAK no longer routes to a single contiguous streak range - now
    // that it tracks cumulative weeks together (see BadgeCatalog.currentValueFor's doc), there's no one
    // "the streak" date span to highlight in Calendar; the qualifying weeks are scattered across all of
    // history, not one run. Dropped the longestWeeklyStreakRange lookup this screen used to feed that
    // now-removed click-through with (StatsCalculator.longestWeeklyStreakRange itself is unaffected and
    // still powers StatsScreen's own separate "Longest streak" card, which still means the true
    // consecutive-streak concept).

    LaunchedEffect(statuses, loadedSettings) {
        // Wait for the saved day start (see loadedSettings above) before writing any unlock timestamp.
        if (loadedSettings == null) return@LaunchedEffect
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
                                BadgeCardContent(status, unlockDates, dayStartHour)
                            }
                        } else {
                            Card(modifier = cardModifier, shape = MaterialTheme.shapes.large, colors = cardColors) {
                                BadgeCardContent(status, unlockDates, dayStartHour)
                            }
                        }
                    }
                    // Odd badge count on the final row: fill the second column with an empty weighted
                    // Box instead of letting the lone card stretch to double width, matching a 2-column
                    // grid's own natural behavior for a trailing incomplete row.
                    if (rowStatuses.size == 1) {
                        Box(modifier = Modifier.weight(1f))
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
private fun BadgeCardContent(status: BadgeStatus, unlockDates: Map<String, Long>, dayStartHour: Int) {
    // BUG fix (user-reported), take 3: the previous fix aligned the progress line's OWN position
    // consistently regardless of title length (Box.heightIn below), but left this Column - which had
    // no fillMaxHeight/fillMaxWidth, so it just wrapped its own content size - anchored to the TOP-START
    // corner of whatever bigger box a row's tallest/widest sibling forces onto this Card
    // (IntrinsicSize.Max on the parent Row, Modifier.weight(1f) giving every card equal width).
    // horizontalAlignment/verticalArrangement only center children WITHIN the Column's own bounds - with
    // no fillMaxHeight/fillMaxWidth, those bounds were only as big as the content itself (e.g. the width
    // of "50 Hours Together"'s text), so there was nothing wider/taller to actually center against,
    // leaving dead space collect at the bottom (shorter-content card in a row with a 2-line-title
    // sibling) and on the right (narrower-content card in a row with a wider-title sibling) instead of
    // splitting evenly. fillMaxSize() gives the Column the card's FULL bounds so
    // horizontalAlignment/verticalArrangement have real room to center within.
    Column(
        modifier = Modifier.padding(16.dp).fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = status.badge.emoji,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.graphicsLayer {
                alpha = if (status.unlocked) 1f else 0.35f
            }
        )
        // BUG fix (user-reported), take 2: row height was already equalized (IntrinsicSize.Max, see
        // this Row's own comment above), but a 1-line title ("50 Hours Together") vs a 2-line one
        // ("100 Hours Together" wraps) still left every card's OWN progress text ("0/50" etc.) at a
        // different vertical offset, since a plain top-aligned Column gives a shorter title less space
        // above the progress line. The first fix for this used Text's own minLines = 2 to reserve a
        // consistent 2-line block - which looked right whenever the SAME row happened to contain a
        // genuinely-2-line title (its real, correctly-measured height set the row's IntrinsicSize.Max
        // tall enough for everyone), but broke completely whenever EVERY title in a row was naturally
        // 1-line ("30 Day Streak" + "100 Day Streak"): IntrinsicSize.Max's intrinsic-measurement pass
        // doesn't correctly account for Text's minLines (a known Compose rough edge - minLines is
        // enforced in the real measure pass but not faithfully mirrored in the cheaper intrinsic-measure
        // pass), so the ROW itself was sized too short, and the Card's Surface then clipped the
        // progress text off entirely rather than just showing misaligned. A fixed-dp Box.heightIn(min)
        // is a plain layout constraint Compose's intrinsic-measure pass handles correctly (unlike
        // minLines), so it doesn't have this gap. 48.dp comfortably fits two titleMedium lines at
        // default scale and, being a MINIMUM (not a cap), still grows further under larger accessibility
        // font sizes rather than clipping.
        // MINOR fix (independent audit): padding was INSIDE heightIn's min, so the 8dp top padding ate
        // into the reserved 48dp, leaving only 40dp for two titleMedium lines (2 x 22sp lineHeight =
        // 44dp) - a 2-line title's box (52dp, since the min no longer bound it) and a 1-line title's box
        // (48dp) differed by 4dp, the "consistent progress-text offset" this fix's own comment promises
        // was still off between row siblings. padding OUTSIDE heightIn fixes the order.
        Box(modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            Text(
                text = status.badge.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                // MINOR fix (independent audit): the comment above claims the heightIn minimum "still
                // grows further under larger accessibility font sizes rather than clipping" - true for
                // the BOX, but maxLines=2 with the default TextOverflow.Clip silently clips the TEXT
                // itself once a title needs a 3rd line (e.g. "1000 Hours Together" at a large font
                // scale) - the exact silent-clipping bug class this whole fix sequence exists to close,
                // just moved from the box to the text. Ellipsis at least signals truncation instead of
                // silently dropping a word with no visual indication.
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = status.progressLabel,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp)
        )
        if (status.unlocked) {
            val unlockedAt = unlockDates[status.badge.id]
            if (unlockedAt != null) {
                // Same logical day as the streak it was earned on (AppSettings.dayStartHour), so a 1 AM unlock
                // reads as the day the couple was still together, not the next calendar date.
                val unlockedDate = remember(unlockedAt, dayStartHour) {
                    StatsCalculator.logicalDayOf(unlockedAt, ZoneId.systemDefault(), dayStartHour)
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
 * above the badge cards, as the first child of [BadgesScreen]'s own scrolling Column - not a grid item
 * (this screen no longer uses a LazyVerticalGrid at all - see this file's own row-equalization history
 * for why - MINOR fix, independent audit: this doc used to describe the removed `item(span = ...)` shape). */
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
                        fraction = row.fraction,
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
// MINOR fix (independent audit): was `current: Double, prevThreshold: Int, nextThreshold: Int` with the
// fraction recomputed INLINE below - duplicating the exact same formula BadgeCatalog.progressRow already
// computed as BadgeProgressRow.fraction (the value BadgeCatalogProgressTest.kt actually asserts against).
// The two formulas happened to agree, but nothing enforced that - editing one without the other would
// silently desync the drawn bar from its own tests. Both call sites (BadgesScreen's own
// BadgeProgressBarsSection, HomeScreen.NextBadgeProgressCard) already have a real BadgeProgressRow with
// .fraction on hand, so this now takes that value directly instead of re-deriving it. nextThreshold is
// kept (needed for the "Goal: N Label" caption below), current/prevThreshold dropped (no longer needed
// for anything once fraction isn't computed here).
internal fun BadgeProgressBarRow(emoji: String, label: String, fraction: Float, nextThreshold: Int, caption: String) {
    Column {
        Text(
            text = "$emoji $label",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
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
            // MINOR fix (independent audit): was unweighted, so in a Row it gets measured against the
            // FULL available width before the caption's own weight(1f, fill=false) claims its share -
            // at a large system font scale, "Goal: 52 Weeks Together" could consume nearly the whole
            // row, squeezing the caption ("3 weeks to go") into single-character-wide wraps. A bounded
            // weight lets both texts share the row instead of one starving the other.
            Text(
                text = "Goal: $nextThreshold $label",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp)
            )
        }
    }
}
