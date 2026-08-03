package com.ssbmedia.twogether.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.stats.Trend
import com.ssbmedia.twogether.ui.components.SectionHeader
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import com.ssbmedia.twogether.ui.components.StatCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class StatsViewModel : ViewModel() {
    val sessions = ServiceLocator.sessionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val proximityState = ServiceLocator.proximityStateStore.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.ssbmedia.twogether.data.datastore.ProximityPersistedState())
}

@Composable
fun StatsScreen(onBack: () -> Unit, onOpenBadges: () -> Unit) {
    val vm: StatsViewModel = viewModel(factory = SimpleViewModelFactory { StatsViewModel() })
    val sessions by vm.sessions.collectAsState()
    val proximityState by vm.proximityState.collectAsState()
    // lastSeenAt clamps an open session's live duration to the last confirmed sighting + absence
    // timeout - see StatsCalculator.effectiveOpenSessionCutoff's doc.
    val stats = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.compute(sessions, lastSeenAt = proximityState.lastSeenAt)
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    StatCard(
                        emoji = "💛",
                        label = "Hours together",
                        value = "${"%.1f".format(stats.totalHoursAllTime)}h",
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                    StatCard(
                        emoji = "🗓️",
                        label = "Days together",
                        value = "${stats.totalDaysTogether}",
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }
            }
            item {
                SectionHeader("Hours together")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    StatCard(emoji = "💛", label = "All-time", value = "${"%.1f".format(stats.totalHoursAllTime)}h", modifier = Modifier.weight(1f))
                    StatCard(emoji = "📆", label = "This week", value = "${"%.1f".format(stats.totalHoursThisWeek)}h", modifier = Modifier.weight(1f))
                    StatCard(emoji = "🗓️", label = "This month", value = "${"%.1f".format(stats.totalHoursThisMonth)}h", modifier = Modifier.weight(1f))
                }
            }
            item {
                SectionHeader("Streaks")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    StatCard(emoji = "🔥", label = "Daily streak (current)", value = "${stats.currentDailyStreak}d", modifier = Modifier.weight(1f))
                    StatCard(emoji = "🏆", label = "Daily streak (longest)", value = "${stats.longestDailyStreak}d", modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    StatCard(emoji = "🌟", label = "Weekly streak (current)", value = "${stats.currentWeeklyStreak}w", modifier = Modifier.weight(1f))
                    StatCard(emoji = "✨", label = "Weekly streak (longest)", value = "${stats.longestWeeklyStreak}w", modifier = Modifier.weight(1f))
                }
                Text(
                    text = "Daily streak = consecutive days together. Weekly streak is separate — consecutive weeks with any time together.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            item {
                SectionHeader("More about you two")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    StatCard(emoji = "⏱️", label = "Longest session", value = formatMinutes(stats.longestSessionMinutes), modifier = Modifier.weight(1f))
                    StatCard(emoji = "🤗", label = "Reunions", value = "${stats.reunionCount}", modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    StatCard(
                        emoji = "❤️",
                        label = "Favorite day",
                        value = stats.favoriteDayOfWeek?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                // Feature E: more stats, all read off the same merged/deduped session timeline above -
                // see StatsCalculator's doc for exactly how each one is derived.
                SectionHeader("Your story so far")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    StatCard(
                        emoji = "💞",
                        label = "Together since",
                        value = stats.togetherSince?.format(DateTimeFormatter.ofPattern("MMM d, yyyy")) ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        emoji = trendEmoji(stats.monthTrend),
                        label = "This month vs last",
                        value = trendLabel(stats.monthTrend),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    StatCard(
                        emoji = "📈",
                        label = "Most met month",
                        value = stats.mostMetMonth?.let { "${monthLabel(it.yearMonth)} (${it.value.toInt()}d)" } ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        emoji = "⏰",
                        label = "Most hours month",
                        value = stats.mostHoursMonth?.let { "${monthLabel(it.yearMonth)} (${"%.1f".format(it.value)}h)" } ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    StatCard(
                        emoji = "🌞",
                        label = "Longest single day",
                        value = stats.longestSingleDay?.let { "${"%.1f".format(it.hours)}h" } ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        emoji = "🔁",
                        label = "Avg. days between meetups",
                        value = stats.avgDaysBetweenMeetups?.let { "%.1f".format(it) } ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                }
                stats.longestSingleDay?.let {
                    Text(
                        text = "Longest day together was ${it.date.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                stats.longestApart?.let { gap ->
                    val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy")
                    val start = java.time.Instant.ofEpochMilli(gap.startMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    val end = java.time.Instant.ofEpochMilli(gap.endMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    Text(
                        text = "Longest apart: ${"%.1f".format(gap.days)} days (${start.format(fmt)} – ${end.format(fmt)}).",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
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

private fun formatMinutes(totalMinutes: Long): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun monthLabel(yearMonth: java.time.YearMonth): String =
    "${yearMonth.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${yearMonth.year}"

private fun trendEmoji(trend: Trend): String = when (trend) {
    Trend.UP -> "📈"
    Trend.DOWN -> "📉"
    Trend.FLAT -> "➡️"
}

private fun trendLabel(trend: Trend): String = when (trend) {
    Trend.UP -> "Up"
    Trend.DOWN -> "Down"
    Trend.FLAT -> "About the same"
}
