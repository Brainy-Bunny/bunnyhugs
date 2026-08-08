package com.ssbmedia.twogether.ui.capsules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.data.datastore.ProximityPersistedState
import com.ssbmedia.twogether.data.db.TimeCapsule
import com.ssbmedia.twogether.data.repo.TimeCapsuleRepository
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.ui.components.EmptyState
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CapsulesViewModel : ViewModel() {
    val capsules = ServiceLocator.timeCapsuleRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sessions = ServiceLocator.sessionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    // Live proximity read (same source Home uses for its staleness check) so an open session's
    // eligibility hours can be clamped the same way - see StatsCalculator.effectiveOpenSessionEnd's
    // doc. Without this, a stale/orphaned open session (service killed while together, permission
    // revoked so it never restarts to self-heal) could inflate cumulative hours enough to permanently
    // and irreversibly unlock a capsule early, purely from elapsed wall-clock reading time.
    val proximityState = ServiceLocator.proximityStateStore.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProximityPersistedState())

    init {
        // The unlock check used to only run inside the foreground service's periodic 60s tick, so newly-
        // qualifying hours wouldn't unlock a capsule until the service happened to tick next (or at all,
        // if it wasn't running). Run it opportunistically here too, any time this screen's session or
        // proximity data loads or changes. See TimeCapsuleRepository.unlockEligible's doc for the
        // auto-adjusting-threshold anti-cheat this feeds into.
        viewModelScope.launch {
            combine(sessions, proximityState) { list, state -> list to state }.collect { (list, state) ->
                // state.lastSeenAt <= 0L means proximityState's cold DataStore-backed flow hasn't
                // produced its first real emission yet and we're still looking at the stateIn default
                // ProximityPersistedState() - see StatsCalculator.effectiveOpenSessionEnd's doc: that
                // intentionally falls back to an UNCLAMPED cutoff when lastSeenAt isn't available yet,
                // which is fine for every other (display-only, self-correcting) screen but not here,
                // since this collector performs an irreversible unlockedAt write. Skip until a genuine
                // persisted value has loaded rather than unlock off a possibly-inflated open session.
                if (list.isEmpty() || state.lastSeenAt <= 0L) return@collect
                val hours = StatsCalculator.compute(list, lastSeenAt = state.lastSeenAt).totalHoursAllTime.toFloat()
                val manualCredit = StatsCalculator.manualHoursCredit(list, lastSeenAt = state.lastSeenAt)
                ServiceLocator.timeCapsuleRepository.unlockEligible(hours, manualCredit)
            }
        }
    }

    fun add(text: String, unlockHours: Float) {
        if (text.isBlank() || unlockHours <= 0f) return
        viewModelScope.launch {
            val manualCredit = StatsCalculator.manualHoursCredit(sessions.value, lastSeenAt = proximityState.value.lastSeenAt)
            ServiceLocator.timeCapsuleRepository.add(text.trim(), unlockHours, manualCredit)
            // MAJOR fix (ultimate-app-review round 2, Sonnet): every other mutation type in the app (date
            // ideas, milestones, list items, a new photo capture) already requests an immediate sync when
            // the couple is currently together, so the change reaches the partner right away instead of
            // waiting for the next reconnect or the 15-minute catch-all - see CalendarScreen's
            // addManualSession for the same pattern. Time Capsules were the one mutation type missing this,
            // live-observed as an 8+ minute propagation delay for a capsule written while genuinely
            // together.
            if (ServiceLocator.proximityStateStore.current().isTogether) {
                AppEvents.requestManualSync()
            }
        }
    }

    fun delete(capsule: TimeCapsule) {
        viewModelScope.launch {
            ServiceLocator.timeCapsuleRepository.delete(capsule)
            // Same reasoning as add() above - don't make a capsule deletion wait for the next reconnect.
            if (ServiceLocator.proximityStateStore.current().isTogether) {
                AppEvents.requestManualSync()
            }
        }
    }
}

