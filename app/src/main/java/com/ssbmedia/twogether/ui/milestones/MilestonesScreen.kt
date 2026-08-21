package com.ssbmedia.twogether.ui.milestones

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.data.db.Milestone
import com.ssbmedia.twogether.data.db.Moment
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.notif.MilestoneAlarmScheduler
import com.ssbmedia.twogether.ui.components.EmptyState
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import com.ssbmedia.twogether.util.PhotoEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

class MilestonesViewModel : ViewModel() {
    val milestones = ServiceLocator.milestoneRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val moments = ServiceLocator.momentRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** MAJOR fix (ultimate-app-review round 1, Opus): AddMilestoneDialog/EditMilestonePhotoDialog used to
     * run this exact photoDownloaded+File.isFile filter directly inside composition, over the FULL moments
     * list, on every recomposition - measured at 4-6s of main-thread blocking and 200+ skipped frames at a
     * realistic (1000+) photo library, the same "blocking disk I/O on Main" mistake
     * HomeViewModel.pickRandomMomentIfNeeded already explicitly guards against elsewhere in this app (see
     * its own withContext(Dispatchers.IO) comment). Filtering here instead - off Dispatchers.IO, cached in
     * a StateFlow - makes both dialogs' own filtering free; see MomentPhotoPicker's LazyRow fix for the
     * other half of this (avoiding eagerly composing every result, not just avoiding I/O on Main). */
    val availableMoments: StateFlow<List<Moment>> = moments
        .map { list -> list.filter { it.photoDownloaded && File(it.photoUri).isFile }.sortedByDescending { it.takenAt } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(context: android.content.Context, label: String, month: Int, day: Int, year: Int?, linkedMomentSyncId: String?) {
        viewModelScope.launch {
            val created = ServiceLocator.milestoneRepository.add(label, month, day, year, linkedMomentSyncId)
            MilestoneAlarmScheduler.scheduleOne(context, created)
            if (ServiceLocator.proximityStateStore.current().isTogether) AppEvents.requestManualSync()
        }
    }

    fun delete(context: android.content.Context, milestone: Milestone) {
        viewModelScope.launch {
            ServiceLocator.milestoneRepository.delete(milestone)
            MilestoneAlarmScheduler.cancel(context, milestone.id)
            if (ServiceLocator.proximityStateStore.current().isTogether) AppEvents.requestManualSync()
        }
    }

    /** UX-FIX-PLAN.md Phase 3 item 19: a full edit (label + month/day/year, alongside the linked photo) -
     * previously only the photo was editable (see the former EditMilestonePhotoDialog this supersedes).
     * Always re-arms the yearly alarm via MilestoneAlarmScheduler.scheduleOne afterward (cheap and
     * idempotent even when the date didn't change - matches [add]'s own unconditional call) so a changed
     * month/day always re-arms correctly rather than needing a separate "did the date change" branch that
     * could itself drift out of sync with what actually changed. */
    fun update(
        context: android.content.Context,
        milestone: Milestone,
        label: String,
        month: Int,
        day: Int,
        year: Int?,
        linkedMomentSyncId: String?
    ) {
        viewModelScope.launch {
            ServiceLocator.milestoneRepository.update(milestone, label, month, day, year, linkedMomentSyncId)
            MilestoneAlarmScheduler.scheduleOne(context, milestone.copy(label = label, month = month, day = day))
            if (ServiceLocator.proximityStateStore.current().isTogether) AppEvents.requestManualSync()
        }
    }

    /** Changes (or clears, if [linkedMomentSyncId] is null) an EXISTING milestone's linked photo - the
     * add-time-only picker in AddMilestoneDialog covers a NEW milestone, this covers going back to add/
     * swap/remove one on a milestone that already exists. No alarm rescheduling needed here (unlike
     * add/delete above) - the photo link never affects month/day/label, the only things
     * MilestoneAlarmScheduler cares about. */
    fun setLinkedMoment(milestone: Milestone, linkedMomentSyncId: String?) {
        viewModelScope.launch {
            ServiceLocator.milestoneRepository.setLinkedMoment(milestone, linkedMomentSyncId)
            if (ServiceLocator.proximityStateStore.current().isTogether) AppEvents.requestManualSync()
        }
    }
}

@Composable
fun MilestonesScreen(
    onBack: () -> Unit,
    initialMilestoneId: String? = null,
    onInitialMilestoneConsumed: () -> Unit = {},
    // UX-FIX-PLAN.md Phase 3 item 20: Milestone -> Calendar date - threaded down into
    // MilestoneRetrospective below, see its own doc for exactly which taps drive it.
    onOpenCalendar: (jumpToEpochDay: Long) -> Unit = {}
) {
    val vm: MilestonesViewModel = viewModel(factory = SimpleViewModelFactory { MilestonesViewModel() })
    val milestones by vm.milestones.collectAsState()
    val availableMoments by vm.availableMoments.collectAsState()
    val moments by vm.moments.collectAsState()
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Milestone?>(null) }
    // MINOR fix (ultimate-app-review round 3, user-requested follow-up): was
    // `remember { mutableStateOf<Milestone?>(null) }` - rotating the device with the edit-photo dialog
    // open silently closed it (no crash/data loss, just an inconsistency with retrospectiveForId right
    // below, which already survives rotation). Same fix, same reasoning: store only the milestone's ID
    // via rememberSaveable and derive the actual Milestone from the already-loaded list.
    // UX-FIX-PLAN.md Phase 3 item 19: renamed from editingPhotoForId now that this drives a full
    // label/date/photo edit, not just the photo - same rememberSaveable-by-id rotation-survival shape.
    var editingForId by rememberSaveable { mutableStateOf<String?>(null) }
    // BUG fix: was `var retrospectiveFor by remember { mutableStateOf<Milestone?>(null) }` - a further
    // review round found that plain `remember` here defeated the ROTATION half of the fix below (making
    // NavGraph's latchedMilestoneId `rememberSaveable`): the caller's latch is nulled via
    // onInitialMilestoneConsumed() within milliseconds of opening, well before a rotation could ever
    // save it, so by the time the Activity is recreated nothing anywhere still remembers a retrospective
    // was open - the screen just came back with an empty retrospectiveFor. Storing only the milestone's
    // ID via rememberSaveable (a plain String, no custom Saver needed) and deriving the actual Milestone
    // object from the already-loaded list survives rotation independently of the latch - which as a
    // side effect also fixes the pre-existing (not a regression - this bug already existed before any of
    // this notification work) case of a MANUALLY-opened retrospective (tapping a row) dropping on
    // rotation too.
    var retrospectiveForId by rememberSaveable { mutableStateOf<String?>(null) }
    val retrospectiveFor = milestones.firstOrNull { it.id == retrospectiveForId }
    val editingFor = milestones.firstOrNull { it.id == editingForId }

