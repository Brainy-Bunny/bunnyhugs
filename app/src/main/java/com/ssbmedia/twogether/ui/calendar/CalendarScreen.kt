package com.ssbmedia.twogether.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.data.db.DayNote
import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.ui.components.DatePickerField
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import com.ssbmedia.twogether.ui.components.TimePickerField
import com.ssbmedia.twogether.util.DateFormats
import com.ssbmedia.twogether.util.SessionBoundsValidator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

class CalendarViewModel : ViewModel() {
    val sessions = ServiceLocator.sessionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val moments = ServiceLocator.momentRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    // UX-FIX-PLAN.md Phase 4 item 25: every active day note across the whole couple (mine + partner's) -
    // feeds the month-grid "has a note" marker on DayCell, same "all days at once" role
    // MomentRepository.observeAll plays for the photo marker above.
    val dayNotes = ServiceLocator.dayNoteRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val proximityState = ServiceLocator.proximityStateStore.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.ssbmedia.twogether.data.datastore.ProximityPersistedState())

    /** Backfills a past together-session by hand. Returns true on success so the UI can close the
     * dialog; validation happens in the caller (AddManualSessionDialog) before this is invoked. */
    fun addManualSession(startedAt: Long, endedAt: Long) {
        viewModelScope.launch {
            ServiceLocator.sessionRepository.addManualSession(startedAt, endedAt)
            // Same reasoning as Date Ideas' add/toggle/delete and a new photo capture - don't make a
            // freshly-backfilled session wait for the next reconnect or the 15-minute catch-all if
            // we're already together right now.
            if (ServiceLocator.proximityStateStore.current().isTogether) {
                AppEvents.requestManualSync()
            }
        }
    }

    /** Deletes a manually-backfilled session entered wrong - SessionRepository.softDeleteManual itself
     * no-ops for a genuine BLE-detected session, but the delete affordance below only ever shows for
     * isManual rows anyway. Same "don't make it wait for the next reconnect" reasoning as
     * addManualSession above - if we're already together, request a sync right now so the deletion
     * propagates to the partner's phone immediately. */
    fun deleteManualSession(session: TogetherSession) {
        viewModelScope.launch {
            ServiceLocator.sessionRepository.softDeleteManual(session)
            if (ServiceLocator.proximityStateStore.current().isTogether) {
                AppEvents.requestManualSync()
            }
        }
    }

    /** UX-FIX-PLAN.md Phase 3 item 19: edits an already-existing manually-backfilled session's start/end -
     * SessionRepository.updateManualSession itself no-ops for a genuine BLE-detected session, but the edit
     * affordance below only ever shows for isManual rows anyway. Same "don't make it wait for the next
     * reconnect" reasoning as [addManualSession]/[deleteManualSession] above. */
    fun updateManualSession(session: TogetherSession, startedAt: Long, endedAt: Long) {
        viewModelScope.launch {
            ServiceLocator.sessionRepository.updateManualSession(session, startedAt, endedAt)
            if (ServiceLocator.proximityStateStore.current().isTogether) {
                AppEvents.requestManualSync()
            }
        }
    }
}

/**
 * @param jumpToEpochDay Feature 1: when set (from a Stats card like "Longest single day" / "Together
 * since"), the calendar opens on that date's month with that day's detail dialog already showing,
 * instead of the current month - so tapping the card visibly lands you on the right day, not just the
 * right screen.
 * @param highlightStartEpochDay @param highlightEndEpochDay Feature 1: when both are set (from
 * "Longest streak" cards), every day in this inclusive range is painted with a distinct highlight -
 * see [DayCell]'s `isStreakHighlight` - separate from the normal together-day dot marking, so it's
 * visually clear this is "the streak being shown" rather than just another together day.
 */
