package com.ssbmedia.twogether.ui.manual

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.data.datastore.AppSettings
import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.ui.calendar.AddManualSessionDialog
import com.ssbmedia.twogether.ui.components.EmptyState
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import com.ssbmedia.twogether.util.DateFormats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Manager for all manually-backfilled sessions, reached from Settings (user request). Everything here is
 * the SAME `isManual` [TogetherSession] rows the Calendar day-detail already creates/edits/deletes - this
 * screen just gathers every one of them into a single, always-visible list so a wrong entry can be spotted
 * and fixed. All of it is already synced: a manual row inserts/edits/tombstones propagate to the partner
 * via GattSyncManager (see SessionRepository.mergeRemoteSessions), so either phone can review and correct
 * the shared history here. BLE-detected sessions deliberately never appear (they are an untouchable
 * automatic record); only hand-entered ones are listed and editable.
 */
class ManualEntriesViewModel : ViewModel() {
    /** Newest-first so the most recently added/edited entry is right at the top while backfilling. Only
     * manual rows - observeAll already excludes soft-deleted (tombstoned) ones. */
    val entries = ServiceLocator.sessionRepository.observeAll()
        .map { list -> list.filter { it.isManual }.sortedByDescending { it.startedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Mirrors CalendarViewModel.addManualSession exactly - validation is done in AddManualSessionDialog
     * before this runs, and a sync is requested immediately if we're together right now so a backfill (or
     * a correction) reaches the partner without waiting for the next reconnect or the 15-minute catch-all. */
    fun add(startedAt: Long, endedAt: Long) {
        viewModelScope.launch {
            ServiceLocator.sessionRepository.addManualSession(startedAt, endedAt)
            if (ServiceLocator.proximityStateStore.current().isTogether) AppEvents.requestManualSync()
        }
    }

    fun update(session: TogetherSession, startedAt: Long, endedAt: Long) {
        viewModelScope.launch {
            ServiceLocator.sessionRepository.updateManualSession(session, startedAt, endedAt)
            if (ServiceLocator.proximityStateStore.current().isTogether) AppEvents.requestManualSync()
        }
    }

    fun delete(session: TogetherSession) {
        viewModelScope.launch {
            ServiceLocator.sessionRepository.softDeleteManual(session)
            if (ServiceLocator.proximityStateStore.current().isTogether) AppEvents.requestManualSync()
        }
    }
}

@Composable
fun ManualEntriesScreen(onBack: () -> Unit) {
    val vm: ManualEntriesViewModel = viewModel(factory = SimpleViewModelFactory { ManualEntriesViewModel() })
    val entries by vm.entries.collectAsState()
    val settings by ServiceLocator.settingsStore.settings.collectAsState(initial = AppSettings())
    val zone = remember { ZoneId.systemDefault() }

    // null = no dialog. showAdd opens a fresh add dialog (bulk-capable); editing opens the edit dialog for
    // that specific row; pendingDelete drives the delete-confirm dialog.
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TogetherSession?>(null) }
    var pendingDelete by remember { mutableStateOf<TogetherSession?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Manual entries") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add entry") }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(
                emoji = "✍️",
                title = "No manual entries yet",
                subtitle = "Add past time together the app didn't track: before you installed it, a day it missed, or an overnight. They'll all be listed here for you and your partner to review.",
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        "${entries.size} manual ${if (entries.size == 1) "entry" else "entries"}. Tap one to edit, or delete a wrong one. Shared with your partner.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(entries, key = { it.id }) { session ->
                    ManualEntryCard(
                        session = session,
                        zone = zone,
                        onEdit = { editing = session },
                        onDelete = { pendingDelete = session }
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddManualSessionDialog(
            zone = zone,
            dayStartHour = settings.dayStartHour,
            onDismiss = { showAdd = false },
            onSave = { start, end -> vm.add(start, end); showAdd = false },
            // Bulk: persist and keep the dialog open for the next entry (dialog resets its own times).
            onSaveAnother = { start, end -> vm.add(start, end) }
        )
    }

    editing?.let { session ->
        AddManualSessionDialog(
            zone = zone,
            dayStartHour = settings.dayStartHour,
            existing = session,
            onDismiss = { editing = null },
            onSave = { start, end -> vm.update(session, start, end); editing = null }
        )
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this entry?") },
            text = { Text("This removes the manual entry for both you and your partner. It won't affect any BLE-detected time together.") },
            confirmButton = { TextButton(onClick = { vm.delete(session); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ManualEntryCard(
    session: TogetherSession,
    zone: ZoneId,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(formatSpan(session, zone), style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatDuration(session),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit this entry")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete this entry")
            }
        }
    }
}

/** "29th September 2026, 7:00 PM - 9:30 PM", or when it crosses into another date,
 * "11th October 2026, 11:04 AM -> 12th October 2026, 12:49 PM" so an overnight reads unambiguously. */
private fun formatSpan(session: TogetherSession, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(session.startedAt).atZone(zone)
    val end = Instant.ofEpochMilli(session.endedAt ?: session.startedAt).atZone(zone)
    val startStr = "${DateFormats.formatDateLong(start.toLocalDate())}, ${DateFormats.formatTime(start.toLocalTime())}"
    return if (start.toLocalDate() == end.toLocalDate()) {
        "$startStr – ${DateFormats.formatTime(end.toLocalTime())}"
    } else {
        "$startStr → ${DateFormats.formatDateLong(end.toLocalDate())}, ${DateFormats.formatTime(end.toLocalTime())}"
    }
}

private fun formatDuration(session: TogetherSession): String {
    val minutes = ((session.endedAt ?: session.startedAt) - session.startedAt) / 60_000L
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m together"
        h > 0 -> "${h}h together"
        else -> "${m}m together"
    }
}
