package com.ssbmedia.twogether.ui.capsules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.ssbmedia.twogether.util.DateFormats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

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
                val hours = StatsCalculator.compute(list, lastSeenAt = state.lastSeenAt, reunionCount = state.reunionCount).totalHoursAllTime.toFloat()
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

    // User-requested (supersedes the earlier "time capsule should not be delete-able, full stop, no
    // exception" decision this comment used to describe): a still-locked capsule can now be deleted -
    // see TimeCapsuleRepository.deleteIfLocked's own doc for why this is safe (only ever fires while
    // still locked, atomically, no creator restriction since this app has no per-user identity model).
    fun delete(capsule: TimeCapsule) {
        viewModelScope.launch {
            ServiceLocator.timeCapsuleRepository.deleteIfLocked(capsule)
            if (ServiceLocator.proximityStateStore.current().isTogether) {
                AppEvents.requestManualSync()
            }
        }
    }
}

@Composable
fun CapsulesScreen(onBack: () -> Unit, onOpenCalendar: (jumpToEpochDay: Long) -> Unit = {}) {
    val vm: CapsulesViewModel = viewModel(factory = SimpleViewModelFactory { CapsulesViewModel() })
    val capsules by vm.capsules.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val proximityState by vm.proximityState.collectAsState()
    // TRUE total hours (manual backfill included, same number every other screen shows) - see
    // TimeCapsuleRepository.unlockEligible's doc for how each capsule's own effective threshold (below)
    // is what keeps this un-gameable, not filtering what counts toward the total.
    val stats = remember(sessions, proximityState.lastSeenAt, proximityState.reunionCount) {
        StatsCalculator.compute(sessions, lastSeenAt = proximityState.lastSeenAt, reunionCount = proximityState.reunionCount)
    }
    val manualCredit = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.manualHoursCredit(sessions, lastSeenAt = proximityState.lastSeenAt)
    }
    var showAddDialog by remember { mutableStateOf(false) }

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
                    var showDeleteConfirm by remember(capsule.id) { mutableStateOf(false) }
                    // BUG fix (same faded-text root cause as SettingsSection/Badges - see SettingsScreen.kt's
                    // own doc): containerColor = surfaceVariant with no explicit contentColor defaulted
                    // every unstyled Text below (the "Locked"/"Unlocks at..." lines) to the muted
                    // onSurfaceVariant role.
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = if (unlocked) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Auto-adjusted threshold - see TimeCapsuleRepository.unlockEligible's doc.
                            // Grows/shrinks by exactly however much manual-hours credit has changed since
                            // this capsule was created, so it always takes the same amount of genuine
                            // together-time to unlock regardless of backfill activity. Computed once here
                            // (not duplicated per-branch) via the same shared TimeCapsuleRepository function
                            // the actual unlock decision itself uses, so this display can never disagree
                            // with reality, and so item 11's persistent timeline text below can reuse it too.
                            val effectiveThreshold = TimeCapsuleRepository.effectiveThreshold(capsule, manualCredit)
                            if (unlocked) {
                                Text("💌 Unlocked", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                Text(capsule.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                            } else {
                                // MAJOR fix (ultimate-app-review round 3, both Opus and Sonnet independently
                                // live-reproduced): this used to recompute the anti-cheat formula inline,
                                // unclamped - see TimeCapsuleRepository.effectiveThreshold's own doc for why
                                // that let a forged manualHoursAtCreation render an alarming/nonsensical
                                // negative "hours to go" here even though the real backend gate correctly
                                // kept the capsule locked. Now computed once above (shared with item 11's
                                // timeline text) via the same shared, clamped implementation, so this display
                                // can never disagree with the actual unlock decision.
                                val remaining = (effectiveThreshold - stats.totalHoursAllTime.toFloat()).coerceAtLeast(0f)
                                val delta = effectiveThreshold - capsule.unlockAtHours
                                // User-requested: deletable (either partner) only while still locked - see
                                // TimeCapsuleRepository.deleteIfLocked's own doc. Only rendered in the
                                // `!unlocked` branch, so this affordance structurally cannot appear once a
                                // capsule has unlocked - no separate visibility check needed beyond that.
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🔒 Locked", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                    IconButton(onClick = { showDeleteConfirm = true }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete capsule")
                                    }
                                }
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

                            // UX-FIX-PLAN.md Phase 2 item 11: a persistent creation/unlock timeline, shown
                            // in BOTH card states - this is the fix for the reported bug that a capsule's
                            // creation/unlock info used to blank out the moment it unlocked (the Unlocked
                            // branch above never rendered anything but the note text). Text itself comes
                            // from a plain top-level function (not inlined here) so it's unit-testable
                            // without any Compose test infra - see CapsulesScreenTest.
                            val timeline = buildCapsuleTimelineText(capsule, effectiveThreshold)
                            Text(
                                timeline.sealedLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                            timeline.openedLine?.let {
                                // UX-FIX-PLAN.md Phase 3 item 20: Time Capsule -> the day it unlocked -
                                // capsule.unlockedAt is only non-null exactly when this line renders (see
                                // buildCapsuleTimelineText's own doc), so the tap target and its data are
                                // always in sync.
                                val unlockedAt = capsule.unlockedAt
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .let { base ->
                                            if (unlockedAt != null) {
                                                base.clickable(onClick = {
                                                    val epochDay = Instant.ofEpochMilli(unlockedAt)
                                                        .atZone(ZoneId.systemDefault())
                                                        .toLocalDate()
                                                        .toEpochDay()
                                                    onOpenCalendar(epochDay)
                                                })
                                            } else base
                                        }
                                )
                            }
                        }
                    }
                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            title = { Text("Delete this time capsule?") },
                            text = { Text("This can't be undone, and will be removed for both of you once you next sync.") },
                            confirmButton = {
                                TextButton(onClick = { showDeleteConfirm = false; vm.delete(capsule) }) { Text("Delete") }
                            },
                            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddCapsuleDialog(onDismiss = { showAddDialog = false }, onAdd = { text, hours -> vm.add(text, hours); showAddDialog = false })
    }
}

