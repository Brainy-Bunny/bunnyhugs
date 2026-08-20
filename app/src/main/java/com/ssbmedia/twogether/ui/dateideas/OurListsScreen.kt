package com.ssbmedia.twogether.ui.dateideas

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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.data.db.DEFAULT_LIST_ID
import com.ssbmedia.twogether.data.db.DateIdea
import com.ssbmedia.twogether.data.db.ListCategory
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.ui.components.EmptyState
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import com.ssbmedia.twogether.util.RelativeTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "Our Lists": what used to be one flat global "Date Ideas" checklist is now any number of named,
 * couple-shared lists (Date Ideas, Movie Watchlist, ...) - see ListCategory's own doc. Every existing
 * Date Ideas behavior (add/check-off/delete an idea, debounced auto-sync, Completed-section collapse)
 * is unchanged, just scoped per-list via DateIdea.listId instead of being global.
 */
class OurListsViewModel : ViewModel() {
    val lists = ServiceLocator.listCategoryRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val ideas = ServiceLocator.dateIdeaRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Opportunistic self-heal, same reasoning as CapsulesViewModel's own init collector: the sync-
        // time sweep in GattSyncManager.applyPayload (via ListCategoryRepository.mergeRemoteWithIdeas)
        // already catches a newly-orphaned idea the moment it arrives, but this also catches anything
        // that became orphaned BEFORE that sweep existed, or from a legacy build, simply by opening this
        // screen, rather than requiring a fresh sync to ever notice it.
        //
        // BLOCKER fix, round 2: this used to derive "the current valid list ids" from
        // combine(lists, ideas)'s own snapshot and pass it into reassignOrphans() as a caller-supplied
        // set - but lists/ideas are two SEPARATE Room Flows that don't update in lockstep with each
        // other even when the underlying write that changed both was atomic (see
        // ListCategoryRepository.reassignOrphanIdeas' doc for the full reasoning), so that snapshot could
        // itself be transiently inconsistent and cause exactly the corruption this mechanism exists to
        // prevent. A single one-shot call to the no-arg self-heal entry point on screen open - which
        // reads both tables fresh, itself, at the moment it runs - has no such risk, and is all this
        // "catch legacy corruption" backstop was ever meant to do; ongoing/future corruption from a live
        // sync is already handled atomically by the GattSyncManager path.
        viewModelScope.launch {
            ServiceLocator.listCategoryRepository.reassignOrphanIdeas()
        }
    }

    // Feature B: debounces an auto-sync request so a burst of quick actions (checking off several
    // ideas in a row, etc) fires one GATT round-trip after things settle rather than one per action.
    // Cancelling+relaunching on every call is what gives the debounce - only the LAST action within the
    // window actually triggers a request.
    private var autoSyncJob: Job? = null

    private fun scheduleAutoSyncIfTogether() {
        autoSyncJob?.cancel()
        autoSyncJob = viewModelScope.launch {
            delay(AUTO_SYNC_DEBOUNCE_MILLIS)
            // Only worth requesting while genuinely together right now - the service's own manual-sync
            // collector already no-ops (and reports failure) when apart, but checking here avoids even
            // emitting a doomed request and flashing a spurious "Couldn't sync" message from an action
            // taken while apart (e.g. reviewing/pruning the list solo).
            if (ServiceLocator.proximityStateStore.current().isTogether) {
                AppEvents.requestManualSync()
            }
        }
    }

    fun addList(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { ServiceLocator.listCategoryRepository.add(name.trim()) }
        scheduleAutoSyncIfTogether()
    }

    fun deleteList(category: ListCategory) {
        viewModelScope.launch { ServiceLocator.listCategoryRepository.delete(category) }
        scheduleAutoSyncIfTogether()
    }

    /** UX-FIX-PLAN.md Phase 3 item 19: renames a list - see ListCategoryRepository.rename's own doc. */
    fun renameList(category: ListCategory, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { ServiceLocator.listCategoryRepository.rename(category, name.trim()) }
        scheduleAutoSyncIfTogether()
    }

    fun add(text: String, listId: String) {
        if (text.isBlank()) return
        viewModelScope.launch { ServiceLocator.dateIdeaRepository.add(text.trim(), listId) }
        scheduleAutoSyncIfTogether()
    }

    fun toggleDone(idea: DateIdea) {
        viewModelScope.launch { ServiceLocator.dateIdeaRepository.setDone(idea, !idea.done) }
        scheduleAutoSyncIfTogether()
    }

    fun delete(idea: DateIdea) {
        viewModelScope.launch { ServiceLocator.dateIdeaRepository.softDelete(idea) }
        scheduleAutoSyncIfTogether()
    }

    /** UX-FIX-PLAN.md Phase 3 item 19: renames an idea's text - see DateIdeaRepository.rename's own doc. */
    fun renameIdea(idea: DateIdea, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { ServiceLocator.dateIdeaRepository.rename(idea, text.trim()) }
        scheduleAutoSyncIfTogether()
    }

    /** Asks the proximity service (which owns the live BLE/GATT connection) to sync right now. */
    fun syncNow() {
        AppEvents.requestManualSync()
    }

    companion object {
        private const val AUTO_SYNC_DEBOUNCE_MILLIS = 1_500L
    }
}

