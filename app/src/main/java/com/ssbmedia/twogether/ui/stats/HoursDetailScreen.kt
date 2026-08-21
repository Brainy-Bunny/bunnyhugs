package com.ssbmedia.twogether.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.ui.components.EmptyState
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import com.ssbmedia.twogether.util.DateFormats
import kotlinx.coroutines.flow.SharingStarted
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

private const val WINDOW_SIZE = 14

/** Phase 1 item 6 of UX-FIX-PLAN.md: splits [totalSize] items into [windowSize]-sized windows anchored
 * from the END, not the start - the LAST window (most recent, index [pageCount]-1) is always exactly
 * [windowSize] items (or all of them, if there are fewer than [windowSize] total), and any leftover/
 * partial window lands at the START (oldest history) instead. Without this, chunking from index 0 could
 * leave the default/most-recent page as a short leftover chunk instead of always showing the true most
 * recent [windowSize] days. Returns each window as a start-until-end (exclusive) index range, oldest
 * window first. */
private fun windowBoundsAnchoredFromEnd(totalSize: Int, windowSize: Int): List<IntRange> {
    if (totalSize <= 0) return emptyList()
    val fullWindowCount = totalSize / windowSize
    val remainder = totalSize % windowSize
    val bounds = mutableListOf<IntRange>()
    var cursor = 0
    if (remainder > 0) {
        bounds.add(cursor until (cursor + remainder))
        cursor += remainder
    }
    repeat(fullWindowCount) {
        bounds.add(cursor until (cursor + windowSize))
        cursor += windowSize
    }
    return bounds
}

class HoursDetailViewModel : ViewModel() {
    val sessions = ServiceLocator.sessionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val proximityState = ServiceLocator.proximityStateStore.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.ssbmedia.twogether.data.datastore.ProximityPersistedState())
}

/**
 * Feature 1: "Hours together" card drill-down. Reuses [StatsCalculator.buildDailyMinuteMap] (the exact
 * same per-day data Stats/Calendar already show) and re-buckets it into fixed [WINDOW_SIZE]-day pages,
 * zero-filling any day with no together-time so the X-axis stays a continuous calendar rather than
 * skipping gaps. The pager opens on the MOST RECENT window (last page index) - swiping right (toward
 * lower page indices) reveals earlier history, clamped at the couple's very first together-day.
 */
