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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.ssbmedia.twogether.data.datastore.AppSettings
import com.ssbmedia.twogether.stats.MonthlyBreakdown
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.ui.components.EmptyState
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private const val WINDOW_SIZE = 6

/** Phase 1 item 6 of UX-FIX-PLAN.md - see HoursDetailScreen's identical helper for the full doc. Kept as
 * a small private duplicate rather than a shared util: each screen already keeps its own page-window
 * constant/state, and this is a self-contained ~15-line pure function. */
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

private enum class MonthlyMetric { DAYS, HOURS }

class MonthlyDetailViewModel : ViewModel() {
    val sessions = ServiceLocator.sessionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val proximityState = ServiceLocator.proximityStateStore.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.ssbmedia.twogether.data.datastore.ProximityPersistedState())
}

/**
 * Feature 1: shared drill-down for BOTH "Most met month" and "Most hours month" Stats cards. Reuses
 * [StatsCalculator.computeMonthlyBreakdown] (the same continuous, zero-filled month timeline the Stats
 * screen's headline numbers are drawn from) and pages it [WINDOW_SIZE] months at a time, most-recent
 * window first - identical pattern to [HoursDetailScreen]'s daily pager, just at month granularity.
 *
 * @param initialMetric "days" or "hours" - which card was tapped, so the toggle opens pre-selected to
 * match rather than always defaulting to one side.
 */
