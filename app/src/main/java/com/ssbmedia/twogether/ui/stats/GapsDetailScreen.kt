package com.ssbmedia.twogether.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.stats.GapInfo
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.ui.components.EmptyState
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import com.ssbmedia.twogether.util.DateFormats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId

class GapsDetailViewModel : ViewModel() {
    val sessions = ServiceLocator.sessionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val proximityState = ServiceLocator.proximityStateStore.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.ssbmedia.twogether.data.datastore.ProximityPersistedState())
}

/**
 * Feature 1: "Longest apart" / "Avg. days between meetups" card drill-down. Reuses
 * [StatsCalculator.computeMeetupGaps] (the exact same distinct-together-day gap list
 * [TogetherStats.longestApart] and [TogetherStats.avgDaysBetweenMeetups] are already derived from) to
 * list EVERY gap, not just the single longest one - sorted longest-first so the most notable gaps are
 * immediately visible, with the single longest one visually called out.
 */
@Composable
fun GapsDetailScreen(onBack: () -> Unit) {
    val vm: GapsDetailViewModel = viewModel(factory = SimpleViewModelFactory { GapsDetailViewModel() })
    val sessions by vm.sessions.collectAsState()
    val proximityState by vm.proximityState.collectAsState()
    val zone = remember { ZoneId.systemDefault() }

    val gaps = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.computeMeetupGaps(sessions, zone = zone, lastSeenAt = proximityState.lastSeenAt)
            .sortedByDescending { it.days }
    }
    val avgDays = remember(gaps) { if (gaps.isNotEmpty()) gaps.map { it.days }.average() else null }
    val longest = gaps.firstOrNull()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Time apart") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (gaps.isEmpty()) {
            EmptyState(
                emoji = "🔁",
                title = "Not enough meetups yet",
                subtitle = "Once you've had a couple of separate meetups, the gaps between them will show up here.",
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Average time apart", style = MaterialTheme.typography.bodySmall)
                            Text(
                                avgDays?.let { "%.1f days".format(it) } ?: "—",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                items(gaps) { gap ->
                    GapRow(gap = gap, zone = zone, isLongest = gap === longest)
                }
            }
        }
    }
}

@Composable
private fun GapRow(gap: GapInfo, zone: ZoneId, isLongest: Boolean) {
    val start = Instant.ofEpochMilli(gap.startMillis).atZone(zone).toLocalDate()
    val end = Instant.ofEpochMilli(gap.endMillis).atZone(zone).toLocalDate()

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isLongest) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isLongest) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                if (isLongest) {
                    Text(
                        "LONGEST",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Text(
                    "${DateFormats.formatDate(start)} – ${DateFormats.formatDate(end)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "${"%.1f".format(gap.days)}d",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