    // BUG fix: an independent review round found the caller-side latch (NavGraph.kt's
    // latchedMilestoneId, which this screen's initialMilestoneId is fed from) was never cleared after
    // being consumed - so EVERY later visit to this screen (not just the one right after a notification
    // tap) kept re-finding the same milestone in `milestones` and silently reopening its retrospective
    // again, since retrospectiveForId itself resets to null on every fresh composition of this screen
    // (Compose Navigation disposes a popped destination). Only calling onInitialMilestoneConsumed() once
    // a REAL match is actually found - not on an earlier pass where `milestones` simply hasn't loaded
    // yet - matters: clearing the caller's latch prematurely (before the real list loads) would silently
    // reintroduce the original blocker this whole mechanism exists to fix.
    LaunchedEffect(initialMilestoneId, milestones) {
        if (initialMilestoneId != null && retrospectiveForId == null) {
            val match = milestones.firstOrNull { it.id == initialMilestoneId }
            if (match != null) {
                retrospectiveForId = match.id
                onInitialMilestoneConsumed()
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Milestones") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add milestone")
            }
        }
    ) { padding ->
        if (milestones.isEmpty()) {
            EmptyState(
                emoji = "🎉",
                title = "No milestones yet",
                subtitle = "Add your anniversary, first date, or any date worth celebrating every year.",
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(milestones, key = { it.id }) { milestone ->
                    // Feature: link a Moment's photo to a milestone (see Milestone.linkedMomentSyncId's
                    // own doc) - resolved fresh from the currently-loaded moments list rather than cached
                    // on the milestone row itself, same "always re-resolve, never denormalize" approach
                    // MilestoneRetrospective already uses below for its own date-matched gallery. A link
                    // that no longer resolves to a locally-held photo (not yet synced, or deleted) is
                    // treated as "no photo" - see the field's own doc.
                    //
                    // User-requested auto-sync: when there's no EXPLICIT link, fall back to the same
                    // date-matched gallery MilestoneRetrospective already shows (matchingMomentsByYear) -
                    // most-recent year, most-recently-taken photo that day. This is a pure display-time
                    // fallback (never writes linkedMomentSyncId), so an explicit user choice can never be
                    // silently overridden by construction - there's simply nothing to override, this only
                    // ever fills in when the field is genuinely null. Deliberately NOT scoped to only
                    // exact-dated milestones (milestone.year != null) - a recurring milestone with no
                    // year still has a real "this specific day" per occurrence, and showing whichever
                    // year's photo exists is strictly more informative than showing nothing.
                    val linkedMoment = remember(moments, milestone.linkedMomentSyncId, milestone.month, milestone.day) {
                        val explicit = milestone.linkedMomentSyncId?.let { syncId -> moments.firstOrNull { it.syncId == syncId } }
                        explicit ?: if (milestone.linkedMomentSyncId == null) {
                            matchingMomentsByYear(moments, milestone.month, milestone.day, zone)
                                .values.firstOrNull()
                                ?.maxByOrNull { it.takenAt }
                        } else null
                    }
                    val hasLinkedPhoto = linkedMoment != null && linkedMoment.photoDownloaded && File(linkedMoment.photoUri).isFile
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        onClick = { retrospectiveForId = milestone.id }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (hasLinkedPhoto) {
                                    AsyncImage(
                                        model = linkedMoment!!.photoUri,
                                        contentDescription = "${milestone.label} photo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                                    )
                                }
                                Column(modifier = Modifier.padding(start = if (hasLinkedPhoto) 10.dp else 0.dp)) {
                                    Text(milestone.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = monthDayLabel(milestone.month, milestone.day) + (milestone.year?.let { " · since $it" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            Row {
                                IconButton(onClick = { editingForId = milestone.id }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit milestone")
                                }
                                IconButton(onClick = { pendingDelete = milestone }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddMilestoneDialog(
            availableMoments = availableMoments,
            onDismiss = { showAddDialog = false },
            onAdd = { label, month, day, year, linkedMomentSyncId ->
                vm.add(context, label, month, day, year, linkedMomentSyncId)
                showAddDialog = false
            }
        )
    }

    pendingDelete?.let { milestone ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this milestone?") },
            text = { Text("\"${milestone.label}\" and its yearly reminder will be removed for both of you once you next sync.") },
            confirmButton = { TextButton(onClick = { vm.delete(context, milestone); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    retrospectiveFor?.let { milestone ->
        MilestoneRetrospective(
            milestone = milestone,
            moments = moments,
            zone = zone,
            onDismiss = { retrospectiveForId = null },
            onOpenCalendar = onOpenCalendar
        )
    }

    editingFor?.let { milestone ->
        EditMilestoneDialog(
            milestone = milestone,
            availableMoments = availableMoments,
            onDismiss = { editingForId = null },
            onSave = { label, month, day, year, linkedMomentSyncId ->
                vm.update(context, milestone, label, month, day, year, linkedMomentSyncId)
                editingForId = null
            }
        )
    }
}

@Composable
private fun AddMilestoneDialog(
    availableMoments: List<Moment>,
    onDismiss: () -> Unit,
    onAdd: (label: String, month: Int, day: Int, year: Int?, linkedMomentSyncId: String?) -> Unit
) {
    var label by remember { mutableStateOf("") }
    val today = remember { LocalDate.now() }
    var month by remember { mutableStateOf(today.monthValue) }
    var dayText by remember { mutableStateOf(today.dayOfMonth.toString()) }
    var yearText by remember { mutableStateOf("") }
    var monthMenuExpanded by remember { mutableStateOf(false) }
    var selectedMomentSyncId by remember { mutableStateOf<String?>(null) }

    val day = dayText.toIntOrNull()
    val maxDay = remember(month) { YearMonth.of(2024, month).lengthOfMonth() } // 2024 is a leap year, so Feb 29 is always offered
    val error: String? = when {
        label.isBlank() -> "Give it a name (e.g. Anniversary)"
        day == null || day < 1 || day > maxDay -> "Enter a valid day for this month"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a milestone") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text("Anniversary, First Kiss…") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = monthMenuExpanded,
                        onExpandedChange = { monthMenuExpanded = it },
                        modifier = Modifier.weight(1.4f)
                    ) {
                        OutlinedTextField(
                            value = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Month") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = monthMenuExpanded) },
                            modifier = Modifier.menuAnchor()
                        )
                        DropdownMenu(expanded = monthMenuExpanded, onDismissRequest = { monthMenuExpanded = false }) {
                            (1..12).forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(Month.of(m).getDisplayName(TextStyle.FULL, Locale.getDefault())) },
                                    onClick = { month = m; monthMenuExpanded = false }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = dayText,
                        onValueChange = { dayText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Day") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = yearText,
                    onValueChange = { yearText = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Year (optional)") },
                    placeholder = { Text("e.g. 2024") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                MomentPhotoPicker(
                    availableMoments = availableMoments,
                    selectedMomentSyncId = selectedMomentSyncId,
                    onSelect = { selectedMomentSyncId = it }
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = error == null,
                onClick = { onAdd(label.trim(), month, day ?: 1, yearText.toIntOrNull(), selectedMomentSyncId) }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Shared photo-picker grid - used by both AddMilestoneDialog (choosing at creation time) and
 * EditMilestonePhotoDialog (changing/clearing it later) so the two flows can never visually drift apart. */
@Composable
private fun MomentPhotoPicker(availableMoments: List<Moment>, selectedMomentSyncId: String?, onSelect: (String?) -> Unit) {
    if (availableMoments.isEmpty()) return
    Text(
        "Photo (optional)",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
    )
    // MAJOR fix (ultimate-app-review round 1, Opus): was a plain Row(...horizontalScroll...).forEach,
    // which composed EVERY available photo eagerly regardless of scroll visibility - measured at
    // seconds of main-thread blocking and hundreds of skipped frames at a realistic (1000+) photo
    // library. LazyRow only composes what's actually on/near screen; see
    // MilestonesViewModel.availableMoments' fix for the other half (the I/O filter this list already
    // arrives pre-filtered from, off Main).
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(availableMoments, key = { _, m -> m.syncId }) { index, moment ->
            val selected = moment.syncId == selectedMomentSyncId
            // MINOR a11y fix (ultimate-app-review round 1, Opus): every thumbnail used to share the
            // identical contentDescription "Pick this photo" with selection expressed only as a visual
            // border, so a screen reader announced N indistinguishable buttons with no selected state.
            // `selectable` reports the selected state to accessibility services itself; the index-based
            // description at least distinguishes which photo is which.
            // BUG fix (user-reported "rotated images are not saved"): see PhotoEditor.cacheBustKey's
            // own doc - without this, a rotate from the Moments viewer kept showing pre-rotate bytes here.
            val cacheBustKey = remember(moment.photoUri) { PhotoEditor.cacheBustKey(moment.photoUri) }
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(moment.photoUri)
                    .memoryCacheKey("${moment.photoUri}:$cacheBustKey")
                    .diskCacheKey("${moment.photoUri}:$cacheBustKey")
                    .build(),
                contentDescription = "Photo ${index + 1} of ${availableMoments.size}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        width = if (selected) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .selectable(selected = selected) { onSelect(if (selected) null else moment.syncId) }
            )
        }
    }
}

/**
 * UX-FIX-PLAN.md Phase 3 item 19: a full edit of an already-existing milestone - label, month/day, year,
 * AND its linked photo, superseding the former EditMilestonePhotoDialog (which was deliberately
 * photo-only, per its own now-obsolete doc: "editing [label/date] isn't supported anywhere else in this
 * screen either"). Mirrors AddMilestoneDialog's own label/month/day/year field layout so the two dialogs
 * can't visually drift apart, pre-filled from [milestone] instead of today's date. The dangling-link
 * self-heal below is carried over unchanged from the old photo-only dialog - see its own doc for why it's
 * a LaunchedEffect rather than a one-shot remember.
 */
@Composable
private fun EditMilestoneDialog(
    milestone: Milestone,
    availableMoments: List<Moment>,
    onDismiss: () -> Unit,
    onSave: (label: String, month: Int, day: Int, year: Int?, linkedMomentSyncId: String?) -> Unit
) {
    var label by remember(milestone.id) { mutableStateOf(milestone.label) }
    var month by remember(milestone.id) { mutableStateOf(milestone.month) }
    var dayText by remember(milestone.id) { mutableStateOf(milestone.day.toString()) }
    var yearText by remember(milestone.id) { mutableStateOf(milestone.year?.toString() ?: "") }
    var monthMenuExpanded by remember { mutableStateOf(false) }

    // MAJOR fix (ultimate-app-review round 1, both reviewers independently found this): was
    // `mutableStateOf(milestone.linkedMomentSyncId)` unconditionally - if the linked Moment had since been
    // deleted (or its photo bytes aren't downloaded on this device), that syncId doesn't match anything in
    // availableMoments, so the picker rendered with NOTHING visibly selected even though a link still
    // existed - indistinguishable from "no link" in the UI, with no way to deliberately clear it (clearing
    // requires tapping the currently-selected thumbnail, and nothing was selected). Worse, tapping Save
    // with no changes re-persisted that same dangling id, permanently. Clearing it only once we've
    // positively confirmed it doesn't resolve means: a resolvable link still shows selected as before, and
    // a dangling one now honestly shows nothing selected AND Save correctly clears it (the self-healing
    // outcome this milestone should have anyway, since the linked photo is gone).
    var selectedMomentSyncId by remember(milestone.id) { mutableStateOf(milestone.linkedMomentSyncId) }
    var hadDanglingLink by remember(milestone.id) { mutableStateOf(false) }
    // MAJOR fix (ultimate-app-review round 2, Opus): the ABOVE two lines used to be a single
    // `remember(milestone.id) { mutableStateOf(milestone.linkedMomentSyncId?.takeIf { availableMoments.any
    // {...} }) }` - deciding dangling-or-not from whatever availableMoments happened to hold on the
    // dialog's FIRST composition. availableMoments is a StateFlow seeded with emptyList() and filled
    // asynchronously off Dispatchers.IO (see MilestonesViewModel.availableMoments' own doc) - at a
    // realistic library size (live-reproduced at 8000 photos) the dialog's first frame can render before
    // that IO-dispatched filter has ever emitted a real value, so a perfectly VALID link was
    // indistinguishable from a dangling one at that instant, got permanently locked in as "not selected"
    // by the one-shot remember, and a no-touch Save then silently destroyed a link that was never actually
    // broken. This LaunchedEffect instead only ever CLEARS the selection - and only once, guarded by
    // hadDanglingLink - the moment availableMoments has genuinely loaded something (isNotEmpty()) and that
    // something still doesn't include this link. Until that first non-empty emission arrives, the link
    // stays exactly as passed in, so a fast Save during the loading window persists the correct
    // (unresolved-but-not-actually-dangling) value instead of guessing wrong. A user who has since picked
    // a DIFFERENT photo is never affected - onSelect always sets selectedMomentSyncId to an id that's
    // already a member of the current availableMoments, so this effect's condition can't fire for it.
    LaunchedEffect(milestone.id, availableMoments) {
        if (!hadDanglingLink && selectedMomentSyncId != null && availableMoments.isNotEmpty() &&
            availableMoments.none { it.syncId == selectedMomentSyncId }
        ) {
            selectedMomentSyncId = null
            hadDanglingLink = true
        }
    }

    val day = dayText.toIntOrNull()
    val maxDay = remember(month) { YearMonth.of(2024, month).lengthOfMonth() } // 2024 is a leap year, so Feb 29 is always offered
    val error: String? = when {
        label.isBlank() -> "Give it a name (e.g. Anniversary)"
        day == null || day < 1 || day > maxDay -> "Enter a valid day for this month"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit milestone") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text("Anniversary, First Kiss…") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = monthMenuExpanded,
                        onExpandedChange = { monthMenuExpanded = it },
                        modifier = Modifier.weight(1.4f)
                    ) {
                        OutlinedTextField(
                            value = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Month") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = monthMenuExpanded) },
                            modifier = Modifier.menuAnchor()
                        )
                        DropdownMenu(expanded = monthMenuExpanded, onDismissRequest = { monthMenuExpanded = false }) {
                            (1..12).forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(Month.of(m).getDisplayName(TextStyle.FULL, Locale.getDefault())) },
                                    onClick = { month = m; monthMenuExpanded = false }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = dayText,
                        onValueChange = { dayText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Day") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = yearText,
                    onValueChange = { yearText = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Year (optional)") },
                    placeholder = { Text("e.g. 2024") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                // MINOR fix (ultimate-app-review round 3, Opus): was just `if (hadDanglingLink)` - that
                // flag is a one-time latch (see the LaunchedEffect above) and never resets, so once a
                // dangling link got cleared, this warning stayed on screen even after the user picked a
                // perfectly valid replacement photo. Save always persisted the correct value regardless
                // (this was cosmetic only), but also require selectedMomentSyncId == null so the warning
                // disappears the instant there's a real selection to show instead.
                if (hadDanglingLink && selectedMomentSyncId == null) {
                    Text(
                        "This milestone's photo isn't available anymore — pick a new one, or tap Save to clear it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                if (availableMoments.isEmpty()) {
                    Text(
                        // MINOR fix (ultimate-app-review round 1, both reviewers): was a plain hyphen -
                        // every other user-facing string in this file (and most of the app) uses an em
                        // dash for this kind of aside.
                        "No photos to choose from yet — take one in Moments first.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                } else {
                    MomentPhotoPicker(
                        availableMoments = availableMoments,
                        selectedMomentSyncId = selectedMomentSyncId,
                        onSelect = { selectedMomentSyncId = it }
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = error == null,
                onClick = { onSave(label.trim(), month, day ?: 1, yearText.toIntOrNull(), selectedMomentSyncId) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * @param onOpenCalendar UX-FIX-PLAN.md Phase 3 item 20: Milestone -> Calendar date. The top subtitle
 * ("<month day> throughout the years") is always tappable, jumping to the [nearestApplicableYear] for
 * this milestone (the milestone's own recorded [Milestone.year] if it has one, otherwise whichever of
 * this-year/last-year's occurrence has already happened) - a sensible single default even when there are
 * no photos to browse by year at all. Each year header below (when [byYear] isn't empty) is ALSO
 * independently tappable, jumping to that specific year instead - "the year currently being viewed in the
 * milestone's own retrospective," per the plan's own suggested interpretation.
 */
@Composable
private fun MilestoneRetrospective(
    milestone: Milestone,
    moments: List<Moment>,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onOpenCalendar: (jumpToEpochDay: Long) -> Unit = {}
) {
    // "Throughout the years": every Moment whose takenAt falls on this same month+day, in any year,
    // grouped by year - the whole point being it works even across many years of photos.
    val byYear = remember(moments, milestone.month, milestone.day) {
        matchingMomentsByYear(moments, milestone.month, milestone.day, zone)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.94f)).clickable(onClick = onDismiss)
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 24.dp))
            Text(milestone.label, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                monthDayLabel(milestone.month, milestone.day) + " throughout the years",
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .clickable(onClick = {
                        val year = nearestApplicableYear(milestone.month, milestone.day, milestone.year, LocalDate.now(zone))
                        onOpenCalendar(safeDateForYear(year, milestone.month, milestone.day).toEpochDay())
                    })
            )
            if (byYear.isEmpty()) {
                Text(
                    "No photos on this day yet in any year — take one today to start the tradition 📸",
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f)
                )
            } else {
                byYear.forEach { (year, yearMoments) ->
                    Text(
                        "$year",
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(top = 12.dp, bottom = 8.dp)
                            .clickable(onClick = {
                                onOpenCalendar(safeDateForYear(year, milestone.month, milestone.day).toEpochDay())
                            })
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        yearMoments.forEach { moment ->
                            // BUG fix: was `!moment.isRemote`, the pre-Feature-2 signal for "do we hold
                            // the photo bytes" - MomentsScreen was already updated to the correct
                            // `photoDownloaded` flag when photo sync was added (a partner's photo can be
                            // isRemote=true AND fully downloaded), but this screen was missed. Without
                            // this fix, a successfully-downloaded partner photo showed the placeholder
                            // here while rendering correctly in the Moments gallery.
                            val hasLocalPhoto = remember(moment.photoUri, moment.photoDownloaded) { moment.photoDownloaded && File(moment.photoUri).isFile }
                            // BUG fix (user-reported "rotated images are not saved"): see
                            // PhotoEditor.cacheBustKey's own doc.
                            val cacheBustKey = remember(moment.photoUri) { PhotoEditor.cacheBustKey(moment.photoUri) }
                            if (hasLocalPhoto) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(moment.photoUri)
                                        .memoryCacheKey("${moment.photoUri}:$cacheBustKey")
                                        .diskCacheKey("${moment.photoUri}:$cacheBustKey")
                                        .build(),
                                    contentDescription = "Moment from $year",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("📷", color = androidx.compose.ui.graphics.Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** BLOCKER fix, defense-in-depth: month/day here come straight from a stored [Milestone] row - as of
 * v2.3, both known ingestion points (BackupManager.parseMilestones, GattSyncManager.deserializeMilestones)
 * clamp these before they ever reach the DB, so a NEWLY-arriving bad value can no longer get in. But a row
 * that was already corrupted BEFORE that fix shipped (e.g. synced from a partner still on v2.2, or
 * restored from a backup taken back then) is still sitting in some phone's local DB right now, unclamped
 * - and Month.of(month) throws for anything outside 1-12, which would crash this screen (and the
 * retrospective view, which shares this same formatter) every time it tried to render that one row.
 * Clamped here too, at the single shared formatter both call sites go through, so a pre-existing bad row
 * displays a nearest-valid label instead of crashing - matching MilestoneAlarmScheduler.safeDate's own
 * defensive clamp for the exact same reason. */
/** User-requested (milestone photo auto-sync): every Moment whose takenAt falls on the given month+day,
 * in ANY year, grouped by year and sorted most-recent-year-first - extracted from
 * [MilestoneRetrospective]'s own "throughout the years" gallery (unchanged behavior there) so the
 * milestone card below can reuse the EXACT same match logic for its own auto-linked preview, rather than
 * growing a second, independently-maintained copy that could drift from what the retrospective view
 * itself considers a match. */
private fun matchingMomentsByYear(moments: List<Moment>, month: Int, day: Int, zone: ZoneId): Map<Int, List<Moment>> =
    moments
        .filter {
            val d = Instant.ofEpochMilli(it.takenAt).atZone(zone).toLocalDate()
            d.monthValue == month && d.dayOfMonth == day
        }
        .groupBy { Instant.ofEpochMilli(it.takenAt).atZone(zone).toLocalDate().year }
        .toSortedMap(compareByDescending { it })

private fun monthDayLabel(month: Int, day: Int): String =
    "${Month.of(month.coerceIn(1, 12)).getDisplayName(TextStyle.FULL, Locale.getDefault())} ${day.coerceIn(1, 31)}"

/** UX-FIX-PLAN.md Phase 3 item 20: builds a real [LocalDate] for [month]/[day] in [year] - same
 * defense-in-depth clamp as [monthDayLabel] above (a pre-existing corrupted row could otherwise crash
 * this, and specifically LocalDate.of, rather than just mis-rendering a label), matching
 * MilestoneAlarmScheduler.safeDate's own reasoning for the exact same clamp. Internal (not private) so
 * MilestonesScreenTest can exercise it directly without any Compose test infra. */
internal fun safeDateForYear(year: Int, month: Int, day: Int): LocalDate {
    val safeMonth = month.coerceIn(1, 12)
    val maxDay = YearMonth.of(year, safeMonth).lengthOfMonth()
    return LocalDate.of(year, safeMonth, day.coerceIn(1, maxDay))
}

/** UX-FIX-PLAN.md Phase 3 item 20: which calendar year to jump to when a milestone's date itself is
 * tapped (rather than a specific year header in its retrospective, which always names its own year
 * explicitly) - prefers the milestone's own recorded [milestoneYear] if one was entered (that's the one
 * year this particular milestone is actually ABOUT), otherwise whichever of this-year/last-year's
 * occurrence of month/day has already happened relative to [today] (so a milestone whose date is still
 * ahead this year jumps to last year's occurrence - an already-real day on the calendar - rather than a
 * not-yet-arrived date this year). Internal (not private) for the same test-without-Compose reason as
 * [safeDateForYear]. */
internal fun nearestApplicableYear(month: Int, day: Int, milestoneYear: Int?, today: LocalDate): Int {
    if (milestoneYear != null) return milestoneYear
    val thisYearDate = safeDateForYear(today.year, month, day)
    return if (!thisYearDate.isAfter(today)) today.year else today.year - 1
}
