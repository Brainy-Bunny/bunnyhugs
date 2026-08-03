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
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.ui.components.EmptyState
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val WINDOW_SIZE = 14

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

    val pageCount = if (dailyHours.isEmpty()) 0 else (dailyHours.size + WINDOW_SIZE - 1) / WINDOW_SIZE
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
                    val start = page * WINDOW_SIZE
                    val end = minOf(start + WINDOW_SIZE, dailyHours.size)
                    val windowData = dailyHours.subList(start, end)
                    Column {
                        val rangeLabel = if (windowData.size > 1) {
                            "${windowData.first().first.format(DateTimeFormatter.ofPattern("MMM d"))} – " +
                                windowData.last().first.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                        } else {
                            windowData.first().first.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                        }
                        Text(rangeLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        DailyHoursBarChart(windowData, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyHoursBarChart(data: List<Pair<LocalDate, Double>>, modifier: Modifier = Modifier) {
    val maxVal = (data.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(1.0)
    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "${"%.1f".format(maxVal)}h",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Canvas(modifier = Modifier.fillMaxWidth().height(200.dp).padding(top = 4.dp, bottom = 4.dp)) {
            val barCount = data.size
            if (barCount == 0) return@Canvas
            val gap = 6.dp.toPx()
            val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
            data.forEachIndexed { i, (_, hours) ->
                val fraction = (hours / maxVal).toFloat().coerceIn(0f, 1f)
                val barHeight = (fraction * size.height).coerceAtLeast(if (hours > 0) 3f else 0f)
                val left = i * (barWidth + gap)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
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
            data.forEach { (date, _) ->
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