@Composable
fun MonthlyDetailScreen(initialMetric: String, onBack: () -> Unit) {
    val vm: MonthlyDetailViewModel = viewModel(factory = SimpleViewModelFactory { MonthlyDetailViewModel() })
    val sessions by vm.sessions.collectAsState()
    val proximityState by vm.proximityState.collectAsState()

    // BUG fix: was plain `remember`, unlike hasJumpedToLatest below - an independent audit round noted
    // the days/hours toggle silently reset back to initialMetric on a config change (rotation) that
    // recreates the Activity, discarding whatever the user had actually selected. rememberSaveable
    // (enums are Serializable by default on the JVM, no custom Saver needed) survives that.
    var metric by rememberSaveable {
        mutableStateOf(if (initialMetric == "hours") MonthlyMetric.HOURS else MonthlyMetric.DAYS)
    }

    val settings by ServiceLocator.settingsStore.settings.collectAsState(initial = AppSettings())
    val monthly = remember(sessions, proximityState.lastSeenAt, settings.dayStartHour) {
        StatsCalculator.computeMonthlyBreakdown(sessions, lastSeenAt = proximityState.lastSeenAt, dayStartHour = settings.dayStartHour)
    }

    // Phase 1 item 6: windows anchored from the END (the current month) backward - see
    // windowBoundsAnchoredFromEnd's own doc for why chunking from index 0 could otherwise leave the
    // default/latest page as a short leftover chunk.
    val pageBounds = remember(monthly) { windowBoundsAnchoredFromEnd(monthly.size, WINDOW_SIZE) }
    val pageCount = pageBounds.size
    val pagerState = rememberPagerState(initialPage = (pageCount - 1).coerceAtLeast(0)) { pageCount }
    // See HoursDetailScreen's identical LaunchedEffect for why this is needed - `sessions` resolves
    // asynchronously after this pagerState is first created, so pageCount is 0 (and initialPage bakes in
    // page 0) on the very first frame. Jump to the last (most recent) page once real data arrives, but
    // only the first time, so it doesn't fight the user paging back through history afterward.
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
                title = { Text("Monthly breakdown") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (monthly.isEmpty()) {
            EmptyState(
                emoji = "📈",
                title = "No months yet",
                subtitle = "Once you've spent time together, your month-by-month breakdown will show up here.",
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = metric == MonthlyMetric.DAYS,
                        onClick = { metric = MonthlyMetric.DAYS },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text("Days met") }
                    SegmentedButton(
                        selected = metric == MonthlyMetric.HOURS,
                        onClick = { metric = MonthlyMetric.HOURS },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text("Hours") }
                }
                Text(
                    "Swipe to see earlier months",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                )
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                    // MINOR fix (final Opus re-audit): the old fallback `?: (0 until 0)` was itself a
                    // crash - see HoursDetailScreen's matching fix for the identical bug and reasoning.
                    val bounds = pageBounds.getOrNull(page)
                    if (bounds != null) {
                        val windowData = monthly.subList(bounds.first, bounds.last + 1)
                        Column {
                            val rangeLabel = if (windowData.size > 1) {
                                "${monthLabel(windowData.first().yearMonth)} – ${monthLabel(windowData.last().yearMonth)}"
                            } else {
                                monthLabel(windowData.first().yearMonth)
                            }
                            Text(rangeLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            MonthlyBarChart(windowData, metric, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Phase 1 item 4 of UX-FIX-PLAN.md: labels used to be drawn in a separate [Row] of equal-`weight(1f)`
 * columns below the [Canvas], using different layout math than the bars' own fixed-dp-gap positioning -
 * so labels drifted out of alignment with their bars, worse toward the edges. Both are now drawn inside
 * the same [Canvas] using the exact same per-bar `left`/`barWidth` geometry, so there's no second layout
 * pass left to disagree with the first.
 */
@Composable
private fun MonthlyBarChart(data: List<MonthlyBreakdown>, metric: MonthlyMetric, modifier: Modifier = Modifier) {
    val values = data.map { if (metric == MonthlyMetric.DAYS) it.daysMet.toDouble() else it.hours }
    val maxVal = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val barColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val unit = if (metric == MonthlyMetric.DAYS) "d" else "h"
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val monthLabelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor, textAlign = TextAlign.Center)
    val valueLabelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            // MINOR fix (ultimate-app-review round 1, item 5): pinned to Locale.US - see GapsDetailScreen's
            // matching fix. UPDATE (round 2, Opus, live-confirmed): the month-NAME display below is now
            // ALSO pinned to Locale.US - it used to be deliberately left on Locale.getDefault() here as
            // "locale-appropriate, intentional display of a proper name," but that reasoning didn't survive
            // a live-observed cross-screen inconsistency (this exact getDisplayName pattern producing
            // "Monday" on one screen and "lun." on another for the same data, on a non-English device) -
            // see StatsScreen.kt's monthLabel doc for the full story. Consistency with the rest of the app
            // wins over per-call "this one's a proper name" exceptions.
            if (metric == MonthlyMetric.DAYS) "${maxVal.toInt()}d" else "${"%.1f".format(Locale.US, maxVal)}h",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Canvas(modifier = Modifier.fillMaxWidth().height(260.dp).padding(top = 4.dp, bottom = 4.dp)) {
            val barCount = values.size
            if (barCount == 0) return@Canvas
            val gap = 20.dp.toPx()
            val labelAreaHeight = 36.dp.toPx()
            val barAreaHeight = (size.height - labelAreaHeight).coerceAtLeast(0f)
            val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
            values.forEachIndexed { i, value ->
                val fraction = (value / maxVal).toFloat().coerceIn(0f, 1f)
                val barHeight = (fraction * barAreaHeight).coerceAtLeast(if (value > 0) 3f else 0f)
                val left = i * (barWidth + gap)
                val centerX = left + barWidth / 2f
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(left, barAreaHeight - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                )
                val monthLayout = textMeasurer.measure(
                    data[i].yearMonth.month.getDisplayName(TextStyle.SHORT, Locale.US),
                    monthLabelStyle
                )
                val monthLeft = (centerX - monthLayout.size.width / 2f)
                    .coerceIn(0f, (size.width - monthLayout.size.width).coerceAtLeast(0f))
                drawText(monthLayout, topLeft = Offset(monthLeft, barAreaHeight + 2.dp.toPx()))
                // BUG fix (user-reported "bar chart should show the value of each bar"): same edge-
                // clamping HoursDetailScreen's matching value label already needed (see its own doc) -
                // an un-clamped centerX-based position let the first/last bar's label paint partly
                // outside the Canvas, which reads as "the value isn't shown" for exactly those bars.
                val valueText = if (metric == MonthlyMetric.DAYS) "${value.toInt()}$unit" else "${"%.1f".format(Locale.US, value)}$unit"
                val valueLayout = textMeasurer.measure(valueText, valueLabelStyle)
                val valueLeft = (centerX - valueLayout.size.width / 2f)
                    .coerceIn(0f, (size.width - valueLayout.size.width).coerceAtLeast(0f))
                drawText(
                    valueLayout,
                    topLeft = Offset(valueLeft, barAreaHeight + 2.dp.toPx() + monthLayout.size.height)
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

// BUG fix (ultimate-app-review Round 2, Opus, live-confirmed): pinned to Locale.US - see the doc
// comment above this file's other getDisplayName call site.
private fun monthLabel(yearMonth: YearMonth): String =
    "${yearMonth.month.getDisplayName(TextStyle.SHORT, Locale.US)} ${yearMonth.year}"
