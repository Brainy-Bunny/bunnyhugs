package com.ssbmedia.twogether.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.ui.components.EmptyState
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

class FavoriteDayDetailViewModel : ViewModel() {
    val sessions = ServiceLocator.sessionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val proximityState = ServiceLocator.proximityStateStore.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.ssbmedia.twogether.data.datastore.ProximityPersistedState())
}

/**
 * Feature 1: "Favorite day" card drill-down. Unlike [TogetherStats.favoriteDayOfWeek] (which the Stats
 * screen derives by summing MINUTES per weekday), this screen answers "why" by showing the COUNT OF
 * DISTINCT MEETUP-DAYS that fell on each weekday - reusing the same qualifying-day key set
 * [StatsCalculator.buildDailyMinuteMap] already produces (so it can never disagree with Calendar/Stats
 * about which days qualify), just grouped and counted differently here.
 */
@Composable
fun FavoriteDayDetailScreen(onBack: () -> Unit) {
    val vm: FavoriteDayDetailViewModel = viewModel(factory = SimpleViewModelFactory { FavoriteDayDetailViewModel() })
    val sessions by vm.sessions.collectAsState()
    val proximityState by vm.proximityState.collectAsState()
    val settings by ServiceLocator.settingsStore.settings.collectAsState(initial = AppSettings())
    val zone = remember { ZoneId.systemDefault() }

    val countsByWeekday = remember(sessions, proximityState.lastSeenAt, settings.dayStartHour) {
        val qualifyingDays = StatsCalculator.buildDailyMinuteMap(
            sessions, zone = zone, lastSeenAt = proximityState.lastSeenAt, dayStartHour = settings.dayStartHour
        ).keys
        val weekdayOrder = listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
        )
        val grouped = qualifyingDays.groupingBy { it.dayOfWeek }.eachCount()
        weekdayOrder.map { it to (grouped[it] ?: 0) }
    }

    val hasAnyData = countsByWeekday.any { it.second > 0 }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Favorite day") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (!hasAnyData) {
            EmptyState(
                emoji = "❤️",
                title = "No meetups yet",
                subtitle = "Once you've spent time together, we'll show which day of the week you meet up most.",
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text(
                    "How many times you've met up on each day of the week.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                WeekdayBarChart(countsByWeekday)
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
private fun WeekdayBarChart(data: List<Pair<DayOfWeek, Int>>, modifier: Modifier = Modifier) {
    val maxVal = (data.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    val bestColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val bestDay = data.maxByOrNull { it.second }?.first
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    // Hoisted out of the Canvas draw block below: MaterialTheme.typography is a @Composable getter and
    // can't be read from inside Canvas's onDraw lambda (a plain DrawScope, not composable context) -
    // .copy() on an already-resolved TextStyle is a regular function call, so that part stays inside.
    val dayLabelBaseStyle = MaterialTheme.typography.labelMedium.copy(color = labelColor, textAlign = TextAlign.Center)
    val countLabelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor, textAlign = TextAlign.Center)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(280.dp).padding(bottom = 4.dp)) {
            val barCount = data.size
            if (barCount == 0) return@Canvas
            val gap = 16.dp.toPx()
            val labelAreaHeight = 42.dp.toPx()
            val barAreaHeight = (size.height - labelAreaHeight).coerceAtLeast(0f)
            val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
            data.forEachIndexed { i, (day, count) ->
                val fraction = count.toFloat() / maxVal.toFloat()
                val barHeight = (fraction * barAreaHeight).coerceAtLeast(if (count > 0) 3f else 0f)
                val left = i * (barWidth + gap)
                val centerX = left + barWidth / 2f
                val isBest = day == bestDay
                drawRoundRect(
                    color = if (isBest && count > 0) bestColor else barColor,
                    topLeft = Offset(left, barAreaHeight - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                )
                val dayLabelStyle = dayLabelBaseStyle.copy(fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal)
                // BUG fix (ultimate-app-review Round 2, Opus, live-confirmed): pinned to Locale.US - see
                // StatsScreen.kt's monthLabel doc for the cross-screen inconsistency this exact pattern
                // caused (this screen's own chart was the "lun." half of that live-observed mismatch).
                val dayLayout = textMeasurer.measure(day.getDisplayName(TextStyle.SHORT, Locale.US), dayLabelStyle)
                // BUG fix (user-requested, same class as HoursDetailScreen/MonthlyDetailScreen's matching
                // fix): un-clamped centerX-based positioning let the first/last bar's label paint partly
                // outside the Canvas - applying the same edge-clamp to every bar chart in this app, not
                // just the two that already had a live-reported symptom.
                val dayLeft = (centerX - dayLayout.size.width / 2f).coerceIn(0f, (size.width - dayLayout.size.width).coerceAtLeast(0f))
                drawText(dayLayout, topLeft = Offset(dayLeft, barAreaHeight + 2.dp.toPx()))
                val countLayout = textMeasurer.measure("$count", countLabelStyle)
                val countLeft = (centerX - countLayout.size.width / 2f).coerceIn(0f, (size.width - countLayout.size.width).coerceAtLeast(0f))
                drawText(
                    countLayout,
                    topLeft = Offset(countLeft, barAreaHeight + 2.dp.toPx() + dayLayout.size.height)
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