@Composable
fun HoursDetailScreen(onBack: () -> Unit) {
    val vm: HoursDetailViewModel = viewModel(factory = SimpleViewModelFactory { HoursDetailViewModel() })
    val sessions by vm.sessions.collectAsState()
    val proximityState by vm.proximityState.collectAsState()
    val zone = remember { ZoneId.systemDefault() }

    val dailyHours = remember(sessions, proximityState.lastSeenAt) {
        val minutesPerDay = StatsCalculator.buildDailyMinuteMap(sessions, zone = zone, lastSeenAt = proximityState.lastSeenAt)
        if (minutesPerDay.isEmpty()) {
            emptyList()
        } else {
            val first = minutesPerDay.keys.min()
            val today = LocalDate.now(zone)
            val out = mutableListOf<Pair<LocalDate, Double>>()
            var cursor = first
            while (!cursor.isAfter(today)) {
                out.add(cursor to (minutesPerDay[cursor] ?: 0L) / 60.0)
                cursor = cursor.plusDays(1)
            }
            out
        }
    }

    // Phase 1 item 6: windows anchored from the END (today) backward, so the default/latest page is
    // always exactly the most recent WINDOW_SIZE days, not a short leftover chunk - see
    // windowBoundsAnchoredFromEnd's own doc.
    val pageBounds = remember(dailyHours) { windowBoundsAnchoredFromEnd(dailyHours.size, WINDOW_SIZE) }
    val pageCount = pageBounds.size
    val pagerState = rememberPagerState(initialPage = (pageCount - 1).coerceAtLeast(0)) { pageCount }
    // `sessions` starts as an empty StateFlow default and only resolves to the real Room data a moment
    // after this screen's first composition (SharingStarted.WhileSubscribed) - so pageCount is 0 on that
    // very first frame, `rememberPagerState`'s initialPage bakes in page 0, and it never budges once the
    // real (larger) pageCount arrives, since initialPage only applies at creation. Explicitly jump to the
    // last (most recent) page the FIRST time pageCount becomes real/nonzero, tracked so it only happens
    // once - otherwise this would also yank the user back to "latest" every time they'd paged back into
    // history and the surrounding data merely recomposed (e.g. a live session tick).
    var hasJumpedToLatest by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pageCount) {
        if (!hasJumpedToLatest && pageCount > 0) {
            pagerState.scrollToPage(pageCount - 1)
            hasJumpedToLatest = true
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Hours together") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (dailyHours.isEmpty()) {
            EmptyState(
                emoji = "💛",
                title = "No hours yet",
                subtitle = "Once you've spent some time together, your daily hours will show up here.",
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text(
                    "Swipe to see earlier days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                    val bounds = pageBounds.getOrNull(page) ?: (0 until 0)
                    val windowData = dailyHours.subList(bounds.first, bounds.last + 1)
                    Column {
                        val rangeLabel = if (windowData.size > 1) {
                            "${DateFormats.formatDateLong(windowData.first().first)} – " +
                                DateFormats.formatDateLong(windowData.last().first)
                        } else {
                            DateFormats.formatDateLong(windowData.first().first)
                        }
                        Text(rangeLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        DailyHoursBarChart(windowData, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}

/**
 * Phase 1 items 3 + 4 of UX-FIX-PLAN.md: this used to draw bars in a [Canvas] using fixed-dp-gap math,
 * then label them in a separate [Row] of equal-`weight(1f)` columns below - different layout math for
 * the bars vs the labels, so labels drifted out of alignment with their own bars (worse toward the
 * edges), AND never showed the actual hours value at all (day-of-month number only). Both bugs share one
 * root cause and one fix: labels are now drawn INSIDE the same [Canvas], using the exact same per-bar
 * `left`/`barWidth` geometry already computed for the bars themselves - there is no second layout pass
 * left to disagree with the first. With up to 14 bars on this screen, each label is two short lines
 * (day-of-month, then the hours value) in [MaterialTheme.typography.labelSmall] or smaller - legible at
 * that width without needing a full multi-line [Column] per bar.
 */
@Composable
private fun DailyHoursBarChart(data: List<Pair<LocalDate, Double>>, modifier: Modifier = Modifier) {
    val maxVal = (data.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(1.0)
    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val dayLabelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor, textAlign = TextAlign.Center)
    val valueLabelStyle = MaterialTheme.typography.labelSmall.copy(
        color = labelColor,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.82f
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            // MINOR fix (ultimate-app-review round 1, item 5): pinned to Locale.US - see GapsDetailScreen's
            // matching fix for why an unpinned "%.1f".format(...) contradicts DateFormats' own stated
            // intent of deterministic, locale-independent display text.
            "${"%.1f".format(Locale.US, maxVal)}h",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Canvas(modifier = Modifier.fillMaxWidth().height(240.dp).padding(top = 4.dp, bottom = 4.dp)) {
            val barCount = data.size
            if (barCount == 0) return@Canvas
            val gap = 6.dp.toPx()
            // Reserved band at the bottom for the two label lines - bars are scaled to fit ABOVE it, so
            // a tall bar can never grow into/behind its own label.
            val labelAreaHeight = 34.dp.toPx()
            val barAreaHeight = (size.height - labelAreaHeight).coerceAtLeast(0f)
            val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
            data.forEachIndexed { i, (date, hours) ->
                val fraction = (hours / maxVal).toFloat().coerceIn(0f, 1f)
                val barHeight = (fraction * barAreaHeight).coerceAtLeast(if (hours > 0) 3f else 0f)
                val left = i * (barWidth + gap)
                val centerX = left + barWidth / 2f
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(left, barAreaHeight - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
                // Both labels are measured and centered against this exact bar's own `centerX` - the
                // same value that placed the bar itself, so alignment can't drift between them.
                val dayLayout = textMeasurer.measure(date.dayOfMonth.toString(), dayLabelStyle)
                // MINOR fix (independent audit): the value label just below gets edge-clamped (see its
                // own comment), but this day label never did, despite the comment above claiming "both
                // labels are measured and centered against this exact bar's own centerX ... so alignment
                // can't drift between them" - for the first/last bar that was false, since only one of
                // the two was actually clamped to the canvas bounds.
                val dayLeft = (centerX - dayLayout.size.width / 2f)
                    .coerceIn(0f, (size.width - dayLayout.size.width).coerceAtLeast(0f))
                drawText(dayLayout, topLeft = Offset(dayLeft, barAreaHeight + 2.dp.toPx()))
                // BUG fix (ultimate-app-review Round 2, Opus, live-confirmed): a 2-digit-hour value like
                // "12.4h" measured wider than this bar's own slot at typical widths (14 bars/chart), so
                // neighboring value labels visibly collided/merged, worse at larger accessibility font
                // scales. Dropping the decimal at 10h+ ("12h" instead of "12.4h") shortens the string
                // enough to fit the same slot single-digit-hour values already fit in - single decimal
                // precision was never meaningful at that magnitude anyway (see DAYS metric elsewhere in
                // this app's charts, which never shows sub-unit precision at all).
                // MINOR fix (independent audit): was hours.toInt() (truncates toward zero) - a 12.9h
                // bar labelled "12h", visibly self-contradicting this same chart's own header just above
                // ("${"%.1f"...}h") when that 12.9h bar happens to be the chart's maximum. roundToInt()
                // matches what a reader actually expects "dropping the decimal" to mean.
                val valueText = when {
                    hours <= 0 -> "–"
                    hours >= 10 -> "${hours.roundToInt()}h"
                    else -> "%.1fh".format(Locale.US, hours)
                }
                val valueLayout = textMeasurer.measure(valueText, valueLabelStyle)
                // BUG fix (same round/finding): even a fitting label could paint outside the Canvas
                // entirely for the first/last bar, since centering purely on centerX never checked the
                // canvas bounds. Clamped so no label's left/right edge can extend past [0, size.width].
                val valueLeft = (centerX - valueLayout.size.width / 2f)
                    .coerceIn(0f, (size.width - valueLayout.size.width).coerceAtLeast(0f))
                drawText(
                    valueLayout,
                    topLeft = Offset(valueLeft, barAreaHeight + 2.dp.toPx() + dayLayout.size.height)
                )
            }
            drawLine(
                color = gridColor,
                start = Offset(0f, barAreaHeight),
                end = Offset(size.width, barAreaHeight),
                strokeWidth = 1.5.dp.toPx()
            )
        }
    }
}
