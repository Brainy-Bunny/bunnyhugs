package com.ssbmedia.twogether.ui.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.data.db.Moment
import com.ssbmedia.twogether.data.db.MomentNote
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.ui.components.EmptyState
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MomentsViewModel : ViewModel() {
    val moments = ServiceLocator.momentRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Deletes a Moment - unlike a manual session, this needs no isManual-style guard first: ANY moment
     * can be deleted by either partner (see Moment.deleted's doc), so MomentFullScreen's delete button
     * calls straight through. Same "don't make it wait for the next reconnect" reasoning as Calendar's
     * deleteManualSession - if we're already together, request a sync right now so the deletion
     * propagates to the partner's phone immediately. */
    fun deleteMoment(moment: Moment) {
        viewModelScope.launch {
            ServiceLocator.momentRepository.softDelete(moment)
            if (ServiceLocator.proximityStateStore.current().isTogether) {
                AppEvents.requestManualSync()
            }
        }
    }
}

@Composable
fun MomentsScreen(onBack: () -> Unit, onNavigateCamera: () -> Unit) {
    val vm: MomentsViewModel = viewModel(factory = SimpleViewModelFactory { MomentsViewModel() })
    val moments by vm.moments.collectAsState()
    // Feature 2: syncIds currently being requested/received over GATT - drives the "Receiving photo…"
    // indicator below instead of the old static remote-stub placeholder while a transfer is in flight.
    val transferring by AppEvents.momentsTransferring.collectAsState()
    var selected by remember { mutableStateOf<Moment?>(null) }

    val zone = remember { ZoneId.systemDefault() }
    val grouped = remember(moments) {
        moments.groupBy { Instant.ofEpochMilli(it.takenAt).atZone(zone).toLocalDate() }
    }

    // Backfill entry point: "Choose from gallery" sits as a small companion FAB above the existing
    // camera FAB (a lightweight speed-dial rather than a menu, since there are only ever two actions
    // here) - see GalleryImportFlow.kt for the pick -> copy-to-local-storage -> date-assignment flow
    // this triggers. New Moments land in the same observeAll() Flow this screen already collects, so
    // nothing else here needs to change once GalleryImportHost's onImported fires.
    GalleryImportHost(onImported = {}) { launchGalleryPicker ->
        // BUG fix: MomentFullScreen used to be rendered INSIDE the Scaffold's content slot below, which
        // only fills the content area BELOW the top bar - the Scaffold's own topBar and
        // floatingActionButton are separate slots that always compose on top of it regardless. Despite
        // MomentFullScreen's own Box being "fillMaxSize()" with a 94%-black background, the top app bar
        // and both FABs stayed live and visible drawn OVER it - specifically, the camera FAB overlapped
        // the note's "Save" button by more than half its area, and since the FAB sits above in z-order,
        // taps in that overlap region opened the camera instead of saving the note. Moving
        // MomentFullScreen to a sibling of the Scaffold (inside this outer Box, composed after it) makes
        // it a true full-screen overlay covering the top bar and FABs too, matching the intended design.
        Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Our Moments") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                    }
                )
            },
            floatingActionButton = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallFloatingActionButton(onClick = launchGalleryPicker) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = "Choose from gallery")
                    }
                    FloatingActionButton(onClick = onNavigateCamera) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = "Take a photo")
                    }
                }
            }
        ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (transferring.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = if (transferring.size == 1) "Syncing 1 photo…" else "Syncing ${transferring.size} photos…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
        if (moments.isEmpty()) {
            EmptyState(
                emoji = "📸",
                title = "No moments yet",
                subtitle = "Photos you take together will show up here, grouped by day.",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                grouped.forEach { (day, dayMoments) ->
                    item(key = day.toString()) {
                        Column {
                            Text(
                                text = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                dayMoments.forEach { moment ->
                                    MomentThumbnail(
                                        moment = moment,
                                        isTransferring = moment.syncId in transferring,
                                        onClick = { selected = moment }
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

        selected?.let { moment ->
            MomentFullScreen(
                moment = moment,
                isTransferring = moment.syncId in transferring,
                onDismiss = { selected = null },
                // Once deleted, `moment` is a stale snapshot that no longer reflects the (now-filtered)
                // Flow - closing the detail view here sidesteps ever rendering a deleted moment's
                // fullscreen view after the fact, rather than needing extra reactivity to notice it's gone.
                onDelete = { vm.deleteMoment(moment); selected = null }
            )
        }
        }
    }
}

@Composable
private fun MomentThumbnail(moment: Moment, isTransferring: Boolean, onClick: () -> Unit) {
    // Feature 2: renders off photoDownloaded now (not isRemote) - a remote-stub moment whose photo
    // transfer has since completed correctly shows the real image here, not the placeholder forever. The
    // File(...).isFile check stays as a defensive belt-and-suspenders against the flag and disk disagreeing.
    // BUG fix: this was a plain `remember { ... }`, which runs its blocking disk I/O synchronously on the
    // UI thread the first time each grid item composes (and again whenever these keys change) - in a grid
    // with many moments, that's a blocking syscall per thumbnail right as it scrolls into view, a real
    // (if usually small) jank risk an independent audit round flagged. produceState moves the actual
    // File.isFile check onto Dispatchers.IO, defaulting to `false` (the placeholder) for the one frame
    // before it resolves rather than blocking composition to get the real answer immediately.
    val hasLocalPhoto by produceState(initialValue = false, moment.photoUri, moment.photoDownloaded) {
        value = withContext(Dispatchers.IO) { moment.photoDownloaded && File(moment.photoUri).isFile }
    }
    if (hasLocalPhoto) {
        AsyncImage(
            model = moment.photoUri,
            contentDescription = "Moment",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
        )
    } else {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (isTransferring) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = "Photo on partner's phone",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun MomentFullScreen(moment: Moment, isTransferring: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val dateLabel = remember(moment.takenAt) {
        Instant.ofEpochMilli(moment.takenAt).atZone(zone).toLocalDateTime()
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }
    val hasLocalPhoto = remember(moment.photoUri, moment.photoDownloaded) { moment.photoDownloaded && File(moment.photoUri).isFile }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.94f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .clickable(onClick = onDismiss, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 32.dp))
            if (hasLocalPhoto) {
                AsyncImage(
                    model = moment.photoUri,
                    contentDescription = "Moment",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(androidx.compose.ui.graphics.Color.DarkGray.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isTransferring) {
                            CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f))
                            Text("Receiving photo…", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 12.dp))
                        } else {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f))
                            Text("This photo is on your partner's phone", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
            Text(
                text = dateLabel,
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                text = if (moment.sessionId != null) "Taken while together 💕" else "Taken apart",
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium
            )

            // Delete affordance: ANY moment can be deleted regardless of isRemote - unlike a manual
            // session, there's no isManual-equivalent gate to check first (see Moment.deleted's doc), so
            // this is always shown. White/alpha-tinted to read on the dark full-bleed photo background,
            // matching the icon/text tinting already used elsewhere in this composable (e.g. the
            // remote-stub placeholder above) rather than this screen's default (light) Material theming.
            Row(
                modifier = Modifier
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = { showDeleteConfirm = true })
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    "Delete photo",
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }

            MomentNotesSection(momentSyncId = moment.syncId)

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 32.dp))
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this photo?") },
            text = { Text("This can't be undone, and will be removed for both of you once you next sync.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
}

/** Feature D: "Your note" (editable, saved locally + synced out on the next GATT round) and, if the
 * partner has written one for this same moment, "{partner}'s note" shown read-only right below it. */
@Composable
private fun MomentNotesSection(momentSyncId: String) {
    val coroutineScope = rememberCoroutineScope()
    var myDeviceId by remember { mutableStateOf<String?>(null) }
    var partnerName by remember { mutableStateOf("Your partner") }
    var notes by remember { mutableStateOf<List<MomentNote>>(emptyList()) }
    var myText by remember { mutableStateOf("") }
    var initializedMyText by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        myDeviceId = ServiceLocator.settingsStore.getOrCreateLocalDeviceId()
        partnerName = ServiceLocator.pairingStore.current().partnerName
    }
    LaunchedEffect(momentSyncId) {
        ServiceLocator.momentNoteRepository.observeForMoment(momentSyncId).collect { notes = it }
    }
    // Seed the editable field from whatever's already stored for "me" exactly once per moment, so
    // typing doesn't get stomped by the very Flow this same save writes back into.
    val myExisting = remember(notes, myDeviceId) { notes.firstOrNull { it.authorDeviceId == myDeviceId && !it.deleted } }
    LaunchedEffect(momentSyncId, myExisting) {
        if (!initializedMyText) {
            myText = myExisting?.text.orEmpty()
            initializedMyText = true
        }
    }
    val partnerNote = remember(notes, myDeviceId) { notes.firstOrNull { it.authorDeviceId != myDeviceId && !it.deleted } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .clickable(enabled = false) {}, // swallow taps so typing/saving doesn't dismiss the overlay
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Your note", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = myText,
            onValueChange = { myText = it; saved = false },
            placeholder = { Text("A little detail about this one…") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = androidx.compose.ui.graphics.Color.White,
                unfocusedTextColor = androidx.compose.ui.graphics.Color.White
            )
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = {
                val deviceId = myDeviceId ?: return@TextButton
                coroutineScope.launch {
                    ServiceLocator.momentNoteRepository.saveMyNote(momentSyncId, deviceId, myText)
                    saved = true
                    // Best-effort: nudge a sync out right away if together, same debounced-manual-sync
                    // entry point Date Ideas uses (Feature B) - if apart, this just no-ops honestly.
                    if (ServiceLocator.proximityStateStore.current().isTogether) {
                        com.ssbmedia.twogether.events.AppEvents.requestManualSync()
                    }
                }
            }) { Text(if (saved) "Saved ✓" else "Save note") }
        }

        if (partnerNote != null && partnerNote.text.isNotBlank()) {
            Text(
                "$partnerName's note",
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f))
            ) {
                Text(
                    partnerNote.text,
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}
