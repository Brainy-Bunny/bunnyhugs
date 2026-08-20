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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.ssbmedia.twogether.util.DateFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

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
                                text = DateFormats.formatDate(day),
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
        DateFormats.formatDateTime(Instant.ofEpochMilli(moment.takenAt).atZone(zone).toLocalDateTime())
    }
    val hasLocalPhoto = remember(moment.photoUri, moment.photoDownloaded) { moment.photoDownloaded && File(moment.photoUri).isFile }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // MINOR fix (independent review, live-observed on two devices): making this a sibling of THIS screen's
    // Scaffold (the previous fix, see the comment at the call site) correctly covered this screen's own top
    // bar and FABs - but the bottom navigation bar does not belong to this screen at all. It lives in
    // TwogetherNavHost's Scaffold, one level UP, so no composable rendered inside a nav destination can
    // ever draw over it: Home/Calendar/Our Lists/Moments/Stats stayed fully visible and tappable beneath
    // the "full-screen" viewer, and the camera FAB showed faintly through it near the note's Save button.
    //
    // A Dialog is the fix rather than plumbing a "hide the bottom bar" flag up into NavGraph: a Dialog is
    // its own window, composed above the entire Activity, so it covers the bottom bar without this screen
    // needing to know the bar exists (and without every future full-screen overlay having to re-plumb the
    // same flag). usePlatformDefaultWidth = false removes the Material dialog width inset so it genuinely
    // fills the screen. Bonus correctness the old Box could not give: the system Back gesture now closes
    // the VIEWER first, instead of popping the whole Moments destination out from under an open photo.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
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
                // MAJOR fix (independent review, live-reproduced across two paired devices): was
                // `moment.sessionId != null`, which is ALWAYS null for a moment that arrived via sync (see
                // MomentRepository.mergeRemoteStubs) - so the identical photo read "Taken while together
                // 💕" on the phone that shot it and "Taken apart" on the partner's. Reads the synced
                // boolean now, so both phones agree. See Moment.takenWhileTogether's doc.
                text = if (moment.takenWhileTogether) "Taken while together 💕" else "Taken apart",
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
    // MAJOR fix (independent review of v2.6, live-reproduced): `notes` used to start as emptyList(), which
    // is indistinguishable from "this moment genuinely has no notes" - and that ambiguity silently
    // destroyed real user data (see the seeding effect below). Nullable now: null means "the DB has not
    // answered yet", emptyList() means "answered: there are none".
    var notes by remember { mutableStateOf<List<MomentNote>?>(null) }
    var myText by remember { mutableStateOf("") }
    var initializedMyText by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    // MAJOR fix (Phase 1 item 7 of UX-FIX-PLAN.md, root cause of "I saved a note but it doesn't
    // appear"): whether the editable field is showing right now. A saved note of your own used to have
    // NO read-only rendering anywhere - it only ever existed as the pre-filled contents of this
    // ALWAYS-editable field, indistinguishable at a glance from an empty draft. Now the field only shows
    // when there's genuinely nothing saved yet, or the user explicitly tapped "Edit" on their own saved
    // note (see hasSavedMyNote/showEditableField below) - a real saved note instead renders as its own
    // prominent read-only card, same as the partner's.
    var isEditingMine by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        myDeviceId = ServiceLocator.settingsStore.getOrCreateLocalDeviceId()
        partnerName = ServiceLocator.pairingStore.current().partnerName
    }
    LaunchedEffect(momentSyncId) {
        ServiceLocator.momentNoteRepository.observeForMoment(momentSyncId).collect { notes = it }
    }
    val myExisting = remember(notes, myDeviceId) {
        notes?.firstOrNull { it.authorDeviceId == myDeviceId && !it.deleted }
    }
    /**
     * Seed the editable field from whatever's already stored for "me" exactly once per moment, so typing
     * doesn't get stomped by the very Flow this same save writes back into.
     *
     * MAJOR fix (independent review of v2.6, live-reproduced on a real device): this used to latch on the
     * FIRST composition unconditionally. Both of its inputs arrive asynchronously - `myDeviceId` from a
     * suspend DataStore read, `notes` from a Room Flow - so on that first pass myDeviceId was still null
     * and notes still empty, `myExisting` resolved to null, and the effect latched `myText = ""` and set
     * initializedMyText = true. When the real data landed a moment later the effect re-ran but the latch
     * had already closed, so an existing note NEVER appeared: reopening a photo you had written a note on
     * always showed an empty box.
     *
     * That was not merely cosmetic. The blank box is a loaded gun: `saveMyNote` treats blank text as a
     * TOMBSTONE (deleted = true, see MomentNoteRepository.saveMyNote), so a user who reopened a photo,
     * saw an empty field, and tapped "Save note" - or tapped it after typing and then clearing - silently
     * destroyed the note they had written AND propagated that deletion to their partner's phone on the
     * next sync, with no warning and no undo.
     *
     * Now the latch only closes once BOTH prerequisites have genuinely resolved, so the value it captures
     * is the real stored one. `notes != null` (not `isNotEmpty()`) is what makes "no notes yet" still
     * latch correctly and immediately for a genuinely un-noted photo.
     */
    LaunchedEffect(momentSyncId, myDeviceId, notes) {
        if (!initializedMyText && myDeviceId != null && notes != null) {
            myText = myExisting?.text.orEmpty()
            initializedMyText = true
        }
    }
    val partnerNote = remember(notes, myDeviceId) {
        // Also guarded on myDeviceId being loaded: while it is still null EVERY note has
        // `authorDeviceId != null`, so for one frame this used to show the user their OWN note back to
        // them labelled as their partner's.
        if (myDeviceId == null) null else notes?.firstOrNull { it.authorDeviceId != myDeviceId && !it.deleted }
    }
    // Whether there's a real saved note of mine to show read-only - myExisting (not the myText draft)
    // is the source of truth here, since myText can be an in-progress, not-yet-saved edit.
    val hasSavedMyNote = myExisting?.text?.isNotBlank() == true
    // The editable field only shows once seeding has genuinely resolved (same guard as before,
    // untouched) AND either nothing is saved yet, or the user explicitly tapped Edit.
    val showEditableField = initializedMyText && (!hasSavedMyNote || isEditingMine)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .clickable(enabled = false) {}, // swallow taps so typing/saving doesn't dismiss the overlay
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Own saved note - read-only, ABOVE the input, with an explicit Edit affordance. This is what
        // was missing entirely before this fix: a saved note only ever existed as the editable field's
        // pre-filled value, with nothing ever rendered to confirm it had actually saved.
        if (hasSavedMyNote && !isEditingMine) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Your note", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { isEditingMine = true; saved = false }) { Text("Edit") }
                }
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.16f))
                ) {
                    Text(
                        myExisting?.text.orEmpty(),
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        if (partnerNote != null && partnerNote.text.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "$partnerName's note",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.titleSmall
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

        if (showEditableField) {
            Text("Your note", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = myText,
                onValueChange = { myText = it; saved = false },
                placeholder = { Text("A little detail about this one…") },
                modifier = Modifier.fillMaxWidth(),
                // MINOR fix (independent review round 2): the field used to be editable from the very first
                // frame, while the seeding effect above was still waiting on myDeviceId/notes. Anything typed
                // in that window was then overwritten the instant the latch fired - the same "an effect
                // stomped what the user typed" class of bug the seeding fix itself addresses. The window is
                // milliseconds, and nothing could be SAVED during it (the button below is gated on the same
                // flag), but leaving the field live invited exactly that race.
                enabled = initializedMyText,
                minLines = 2,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = androidx.compose.ui.graphics.Color.White,
                    unfocusedTextColor = androidx.compose.ui.graphics.Color.White
                )
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                // Lets the user back out of an in-progress edit without saving, reverting the draft back
                // to whatever's actually stored - only offered once there's something to revert TO.
                if (hasSavedMyNote) {
                    TextButton(onClick = {
                        myText = myExisting?.text.orEmpty()
                        isEditingMine = false
                    }) { Text("Cancel") }
                }
                // Defence in depth for the data-loss bug fixed in the seeding effect above: even if some
                // future change reintroduced a too-early latch, saving is impossible until this field is
                // known to hold the real stored value, so a blank box can never be committed as a tombstone
                // over a note the user actually wrote.
                TextButton(enabled = initializedMyText, onClick = {
                    val deviceId = myDeviceId ?: return@TextButton
                    coroutineScope.launch {
                        ServiceLocator.momentNoteRepository.saveMyNote(momentSyncId, deviceId, myText)
                        saved = true
                        // Back to the read-only card once saved (or, for a blank save, back to the "no
                        // note yet" empty-field state) - the Flow above will catch up moments later
                        // regardless, but this avoids a visible flash of the just-saved text sitting in
                        // an editable field a beat longer than it needs to.
                        isEditingMine = false
                        // Best-effort: nudge a sync out right away if together, same debounced-manual-sync
                        // entry point Date Ideas uses (Feature B) - if apart, this just no-ops honestly.
                        if (ServiceLocator.proximityStateStore.current().isTogether) {
                            com.ssbmedia.twogether.events.AppEvents.requestManualSync()
                        }
                    }
                }) { Text(if (saved) "Saved ✓" else "Save note") }
            }
        }
    }
}