@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    jumpToEpochDay: Long? = null,
    highlightStartEpochDay: Long? = null,
    highlightEndEpochDay: Long? = null,
    // UX-FIX-PLAN.md Phase 3 item 20: the reverse of Moments' own day-header -> Calendar link - lets the
    // day-detail dialog below jump straight to Moments, scrolled to this same date, when that day
    // actually has photos.
    onOpenMoments: (jumpToEpochDay: Long) -> Unit = {}
) {
    val vm: CalendarViewModel = viewModel(factory = SimpleViewModelFactory { CalendarViewModel() })
    val sessions by vm.sessions.collectAsState()
    val moments by vm.moments.collectAsState()
    val dayNotes by vm.dayNotes.collectAsState()
    val proximityState by vm.proximityState.collectAsState()
    val zone = remember { ZoneId.systemDefault() }

    val jumpToDate = remember(jumpToEpochDay) { jumpToEpochDay?.let { LocalDate.ofEpochDay(it) } }
    // A plain Pair rather than a java.time ClosedRange - LocalDate implements Comparable<ChronoLocalDate>
    // rather than Comparable<LocalDate>, which the stdlib's generic `a..b` rangeTo operator can't use
    // directly. Membership below is checked by hand instead.
    val highlightRange = remember(highlightStartEpochDay, highlightEndEpochDay) {
        if (highlightStartEpochDay != null && highlightEndEpochDay != null) {
            LocalDate.ofEpochDay(highlightStartEpochDay) to LocalDate.ofEpochDay(highlightEndEpochDay)
        } else null
    }
    // The highlight range takes priority over a plain jump-to-date when both would apply (they never
    // do in practice from how Stats wires these, but this keeps the initial month deterministic either
    // way): jump to whichever one was actually requested.
    var yearMonth by remember { mutableStateOf(YearMonth.from(highlightRange?.first ?: jumpToDate ?: LocalDate.now(zone))) }
    var selectedDay by remember { mutableStateOf(jumpToDate) }
    var showAddDialog by remember { mutableStateOf(false) }
    // Only ever set for an isManual session (see the delete IconButton below, which is only rendered
    // for those rows) - a genuine BLE-detected session has no way to reach this state at all.
    var sessionPendingDelete by remember { mutableStateOf<TogetherSession?>(null) }
    // UX-FIX-PLAN.md Phase 3 item 19: same isManual-only reachability as sessionPendingDelete above -
    // set only by the edit IconButton, which is only ever rendered for isManual rows.
    var sessionPendingEdit by remember { mutableStateOf<TogetherSession?>(null) }

    // lastSeenAt clamps an open session's live duration so a stale/orphaned open session can't inflate
    // day totals - see StatsCalculator.effectiveOpenSessionEnd's doc.
    val minutesPerDay = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.buildDailyMinuteMap(sessions, zone = zone, lastSeenAt = proximityState.lastSeenAt)
    }
    val daysWithPhotos = remember(moments) {
        moments.map { Instant.ofEpochMilli(it.takenAt).atZone(zone).toLocalDate() }.toSet()
    }
    val daysWithManualEntry = remember(sessions) {
        sessions.filter { it.isManual }.map { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }.toSet()
    }
    // UX-FIX-PLAN.md Phase 4 item 25: [DayNote.date] is already an epoch-day Long, so this is a plain
    // wrap rather than a zone-derived conversion like the maps above.
    val daysWithNotes = remember(dayNotes) {
        dayNotes.map { LocalDate.ofEpochDay(it.date) }.toSet()
    }

    // Reuses StatsCalculator's qualifying-day count (same buildDailyMinuteMap() call above feeds it)
    // rather than re-deriving "day with any together-time" locally, so this can never drift from the
    // "days together" stat shown on the Stats/Home screens.
    val totalDaysTogether = remember(sessions, proximityState.lastSeenAt) {
        StatsCalculator.compute(sessions, zone = zone, lastSeenAt = proximityState.lastSeenAt).totalDaysTogether
    }
    // Membership in minutesPerDay (not minutes >= 1) is what "qualifies" a day - see StatsCalculator's
    // buildDailyMinuteMap: every key it inserts already had a real positive-duration segment
    // (segmentEnd > cursor), even if that segment floors to under a whole minute. Matching that
    // criterion here (rather than an independent "minutes >= 1" check) is what keeps this screen's
    // day-dots and totalDaysTogether from ever drifting apart from Stats/Home's streak calculation.
    val daysThisMonth = remember(minutesPerDay, yearMonth) {
        minutesPerDay.keys.count { YearMonth.from(it) == yearMonth }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Calendar") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add past time together")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("$totalDaysTogether", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        // MINOR fix (deferred-minors, fixed per explicit user request): was a static
                        // "days" label, reading "1 days together ever" - singular for exactly 1.
                        Text(
                            if (totalDaysTogether == 1) "day together ever" else "days together ever",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Card(
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("$daysThisMonth", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("this month", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { yearMonth = yearMonth.minusMonths(1) }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous month")
                }
                Text(
                    text = yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + yearMonth.year,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = { yearMonth = yearMonth.plusMonths(1) }) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Next month")
                }
            }

            val firstOfMonth = yearMonth.atDay(1)
            val daysInMonth = yearMonth.lengthOfMonth()
            val firstDayOffset = (firstOfMonth.dayOfWeek.value % 7) // Sunday=0..Saturday=6 (dayOfWeek.value: Mon=1..Sun=7)

            LazyVerticalGrid(columns = GridCells.Fixed(7), modifier = Modifier.padding(top = 12.dp)) {
                items(listOf("S", "M", "T", "W", "T", "F", "S")) { label ->
                    Box(modifier = Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                items(firstDayOffset) {
                    Box(modifier = Modifier.aspectRatio(1f))
                }
                items(daysInMonth) { index ->
                    val day = firstOfMonth.plusDays(index.toLong())
                    val minutes = minutesPerDay[day] ?: 0L
                    val hasTogetherTime = day in minutesPerDay
                    val hasPhoto = day in daysWithPhotos
                    val hasManualEntry = day in daysWithManualEntry
                    val hasNote = day in daysWithNotes
                    val isStreakHighlight = highlightRange != null &&
                        !day.isBefore(highlightRange.first) && !day.isAfter(highlightRange.second)
                    DayCell(
                        day = day,
                        hasTogetherTime = hasTogetherTime,
                        hasPhoto = hasPhoto,
                        hasManualEntry = hasManualEntry,
                        hasNote = hasNote,
                        isToday = day == LocalDate.now(zone),
                        isStreakHighlight = isStreakHighlight,
                        onClick = { selectedDay = day }
                    )
                }
            }

            // Feature 1: only shown when Stats deep-linked here to show off a specific streak - explains
            // what the distinct highlight color means, right where it's visible.
            if (highlightRange != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(MaterialTheme.colorScheme.tertiary)
                    )
                    Text(
                        "Your streak",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }

    selectedDay?.let { day ->
        val minutes = minutesPerDay[day] ?: 0L
        val daySessions = remember(day, sessions, proximityState.lastSeenAt) {
            sessionsOverlapping(sessions, day, zone, proximityState.lastSeenAt)
        }
        // UX-FIX-PLAN.md Phase 3 item 20: how many Moments were actually taken on this day - drives the
        // "View N photos from this day" row below, only shown when there's something to jump to. Reuses
        // the same day-grouping key (zone-local LocalDate off takenAt) [daysWithPhotos] above already
        // uses, so this can never disagree with that dot marker about which days "have a photo".
        val dayMomentsCount = remember(day, moments) {
            moments.count { Instant.ofEpochMilli(it.takenAt).atZone(zone).toLocalDate() == day }
        }
        AlertDialog(
            onDismissRequest = { selectedDay = null },
            confirmButton = { TextButton(onClick = { selectedDay = null }) { Text("Close") } },
            // BUG fix (Phase 1 item 1 of UX-FIX-PLAN.md): was "EEEE, MMM d" with no year at all - a real
            // bug when this dialog is reached via a deep link into a PAST year (e.g. Stats' "Longest
            // single day"/"Together since" cards), where the missing year made the title ambiguous.
            title = { Text(DateFormats.formatDateWithWeekday(day)) },
            text = {
                Column {
                    Text(if (minutes > 0) "${minutes / 60}h ${minutes % 60}m together that day" else "No time together that day")
                    if (daySessions.isEmpty()) {
                        Text("No sessions", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    } else {
                        daySessions.forEach { s ->
                            val start = Instant.ofEpochMilli(s.startedAt).atZone(zone).toLocalTime()
                            val end = s.endedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
                            val manualTag = if (s.isManual) " (added manually)" else ""
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "• ${DateFormats.formatTime(start)} – ${end?.let { DateFormats.formatTime(it) } ?: "now"}$manualTag",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                // Edit/delete are only ever offered for a manually-backfilled entry - a
                                // genuine BLE-detected session is the app's real historical record and
                                // must stay untouchable, so neither icon is even rendered for it.
                                if (s.isManual) {
                                    IconButton(
                                        onClick = { sessionPendingEdit = s },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Edit,
                                            contentDescription = "Edit this entry",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { sessionPendingDelete = s },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "Delete this entry",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // UX-FIX-PLAN.md Phase 4 item 25: the day-note field itself - see DayNoteSection's
                    // own doc for why this reuses MomentsScreen's MomentNotesSection interaction shape.
                    DayNoteSection(date = day.toEpochDay())
                    // UX-FIX-PLAN.md Phase 3 item 20: Calendar day -> that day's Moments. Only shown when
                    // this day genuinely has photos, matching the day-cell's own HAS_PHOTO marker.
                    if (dayMomentsCount > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .clickable(onClick = { onOpenMoments(day.toEpochDay()) })
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (dayMomentsCount == 1) "View 1 photo from this day" else "View $dayMomentsCount photos from this day",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Icon(Icons.Filled.ChevronRight, contentDescription = null)
                        }
                    }
                }
            }
        )
    }

    if (showAddDialog) {
        AddManualSessionDialog(
            zone = zone,
            existing = null,
            onDismiss = { showAddDialog = false },
            onSave = { startedAt, endedAt ->
                vm.addManualSession(startedAt, endedAt)
                showAddDialog = false
            }
        )
    }

    // UX-FIX-PLAN.md Phase 3 item 19: same AddManualSessionDialog, reopened pre-filled with the existing
    // session's start/end - see the dialog's own doc for how [existing] drives both the pre-fill and the
    // Add-vs-Save copy.
    sessionPendingEdit?.let { session ->
        AddManualSessionDialog(
            zone = zone,
            existing = session,
            onDismiss = { sessionPendingEdit = null },
            onSave = { startedAt, endedAt ->
                vm.updateManualSession(session, startedAt, endedAt)
                sessionPendingEdit = null
            }
        )
    }

    sessionPendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionPendingDelete = null },
            title = { Text("Delete this entry?") },
            text = { Text("This can't be undone, and will be removed for both of you once you next sync.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteManualSession(session); sessionPendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { sessionPendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    hasTogetherTime: Boolean,
    hasPhoto: Boolean,
    hasManualEntry: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    isStreakHighlight: Boolean = false,
    // UX-FIX-PLAN.md Phase 4 item 25: whether this day has a (mine or partner's) day note - rendered as
    // its own small marker, distinct from both the photo-camera icon above and hasManualEntry's dot.
    hasNote: Boolean = false
) {
    // isStreakHighlight (Feature 1: "this is the streak Stats sent you to look at") is deliberately a
    // DIFFERENT visual channel than isToday's fill - a tertiary border, so a highlighted streak day that
    // also happens to be today still clearly shows both signals rather than one hiding the other.
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (isToday) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .then(
                if (isStreakHighlight) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.medium)
                } else Modifier
            )
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${day.dayOfMonth}", style = MaterialTheme.typography.bodyMedium)
            if (hasTogetherTime) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            // UX-FIX-PLAN.md Phase 2 item 13: the photo marker used to be nested inside `hasTogetherTime`
            // and rendered when `!hasPhoto` - i.e. a "no photo yet" icon on together-days WITHOUT a photo,
            // and NOTHING AT ALL on days that actually have one (the primary bug), including every
            // gallery-imported photo on a day with no together-time. photoMarkerState is a plain function
            // (not inlined) so this logic is unit-testable without Compose test infra - see
            // CalendarScreenTest.
            when (photoMarkerState(hasPhoto = hasPhoto, hasTogetherTime = hasTogetherTime)) {
                PhotoMarkerState.HAS_PHOTO -> Icon(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = "Has a photo",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 1.dp).size(9.dp)
                )
                PhotoMarkerState.PROMPT_NO_PHOTO -> Icon(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = "No photo yet",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.padding(top = 1.dp).size(9.dp)
                )
                PhotoMarkerState.NONE -> {}
            }
        }
        if (hasManualEntry) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 3.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary)
            )
        }
        // UX-FIX-PLAN.md Phase 4 item 25: same small-corner-dot convention as hasManualEntry's marker
        // just above, deliberately placed in a DIFFERENT corner with a DIFFERENT color (secondary, not
        // tertiary) so a day carrying both a manual entry and a note still shows two distinguishable
        // marks rather than one hiding the other - same "different visual channel" reasoning
        // isStreakHighlight's own doc gives for its border vs. isToday's fill.
        if (hasNote) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 3.dp, end = 3.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary)
            )
        }
    }
}