// Above this, an unlock threshold stops being a remotely plausible amount of together-time to enter
// by hand - purely a guardrail against a typo (e.g. an extra digit) silently creating a capsule that
// could never realistically unlock, not a real usage limit.
//
// MINOR fix (Opus+Sonnet+Fable all independently proposed this): now sourced from
// TimeCapsuleRepository.MAX_UNLOCK_AT_HOURS, the one shared constant also used by the untrusted wire/
// backup ingestion paths - was a separate local 5000f literal here, free to drift from the other two.
private val MAX_CAPSULE_UNLOCK_HOURS = TimeCapsuleRepository.MAX_UNLOCK_AT_HOURS

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

/** UX-FIX-PLAN.md Phase 2 item 11: the two lines of persistent timeline text a capsule card shows -
 * [sealedLine] always, [openedLine] only once [TimeCapsule.unlockedAt] is set (and, unlike the old
 * Unlocked-branch UI, this keeps showing forever after - it never blanks back out). Extracted as a plain
 * function (not inlined into the Composable) purely so it's unit-testable without any Compose test
 * dependency - see CapsulesScreenTest. */
internal data class CapsuleTimelineText(val sealedLine: String, val openedLine: String?)

internal fun buildCapsuleTimelineText(
    capsule: TimeCapsule,
    effectiveThreshold: Float,
    zone: ZoneId = ZoneId.systemDefault()
): CapsuleTimelineText {
    val createdDate = Instant.ofEpochMilli(capsule.createdAt).atZone(zone).toLocalDate()
    val sealedLine = "Sealed on ${DateFormats.formatDate(createdDate)} · unlocks after ${effectiveThreshold.trimZeros()}h together"
    val openedLine = capsule.unlockedAt?.let { unlockedAt ->
        val unlockedDate = Instant.ofEpochMilli(unlockedAt).atZone(zone).toLocalDate()
        "Opened on ${DateFormats.formatDate(unlockedDate)}"
    }
    return CapsuleTimelineText(sealedLine, openedLine)
}