@Composable
fun OurListsScreen(onBack: () -> Unit) {
    val vm: OurListsViewModel = viewModel(factory = SimpleViewModelFactory { OurListsViewModel() })
    val lists by vm.lists.collectAsState()
    val ideas by vm.ideas.collectAsState()
    var lastSyncAt by remember { mutableStateOf(0L) }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    // True once this manual sync attempt has resolved to us holding the passive GATT server role (see
    // AppEvents.syncListening) - suppresses the generic timeout-driven failure message below, since a
    // server can't force an on-demand result and "nothing happened yet" doesn't mean anything is broken.
    var isListeningRole by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DateIdea?>(null) }
    var pendingDeleteList by remember { mutableStateOf<ListCategory?>(null) }
    var showAddListDialog by remember { mutableStateOf(false) }
    // UX-FIX-PLAN.md Phase 3 item 19: rename affordances for both a whole list and a single idea.
    var pendingRenameIdea by remember { mutableStateOf<DateIdea?>(null) }
    var pendingRenameList by remember { mutableStateOf<ListCategory?>(null) }
    // Per-list UI state, keyed by ListCategory.id - each card's own expansion and its own independent
    // "show completed" toggle, exactly mirroring the single global `showCompleted` the old flat screen
    // had, just one map entry per list instead of one screen-wide bool.
    val expandedListIds = remember { mutableStateOf(setOf<String>()) }
    val showCompletedListIds = remember { mutableStateOf(setOf<String>()) }
    val coroutineScope = rememberCoroutineScope()
    // Phase 1 item 2 of UX-FIX-PLAN.md: "Last synced Xm ago" used to be computed once at composition
    // time with no ticker, so it visibly froze the whole time this screen stayed open - matches
    // HomeScreen's own 30s ticker pattern.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        lastSyncAt = ServiceLocator.settingsStore.current().lastSyncAt
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        AppEvents.syncListening.collect {
            isListeningRole = true
            syncing = false
            syncMessage = "Listening for your partner's phone…"
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        AppEvents.syncCompleted.collect { success ->
            syncing = false
            isListeningRole = false
            // MAJOR fix (ultimate-app-review, Fable F-4, assertion 6): a merge can succeed while still
            // having dropped some incoming rows as implausible (even after the clock-skew correction, a
            // row can still be genuinely implausible) - say so instead of an unqualified "Synced!" that
            // would hide real, silent data loss from the person who'd want to know their partner's
            // clock might be off.
            val dropped = AppEvents.lastSyncDroppedCount.value
            // MAJOR fix (ultimate-app-review round 1, Opus+Sonnet): both independently live-reproduced a
            // permanent sync lockout after a partner reinstall, with the generic "make sure you're
            // together" message actively misleading the user (the devices really were together and really
            // did connect) - see GattSyncManager.lastSyncFailedDueToPartnerMismatch's doc. This is the one
            // failure reason worth distinguishing here, since it's the one with an actual fix the user can
            // take (every other failure reason - not together, timeout - really is just "try again later").
            syncMessage = when {
                !success && AppEvents.lastSyncFailedDueToPartnerMismatch.value ->
                    "Couldn't sync — this phone doesn't match your paired partner. Unpair, then create a new pairing code to reconnect."
                !success -> "Couldn't sync — make sure you're together"
                dropped > 0 -> "Synced, but $dropped item${if (dropped == 1) "" else "s"} skipped — check both phones' clocks"
                else -> "Synced! 💛"
            }
            if (success) lastSyncAt = ServiceLocator.settingsStore.current().lastSyncAt
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Our Lists") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddListDialog = true }) { Icon(Icons.Filled.Add, contentDescription = "New list") }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = syncMessage
                        ?: if (lastSyncAt > 0) "Last synced ${RelativeTime.relativeAgo((now - lastSyncAt).coerceAtLeast(0L))}" else "Not synced yet",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = {
                        syncing = true
                        isListeningRole = false
                        syncMessage = null
                        vm.syncNow()
                        // Belt-and-suspenders: whatever the underlying cause (foreground service not
                        // running at all so AppEvents.requestManualSync() has no collector, a GATT
                        // connection that drops silently, etc), the button must never stay stuck
                        // "Syncing…" forever. If no syncCompleted event has arrived within a few
                        // seconds, resolve to a visible failure ourselves - UNLESS this attempt already
                        // resolved to us being the passive GATT server (isListeningRole), in which case
                        // "nothing happened yet" is expected/honest behavior, not a failure.
                        coroutineScope.launch {
                            delay(8_000)
                            if (syncing && !isListeningRole) {
                                syncing = false
                                syncMessage = "Couldn't sync — make sure you're together"
                            }
                        }
                    },
                    enabled = !syncing
                ) { Text(if (syncing) "Syncing…" else "Sync now") }
            }

            if (lists.isEmpty()) {
                // Shouldn't normally happen post-migration since the default "Date Ideas" list always
                // exists - defensive only (e.g. a mid-sync race where the local list_categories row hasn't
                // landed yet).
                EmptyState(
                    emoji = "💌",
                    title = "No lists yet",
                    subtitle = "Tap the + button to start your first list — it'll sync with your partner's phone automatically when you're together.",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(lists, key = { it.id }) { list ->
                        val listIdeas = remember(ideas, list.id) { ideas.filter { it.listId == list.id } }
                        val activeIdeas = remember(listIdeas) { listIdeas.filter { !it.done } }
                        val completedIdeas = remember(listIdeas) { listIdeas.filter { it.done } }
                        val isExpanded = list.id in expandedListIds.value
                        val showCompleted = list.id in showCompletedListIds.value

                        ListCategoryCard(
                            list = list,
                            activeCount = activeIdeas.size,
                            expanded = isExpanded,
                            onToggleExpanded = {
                                expandedListIds.value = if (isExpanded) {
                                    expandedListIds.value - list.id
                                } else {
                                    expandedListIds.value + list.id
                                }
                            },
                            onDeleteClick = { pendingDeleteList = list },
                            onRenameClick = { pendingRenameList = list },
                            onAddIdea = { text -> vm.add(text, list.id) },
                            activeIdeas = activeIdeas,
                            completedIdeas = completedIdeas,
                            showCompleted = showCompleted,
                            onToggleShowCompleted = {
                                showCompletedListIds.value = if (showCompleted) {
                                    showCompletedListIds.value - list.id
                                } else {
                                    showCompletedListIds.value + list.id
                                }
                            },
                            onToggleIdea = { idea -> vm.toggleDone(idea) },
                            onDeleteIdea = { idea -> pendingDelete = idea },
                            onRenameIdea = { idea -> pendingRenameIdea = idea }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { idea ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this idea?") },
            text = { Text("\"${idea.text}\" will be removed for both of you once you next sync.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(idea); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    pendingDeleteList?.let { list ->
        AlertDialog(
            onDismissRequest = { pendingDeleteList = null },
            title = { Text("Delete this list?") },
            text = { Text("\"${list.name}\" and everything in it will be removed for both of you once you next sync.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteList(list); pendingDeleteList = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteList = null }) { Text("Cancel") } }
        )
    }

    if (showAddListDialog) {
        AddListDialog(
            onDismiss = { showAddListDialog = false },
            onAdd = { name -> vm.addList(name); showAddListDialog = false }
        )
    }

    // UX-FIX-PLAN.md Phase 3 item 19: rename dialogs, both sharing the same generic RenameDialog.
    pendingRenameList?.let { list ->
        RenameDialog(
            title = "Rename list",
            initialText = list.name,
            onDismiss = { pendingRenameList = null },
            onSave = { name -> vm.renameList(list, name); pendingRenameList = null }
        )
    }
    pendingRenameIdea?.let { idea ->
        RenameDialog(
            title = "Rename idea",
            initialText = idea.text,
            onDismiss = { pendingRenameIdea = null },
            onSave = { text -> vm.renameIdea(idea, text); pendingRenameIdea = null }
        )
    }
}

/** UX-FIX-PLAN.md Phase 3 item 19: a plain single-text-field rename dialog, shared by both a whole list
 * and an individual idea (the only difference between the two is the title and which repository call the
 * caller wires [onSave] to). */
@Composable
private fun RenameDialog(title: String, initialText: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSave(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * One list, collapsed by default (just the name + an active-idea-count hint + a delete icon + a
 * chevron), expanding in place to reveal the same add-idea row / active-idea list / collapsible
 * Completed section the old flat DateIdeasScreen had, now scoped to just this list.
 */
@Composable
private fun ListCategoryCard(
    list: ListCategory,
    activeCount: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDeleteClick: () -> Unit,
    onRenameClick: () -> Unit,
    onAddIdea: (String) -> Unit,
    activeIdeas: List<DateIdea>,
    completedIdeas: List<DateIdea>,
    showCompleted: Boolean,
    onToggleShowCompleted: () -> Unit,
    onToggleIdea: (DateIdea) -> Unit,
    onDeleteIdea: (DateIdea) -> Unit,
    onRenameIdea: (DateIdea) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(list.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (activeCount == 1) "1 idea" else "$activeCount ideas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // UX-FIX-PLAN.md Phase 3 item 19: renaming is allowed for every list, INCLUDING the
                // default one - only its delete tombstone is specially blocked (see
                // ListCategoryRepository.rename's own doc, "Any NON-delete update to it still applies
                // normally").
                IconButton(onClick = onRenameClick) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename list")
                }
                // The default "Date Ideas" list is the permanent fallback DateIdeaRepository.reassignOrphans
                // relies on always existing (see its doc) - deleting it would let a future orphaned idea get
                // reassigned into a list that itself doesn't resolve, reproducing the exact "invisible
                // forever" bug that mechanism exists to prevent. No delete affordance for it at all, rather
                // than a tap that would silently no-op against ListCategoryRepository.delete's own guard.
                if (list.id != DEFAULT_LIST_ID) {
                    IconButton(onClick = onDeleteClick) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete list")
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }

            if (expanded) {
                var text by remember(list.id) { mutableStateOf("") }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("Add an idea… 🍿") },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.large
                    )
                    TextButton(onClick = {
                        onAddIdea(text)
                        text = ""
                    }) { Text("Add") }
                }

                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    if (activeIdeas.isEmpty()) {
                        Text(
                            "Nothing active — add a new idea above, or check Completed below 🎉",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        activeIdeas.forEach { idea ->
                            DateIdeaRow(
                                idea = idea,
                                onToggle = { onToggleIdea(idea) },
                                onDelete = { onDeleteIdea(idea) },
                                onRename = { onRenameIdea(idea) },
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }

                    if (completedIdeas.isNotEmpty()) {
                        // Feature C: completed items collapse into a "Completed (N)" section, collapsed
                        // by default, so a long-lived couple's growing pile of finished ideas doesn't
                        // push the still-relevant active ones off screen - now per-list, so each list's
                        // Completed section expands/collapses independently of every other list's.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .clickable(onClick = onToggleShowCompleted),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Completed (${completedIdeas.size})",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Icon(
                                imageVector = if (showCompleted) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (showCompleted) "Collapse" else "Expand"
                            )
                        }
                        if (showCompleted) {
                            completedIdeas.forEach { idea ->
                                DateIdeaRow(
                                    idea = idea,
                                    onToggle = { onToggleIdea(idea) },
                                    onDelete = { onDeleteIdea(idea) },
                                    onRename = { onRenameIdea(idea) },
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DateIdeaRow(
    idea: DateIdea,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (idea.done) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Checkbox(checked = idea.done, onCheckedChange = { onToggle() })
                Text(
                    text = idea.text,
                    textDecoration = if (idea.done) TextDecoration.LineThrough else null,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            // UX-FIX-PLAN.md Phase 3 item 19: rename affordance alongside the existing delete one.
            IconButton(onClick = onRename) {
                Icon(Icons.Filled.Edit, contentDescription = "Rename idea")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun AddListDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New list") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("List name…") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onAdd(name) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