/** UX-FIX-PLAN.md Phase 2 item 13: which photo-camera marker (if any) a day cell should show, extracted
 * as a plain pure function purely so the fixed logic is unit-testable without any Compose UI test infra -
 * see CalendarScreenTest. [HAS_PHOTO] (a solid marker) always wins when the day actually has a photo,
 * regardless of together/apart status - this is the fix for the primary bug (a photo-day used to render
 * NOTHING). [PROMPT_NO_PHOTO] (the old faded "no photo yet" prompt) is kept for a together-day that has
 * no photo yet, since that's still a useful nudge; [NONE] otherwise (an apart-day with no photo). */
internal enum class PhotoMarkerState { HAS_PHOTO, PROMPT_NO_PHOTO, NONE }

internal fun photoMarkerState(hasPhoto: Boolean, hasTogetherTime: Boolean): PhotoMarkerState = when {
    hasPhoto -> PhotoMarkerState.HAS_PHOTO
    hasTogetherTime -> PhotoMarkerState.PROMPT_NO_PHOTO
    else -> PhotoMarkerState.NONE
}

/**
 * UX-FIX-PLAN.md Phase 4 item 25: "Your note" (editable, saved locally - not yet synced out to the
 * partner, see DayNoteRepository.mergeRemote's own doc for why) and, if the partner has already written
 * one for this same day, "{partner}'s note" shown read-only right below it.
 *
 * Deliberately reuses MomentsScreen's MomentNotesSection interaction shape almost line-for-line (an
 * already-saved note of yours renders as a read-only card ABOVE the input with an explicit Edit
 * affordance - see that composable's own doc for the Phase 1 item 7 "saved but doesn't appear" bug this
 * shape fixes) rather than inventing a different pattern for this new but structurally identical
 * per-author-note feature. The one deliberate difference: no `AppEvents.requestManualSync()` nudge after
 * saving - DayNote has no wire-protocol support yet (see this feature's own scope note), so there is
 * nothing for a manual sync to actually send for this entity yet.
 */