@Composable
fun CapsulesScreen(onBack: () -> Unit) {
    val vm: CapsulesViewModel = viewModel(factory = SimpleViewModelFactory { CapsulesViewModel() })
    val capsules by vm.capsules.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val proximityState by vm.proximityState.collectAsState()
    // TRUE total hours (manual backfill included, same number every other screen shows) - see
    // TimeCapsuleRepository.unlockEligible's doc for how each capsule's own effective threshold (below)
    // is what keeps this un-gameable, not filtering what counts toward the total.
    val stats = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.compute(sessions, lastSeenAt = proximityState.lastSeenAt)
    }
    val manualCredit = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.manualHoursCredit(sessions, lastSeenAt = proximityState.lastSeenAt)
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<TimeCapsule?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Time Capsules") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) { Icon(Icons.Filled.Add, contentDescription = "Add capsule") }
        }
    ) { padding ->
        if (capsules.isEmpty()) {
            EmptyState(
                emoji = "⏳",
                title = "No time capsules yet",
                subtitle = "Write a little note that unlocks once you've spent enough hours together.",
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(capsules, key = { it.id }) { capsule ->
                    val unlocked = capsule.unlockedAt != null
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = if (unlocked) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Box {
                        IconButton(
                            onClick = { pendingDelete = capsule },
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete capsule")
                        }
                        Column(modifier = Modifier.padding(16.dp).padding(end = 40.dp)) {
                            if (unlocked) {
                                Text("💌 Unlocked", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                Text(capsule.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                            } else {
                                // MAJOR fix (ultimate-app-review round 3, both Opus and Sonnet independently
                                // live-reproduced): this used to recompute the anti-cheat formula inline,
                                // unclamped - see TimeCapsuleRepository.effectiveThreshold's own doc for why
                                // that let a forged manualHoursAtCreation render an alarming/nonsensical
                                // negative "hours to go" here even though the real backend gate correctly
                                // kept the capsule locked. Now calls the one shared, clamped implementation,
                                // so this display can never disagree with the actual unlock decision again.
                                val effectiveThreshold = TimeCapsuleRepository.effectiveThreshold(capsule, manualCredit)
                                val remaining = (effectiveThreshold - stats.totalHoursAllTime.toFloat()).coerceAtLeast(0f)
                                val delta = effectiveThreshold - capsule.unlockAtHours
                                Text("🔒 Locked", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Unlocks at ${effectiveThreshold.trimZeros()} hours together — ${remaining.trimZeros()} to go",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                // The "lowered by" case is now structurally unreachable - effectiveThreshold
                                // can never fall below capsule.unlockAtHours (delta is clamped at >= 0 by
                                // TimeCapsuleRepository.effectiveThreshold), so only the "extra time" case
                                // remains possible.
                                if (delta > 0.01f) {
                                    Text(
                                        "Includes an extra ${delta.trimZeros()}h from backfilled time, so it can't be unlocked early.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { capsule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this time capsule?") },
            text = { Text(if (capsule.unlockedAt != null) "This will remove it for both of you once you next sync." else "It'll be gone before it ever unlocks.") },
            confirmButton = { TextButton(onClick = { vm.delete(capsule); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    if (showAddDialog) {
        AddCapsuleDialog(onDismiss = { showAddDialog = false }, onAdd = { text, hours -> vm.add(text, hours); showAddDialog = false })
    }
}

// Above this, an unlock threshold stops being a remotely plausible amount of together-time to enter
// by hand - purely a guardrail against a typo (e.g. an extra digit) silently creating a capsule that
// could never realistically unlock, not a real usage limit.
private const val MAX_CAPSULE_UNLOCK_HOURS = 5000f

@Composable
private fun AddCapsuleDialog(onDismiss: () -> Unit, onAdd: (String, Float) -> Unit) {
    var text by remember { mutableStateOf("") }
    var hoursText by remember { mutableStateOf("50") }

    // Previously Save silently no-op'd on blank/zero/unparseable input with no explanation at all - the
    // user would tap Save and nothing would visibly happen. Validate up front instead so the dialog
    // shows a clear reason and Save is disabled until the input is actually usable.
    val parsedHours = hoursText.toFloatOrNull()
    val error: String? = when {
        text.isBlank() -> "Write a note for this capsule"
        hoursText.isBlank() -> "Enter how many hours together this unlocks at"
        parsedHours == null -> "Enter a valid number of hours"
        parsedHours <= 0f -> "Must be greater than zero hours"
        parsedHours > MAX_CAPSULE_UNLOCK_HOURS -> "That's a lot of hours - keep it under ${MAX_CAPSULE_UNLOCK_HOURS.toInt()}"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New time capsule") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("Write your note…") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = hoursText,
                    onValueChange = { hoursText = it.filter { c -> c.isDigit() || c == '.' } },
                    placeholder = { Text("Unlocks at how many hours together?") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = error == null, onClick = { onAdd(text, parsedHours ?: 0f) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun Float.trimZeros(): String {
    return if (this == this.toInt().toFloat()) this.toInt().toString() else "%.1f".format(this)
}
