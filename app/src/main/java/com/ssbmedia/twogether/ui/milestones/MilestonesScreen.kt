package com.ssbmedia.twogether.ui.milestones

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.data.db.Milestone
import com.ssbmedia.twogether.data.db.Moment
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.notif.MilestoneAlarmScheduler
import com.ssbmedia.twogether.ui.components.EmptyState
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
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
fun MilestonesScreen(onBack: () -> Unit, initialMilestoneId: String? = null, onInitialMilestoneConsumed: () -> Unit = {}) {
    val vm: MilestonesViewModel = viewModel(factory = SimpleViewModelFactory { MilestonesViewModel() })
    val milestones by vm.milestones.collectAsState()
    val moments by vm.moments.collectAsState()
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Milestone?>(null) }
    var editingPhotoFor by remember { mutableStateOf<Milestone?>(null) }
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
                    val linkedMoment = milestone.linkedMomentSyncId?.let { syncId -> moments.firstOrNull { it.syncId == syncId } }
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
                                IconButton(onClick = { editingPhotoFor = milestone }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit photo")
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
            moments = moments,
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
        MilestoneRetrospective(milestone = milestone, moments = moments, zone = zone, onDismiss = { retrospectiveForId = null })
    }

    editingPhotoFor?.let { milestone ->
        EditMilestonePhotoDialog(
            milestone = milestone,
            moments = moments,
            onDismiss = { editingPhotoFor = null },
            onSave = { linkedMomentSyncId ->
                vm.setLinkedMoment(milestone, linkedMomentSyncId)
                editingPhotoFor = null
            }
        )
    }
}

@Composable
private fun AddMilestoneDialog(
    moments: List<Moment>,
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
    // Same photoDownloaded+file-exists gate as MilestoneRetrospective/the milestone-card thumbnail below -
    // only ever offer a photo this device can actually display right now.
    val availableMoments = remember(moments) {
        moments.filter { it.photoDownloaded && File(it.photoUri).isFile }.sortedByDescending { it.takenAt }
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
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        availableMoments.forEach { moment ->
            val selected = moment.syncId == selectedMomentSyncId
            AsyncImage(
                model = moment.photoUri,
                contentDescription = "Pick this photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        width = if (selected) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(if (selected) null else moment.syncId) }
            )
        }
    }
}

/** Lets the user pick/swap/clear an EXISTING milestone's linked photo - AddMilestoneDialog's picker only
 * ever runs at creation time, this covers going back to it afterward (see MilestonesViewModel.
 * setLinkedMoment's own doc). Deliberately just the photo picker, not label/date - editing those isn't
 * supported anywhere else in this screen either, so adding it here would be scope beyond what this fix
 * needs. */
@Composable
private fun EditMilestonePhotoDialog(milestone: Milestone, moments: List<Moment>, onDismiss: () -> Unit, onSave: (linkedMomentSyncId: String?) -> Unit) {
    var selectedMomentSyncId by remember(milestone.id) { mutableStateOf(milestone.linkedMomentSyncId) }
    val availableMoments = remember(moments) {
        moments.filter { it.photoDownloaded && File(it.photoUri).isFile }.sortedByDescending { it.takenAt }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Photo for \"${milestone.label}\"") },
        text = {
            Column {
                if (availableMoments.isEmpty()) {
                    Text(
                        "No photos to choose from yet - take one in Moments first.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    MomentPhotoPicker(
                        availableMoments = availableMoments,
                        selectedMomentSyncId = selectedMomentSyncId,
                        onSelect = { selectedMomentSyncId = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selectedMomentSyncId) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun MilestoneRetrospective(milestone: Milestone, moments: List<Moment>, zone: ZoneId, onDismiss: () -> Unit) {
    // "Throughout the years": every Moment whose takenAt falls on this same month+day, in any year,
    // grouped by year - the whole point being it works even across many years of photos.
    val byYear = remember(moments, milestone.month, milestone.day) {
        moments
            .filter {
                val d = Instant.ofEpochMilli(it.takenAt).atZone(zone).toLocalDate()
                d.monthValue == milestone.month && d.dayOfMonth == milestone.day
            }
            .groupBy { Instant.ofEpochMilli(it.takenAt).atZone(zone).toLocalDate().year }
            .toSortedMap(compareByDescending { it })
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
                modifier = Modifier.padding(bottom = 16.dp)
            )
            if (byYear.isEmpty()) {
                Text(
                    "No photos on this day yet in any year — take one today to start the tradition 📸",
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f)
                )
            } else {
                byYear.forEach { (year, yearMoments) ->
                    Text("$year", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        yearMoments.forEach { moment ->
                            // BUG fix: was `!moment.isRemote`, the pre-Feature-2 signal for "do we hold
                            // the photo bytes" - MomentsScreen was already updated to the correct
                            // `photoDownloaded` flag when photo sync was added (a partner's photo can be
                            // isRemote=true AND fully downloaded), but this screen was missed. Without
                            // this fix, a successfully-downloaded partner photo showed the placeholder
                            // here while rendering correctly in the Moments gallery.
                            val hasLocalPhoto = remember(moment.photoUri, moment.photoDownloaded) { moment.photoDownloaded && File(moment.photoUri).isFile }
                            if (hasLocalPhoto) {
                                AsyncImage(
                                    model = moment.photoUri,
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
private fun monthDayLabel(month: Int, day: Int): String =
    "${Month.of(month.coerceIn(1, 12)).getDisplayName(TextStyle.FULL, Locale.getDefault())} ${day.coerceIn(1, 31)}"
