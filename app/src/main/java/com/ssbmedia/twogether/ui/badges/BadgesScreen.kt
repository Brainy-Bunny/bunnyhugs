package com.ssbmedia.twogether.ui.badges

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.badges.BadgeCatalog
import com.ssbmedia.twogether.data.datastore.ProximityPersistedState
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

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
fun BadgesScreen(onBack: () -> Unit) {
    val vm: BadgesViewModel = viewModel(factory = SimpleViewModelFactory { BadgesViewModel() })
    val sessions by vm.sessions.collectAsState()
    val proximityState by vm.proximityState.collectAsState()
    val unlockDates by vm.unlockDates.collectAsState()
    // lastSeenAt clamps an open session's live duration so badge progress can't be inflated by a
    // stale/orphaned open session - see StatsCalculator.effectiveOpenSessionCutoff's doc.
    val stats = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.compute(sessions, lastSeenAt = proximityState.lastSeenAt)
    }
    val statuses = remember(stats) { BadgeCatalog.statuses(stats) }

    LaunchedEffect(statuses) {
        vm.recordNewlyUnlocked(statuses.filter { it.unlocked }.map { it.badge.id })
    }

    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Badges") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(statuses, key = { it.badge.id }) { status ->
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = if (status.unlocked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
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
                                Text(
                                    text = "on ${dateFormat.format(Date(unlockedAt))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
