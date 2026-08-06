package com.ssbmedia.twogether.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssbmedia.twogether.ServiceLocator
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

    val monthly = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.computeMonthlyBreakdown(sessions, lastSeenAt = proximityState.lastSeenAt)
    }

    val pageCount = if (monthly.isEmpty()) 0 else (monthly.size + WINDOW_SIZE - 1) / WINDOW_SIZE
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
                    val start = page * WINDOW_SIZE
                    val end = minOf(start + WINDOW_SIZE, monthly.size)
                    val windowData = monthly.subList(start, end)
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

@Composable
private fun MonthlyBarChart(data: List<MonthlyBreakdown>, metric: MonthlyMetric, modifier: Modifier = Modifier) {
    val values = data.map { if (metric == MonthlyMetric.DAYS) it.daysMet.toDouble() else it.hours }
    val maxVal = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val barColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val unit = if (metric == MonthlyMetric.DAYS) "d" else "h"

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            if (metric == MonthlyMetric.DAYS) "${maxVal.toInt()}d" else "${"%.1f".format(maxVal)}h",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Canvas(modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 4.dp, bottom = 4.dp)) {
            val barCount = values.size
            if (barCount == 0) return@Canvas
            val gap = 20.dp.toPx()
            val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
            values.forEachIndexed { i, value ->
                val fraction = (value / maxVal).toFloat().coerceIn(0f, 1f)
                val barHeight = (fraction * size.height).coerceAtLeast(if (value > 0) 3f else 0f)
                val left = i * (barWidth + gap)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                )
            }
            drawLine(
                color = gridColor,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.5.dp.toPx()
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            data.forEachIndexed { i, month ->
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = month.yearMonth.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val v = values[i]
                    Text(
                        text = if (metric == MonthlyMetric.DAYS) "${v.toInt()}$unit" else "${"%.1f".format(v)}$unit",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private fun monthLabel(yearMonth: YearMonth): String =
    "${yearMonth.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${yearMonth.year}"
