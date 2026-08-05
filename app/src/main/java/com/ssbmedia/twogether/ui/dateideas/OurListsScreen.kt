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
import com.ssbmedia.twogether.data.db.DateIdea
import com.ssbmedia.twogether.data.db.ListCategory
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.ui.components.EmptyState
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
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
    // Per-list UI state, keyed by ListCategory.id - each card's own expansion and its own independent
    // "show completed" toggle, exactly mirroring the single global `showCompleted` the old flat screen
    // had, just one map entry per list instead of one screen-wide bool.
    val expandedListIds = remember { mutableStateOf(setOf<String>()) }
    val showCompletedListIds = remember { mutableStateOf(setOf<String>()) }
    val coroutineScope = rememberCoroutineScope()

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
            syncMessage = if (success) "Synced! 💛" else "Couldn't sync — make sure you're together"
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
                        ?: if (lastSyncAt > 0) "Last synced ${minutesAgo(lastSyncAt)}m ago" else "Not synced yet",
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
                            onDeleteIdea = { idea -> pendingDelete = idea }
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
    onAddIdea: (String) -> Unit,
    activeIdeas: List<DateIdea>,
    completedIdeas: List<DateIdea>,
    showCompleted: Boolean,
    onToggleShowCompleted: () -> Unit,
    onToggleIdea: (DateIdea) -> Unit,
    onDeleteIdea: (DateIdea) -> Unit
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
                IconButton(onClick = onDeleteClick) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete list")
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
private fun DateIdeaRow(idea: DateIdea, onToggle: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
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

private fun minutesAgo(pastMillis: Long): Long = ((System.currentTimeMillis() - pastMillis) / 60000L).coerceAtLeast(0)