@Composable
private fun DayNoteSection(date: Long) {
    val coroutineScope = rememberCoroutineScope()
    var myDeviceId by remember { mutableStateOf<String?>(null) }
    var partnerName by remember { mutableStateOf("Your partner") }
    // Nullable, same reasoning as MomentNotesSection's own `notes` state: null means "the DB has not
    // answered yet", emptyList() means "answered: there are none" - collapsing that distinction is what
    // caused the data-loss bug MomentNotesSection's doc describes.
    var notes by remember { mutableStateOf<List<DayNote>?>(null) }
    var myText by remember { mutableStateOf("") }
    var initializedMyText by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var isEditingMine by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        myDeviceId = ServiceLocator.settingsStore.getOrCreateLocalDeviceId()
        partnerName = ServiceLocator.pairingStore.current().partnerName
    }
    LaunchedEffect(date) {
        ServiceLocator.dayNoteRepository.observeForDate(date).collect { notes = it }
    }
    val myExisting = remember(notes, myDeviceId) {
        notes?.firstOrNull { it.authorDeviceId == myDeviceId && !it.deleted }
    }
    // Seed the editable field from whatever's already stored for "me" exactly once per day, only once
    // BOTH prerequisites (myDeviceId, notes) have genuinely resolved - see MomentNotesSection's own doc
    // for the full data-loss scenario this latch-guard prevents (a too-early latch capturing an empty
    // draft, which saveMyNote's blank-means-tombstone semantics would then silently commit as a delete).
    LaunchedEffect(date, myDeviceId, notes) {
        if (!initializedMyText && myDeviceId != null && notes != null) {
            myText = myExisting?.text.orEmpty()
            initializedMyText = true
        }
    }
    val partnerNote = remember(notes, myDeviceId) {
        if (myDeviceId == null) null else notes?.firstOrNull { it.authorDeviceId != myDeviceId && !it.deleted }
    }
    val hasSavedMyNote = myExisting?.text?.isNotBlank() == true
    val showEditableField = initializedMyText && (!hasSavedMyNote || isEditingMine)

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Own saved note - read-only, ABOVE the input, with an explicit Edit affordance.
        if (hasSavedMyNote && !isEditingMine) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Your note", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { isEditingMine = true; saved = false }) { Text("Edit") }
                }
                Card(shape = MaterialTheme.shapes.medium) {
                    Text(
                        myExisting?.text.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        if (partnerNote != null && partnerNote.text.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$partnerName's note", style = MaterialTheme.typography.titleSmall)
                Card(shape = MaterialTheme.shapes.medium) {
                    Text(
                        partnerNote.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        if (showEditableField) {
            Text("Your note", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = myText,
                onValueChange = { myText = it; saved = false },
                placeholder = { Text("Jot something about this day…") },
                modifier = Modifier.fillMaxWidth(),
                // Same "don't let the field be editable before seeding resolves" guard as
                // MomentNotesSection - see its own doc for the race this closes.
                enabled = initializedMyText,
                minLines = 2
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (hasSavedMyNote) {
                    TextButton(onClick = {
                        myText = myExisting?.text.orEmpty()
                        isEditingMine = false
                    }) { Text("Cancel") }
                }
                TextButton(
                    enabled = initializedMyText,
                    onClick = {
                        val deviceId = myDeviceId ?: return@TextButton
                        coroutineScope.launch {
                            ServiceLocator.dayNoteRepository.saveMyNote(date, deviceId, myText)
                            saved = true
                            isEditingMine = false
                        }
                    }
                ) { Text(if (saved) "Saved ✓" else "Save note") }
            }
        }
    }
}

private fun sessionsOverlapping(sessions: List<TogetherSession>, day: LocalDate, zone: ZoneId, lastSeenAt: Long): List<TogetherSession> {
    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val now = System.currentTimeMillis()
    return sessions.filter { s ->
        // Same clamp as StatsCalculator.effectiveOpenSessionEnd: an open session's displayed end must
        // never be credited past its newest confirmed sighting + the absence timeout. Computed per row
        // because the no-lastSeenAt fallback is bounded by that row's own startedAt.
        val end = s.endedAt ?: StatsCalculator.effectiveOpenSessionEnd(s.startedAt, now, lastSeenAt)
        s.startedAt < dayEnd && end > dayStart
    }
}

/**
 * "Add past time together" (or, per [existing], edit an already-existing one): backfills/edits a
 * TogetherSession for a chosen date the couple was together before installing the app (or a day BLE
 * detection missed).
 *
 * UX-FIX-PLAN.md Phase 3 items 18+19 (done together deliberately, per the plan's own note that both touch
 * this same dialog): redesigned from a date-text-field + duration(hours/minutes) shape to a native
 * date/start-time/end-time picker shape - both so the free-text YYYY-MM-DD field can be replaced with
 * [DatePickerField]/[TimePickerField] (item 18), AND because representing the session as real start/end
 * times (rather than "midnight + a duration") is what makes pre-filling this same dialog with an EXISTING
 * session's actual bounds for editing (item 19) meaningful - a manual session created via
 * GalleryImportFlow's own "we were together" companion entry already has real start/end times, not a
 * midnight anchor, so editing it here must preserve that shape rather than silently shifting its start to
 * midnight the moment someone edits an unrelated field. [existing] null means "add"; non-null means "edit",
 * pre-filling date/start/end from its startedAt/endedAt and switching the title/confirm-button copy.
 *
 * Still capped at one calendar day (end time must be on the same date, after the start time) - same
 * invariant the old midnight+duration shape enforced structurally, now enforced by validation instead
 * since a picker can't express "spans past midnight" here in the first place.
 */
@Composable
private fun AddManualSessionDialog(
    zone: ZoneId,
    existing: TogetherSession? = null,
    onDismiss: () -> Unit,
    onSave: (startedAt: Long, endedAt: Long) -> Unit
) {
    val today = remember { LocalDate.now(zone) }
    val existingStart = remember(existing) { existing?.let { Instant.ofEpochMilli(it.startedAt).atZone(zone) } }
    val existingEnd = remember(existing) { existing?.endedAt?.let { Instant.ofEpochMilli(it).atZone(zone) } }

    var date by remember { mutableStateOf(existingStart?.toLocalDate() ?: today) }
    var startTime by remember { mutableStateOf(existingStart?.toLocalTime() ?: LocalTime.of(9, 0)) }
    var endTime by remember { mutableStateOf(existingEnd?.toLocalTime() ?: LocalTime.of(10, 0)) }

    // MINOR fix (test-code-allmodels final clean-room pass, Opus): this dialog previously had no lower
    // date bound - a pre-2020 entry inserted with no error, but SessionBoundsValidator.
    // MIN_PLAUSIBLE_TIMESTAMP_MILLIS (the same floor every untrusted wire/backup timestamp is already
    // checked against) silently rejects it everywhere else: StatsCalculator's own merge excludes it from
    // every stat, deserializeSessions drops it from the sync payload, and a restored backup drops it too
    // (SessionBoundsValidator.isPlausible). The row would sit in the local DB forever contributing to
    // nothing while the Calendar day-detail view showed a self-contradictory "manual entry, but no time
    // together that day." Now enforced structurally too (item 18): [DatePickerField]'s own minDate/maxDate
    // make an out-of-range day un-tappable in the first place, with the error text kept as a backstop.
    val minPlausibleDate = remember {
        Instant.ofEpochMilli(SessionBoundsValidator.MIN_PLAUSIBLE_TIMESTAMP_MILLIS).atZone(zone).toLocalDate()
    }

    // For a same-day (today) entry, an end time later than the actual current wall-clock time must be
    // rejected rather than silently clipped - same reasoning as this dialog always had, just re-expressed
    // against a real end TIME instead of a duration-from-midnight.
    val nowTimeToday = remember(date, today) { if (date == today) LocalTime.now(zone) else null }

    val error: String? = when {
        date.isAfter(today) -> "Date can't be in the future"
        date.isBefore(minPlausibleDate) -> "Date can't be before ${DateFormats.formatDate(minPlausibleDate)}"
        !endTime.isAfter(startTime) -> "End time must be after start time"
        nowTimeToday != null && endTime.isAfter(nowTimeToday) -> "End time can't be later than the current time"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add past time together" else "Edit time together") },
        text = {
            Column {
                Text(
                    "For time together before you installed the app, or a day it missed.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                DatePickerField(
                    label = "Date",
                    date = date,
                    onDateChange = { date = it },
                    minDate = minPlausibleDate,
                    maxDate = today,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimePickerField(
                        label = "Start",
                        time = startTime,
                        onTimeChange = { startTime = it },
                        modifier = Modifier.weight(1f)
                    )
                    TimePickerField(
                        label = "End",
                        time = endTime,
                        onTimeChange = { endTime = it },
                        modifier = Modifier.weight(1f)
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
                onClick = {
                    val startedAt = date.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
                    val endedAt = date.atTime(endTime).atZone(zone).toInstant().toEpochMilli()
                    onSave(startedAt, endedAt)
                }
            ) { Text(if (existing == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
