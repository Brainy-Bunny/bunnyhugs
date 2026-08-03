package com.ssbmedia.twogether.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
    val zone = remember { ZoneId.systemDefault() }

    val countsByWeekday = remember(sessions, proximityState.lastSeenAt) {
        val qualifyingDays = StatsCalculator.buildDailyMinuteMap(sessions, zone = zone, lastSeenAt = proximityState.lastSeenAt).keys
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

@Composable
private fun WeekdayBarChart(data: List<Pair<DayOfWeek, Int>>, modifier: Modifier = Modifier) {
    val maxVal = (data.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    val bestColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val bestDay = data.maxByOrNull { it.second }?.first

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(240.dp).padding(bottom = 4.dp)) {
            val barCount = data.size
            if (barCount == 0) return@Canvas
            val gap = 16.dp.toPx()
            val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
            data.forEachIndexed { i, (day, count) ->
                val fraction = count.toFloat() / maxVal.toFloat()
                val barHeight = (fraction * size.height).coerceAtLeast(if (count > 0) 3f else 0f)
                val left = i * (barWidth + gap)
                drawRoundRect(
                    color = if (day == bestDay && count > 0) bestColor else barColor,
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
            data.forEach { (day, count) ->
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = day.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (day == bestDay) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
