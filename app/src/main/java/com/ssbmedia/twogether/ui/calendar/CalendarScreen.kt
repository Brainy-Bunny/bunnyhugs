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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import com.ssbmedia.twogether.util.SessionBoundsValidator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale

class CalendarViewModel : ViewModel() {
    val sessions = ServiceLocator.sessionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val moments = ServiceLocator.momentRepository.observeAll()
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
    highlightEndEpochDay: Long? = null
) {
    val vm: CalendarViewModel = viewModel(factory = SimpleViewModelFactory { CalendarViewModel() })
    val sessions by vm.sessions.collectAsState()
    val moments by vm.moments.collectAsState()
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
                    val isStreakHighlight = highlightRange != null &&
                        !day.isBefore(highlightRange.first) && !day.isAfter(highlightRange.second)
                    DayCell(
                        day = day,
                        hasTogetherTime = hasTogetherTime,
                        hasPhoto = hasPhoto,
                        hasManualEntry = hasManualEntry,
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
        AlertDialog(
            onDismissRequest = { selectedDay = null },
            confirmButton = { TextButton(onClick = { selectedDay = null }) { Text("Close") } },
            title = { Text(day.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))) },
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
                                    "• ${start.format(DateTimeFormatter.ofPattern("h:mm a"))} – ${end?.format(DateTimeFormatter.ofPattern("h:mm a")) ?: "now"}$manualTag",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                // Delete is only ever offered for a manually-backfilled entry - a genuine
                                // BLE-detected session is the app's real historical record and must stay
                                // untouchable, so no delete icon is even rendered for it.
                                if (s.isManual) {
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
                }
            }
        )
    }

    if (showAddDialog) {
        AddManualSessionDialog(
            zone = zone,
            onDismiss = { showAddDialog = false },
            onAdd = { startedAt, endedAt ->
                vm.addManualSession(startedAt, endedAt)
                showAddDialog = false
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
    isStreakHighlight: Boolean = false
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
                if (!hasPhoto) {
                    Icon(
                        imageVector = Icons.Filled.PhotoCamera,
                        contentDescription = "No photo yet",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.padding(top = 1.dp).size(9.dp)
                    )
                }
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

/** "Add past time together": backfills a TogetherSession for a chosen date the couple was together
 * before installing the app (or a day BLE detection missed). Anchors the session at that date's
 * midnight so it lands entirely within the chosen calendar day - duration is capped at 24h so it can
 * never spill into the next day. */
@Composable
private fun AddManualSessionDialog(zone: ZoneId, onDismiss: () -> Unit, onAdd: (startedAt: Long, endedAt: Long) -> Unit) {
    val today = remember { LocalDate.now(zone) }
    var dateText by remember { mutableStateOf(today.format(DateTimeFormatter.ISO_LOCAL_DATE)) }
    var hoursText by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("") }

    val parsedDate = remember(dateText) {
        try { LocalDate.parse(dateText, DateTimeFormatter.ISO_LOCAL_DATE) } catch (e: DateTimeParseException) { null }
    }
    val hours = hoursText.toIntOrNull() ?: 0
    val minutes = minutesText.toIntOrNull() ?: 0
    val totalMinutes = hours * 60 + minutes

    // For a same-day (today) entry, a duration longer than what's actually elapsed since local
    // midnight used to get silently clipped down to whatever fit (see onAdd's endedAt clamp below) -
    // no warning, and duration is degenerate the "day" a same-day entry lands right on the elapsed
    // minute (e.g. rounds down to 0), the repository's require(endedAt > startedAt) would throw
    // uncaught inside a viewModelScope.launch, crashing the app. Validate up front instead so the user
    // sees a clear error and the dialog can't submit a value that would need clipping at all.
    val elapsedMinutesToday = remember(parsedDate, today) {
        if (parsedDate == today) {
            ((System.currentTimeMillis() - today.atStartOfDay(zone).toInstant().toEpochMilli()) / 60_000L)
                .coerceAtLeast(0L)
        } else null
    }

    // MINOR fix (test-code-allmodels final clean-room pass, Opus): this dialog previously had no lower
    // date bound - a pre-2020 entry inserted with no error, but SessionBoundsValidator.
    // MIN_PLAUSIBLE_TIMESTAMP_MILLIS (the same floor every untrusted wire/backup timestamp is already
    // checked against) silently rejects it everywhere else: StatsCalculator's own merge excludes it from
    // every stat, deserializeSessions drops it from the sync payload, and a restored backup drops it too
    // (SessionBoundsValidator.isPlausible). The row would sit in the local DB forever contributing to
    // nothing while the Calendar day-detail view showed a self-contradictory "manual entry, but no time
    // together that day." Validated up front here instead, using the exact same floor.
    val minPlausibleDate = remember {
        java.time.Instant.ofEpochMilli(SessionBoundsValidator.MIN_PLAUSIBLE_TIMESTAMP_MILLIS).atZone(zone).toLocalDate()
    }
    val error: String? = when {
        parsedDate == null -> "Enter a valid date as YYYY-MM-DD"
        parsedDate.isAfter(today) -> "Date can't be in the future"
        parsedDate.isBefore(minPlausibleDate) -> "Date can't be before $minPlausibleDate"
        totalMinutes <= 0 -> "Enter a duration greater than zero"
        totalMinutes > 24 * 60 -> "Can't be more than 24 hours in one day"
        elapsedMinutesToday != null && totalMinutes > elapsedMinutesToday ->
            "Only ${elapsedMinutesToday / 60}h ${elapsedMinutesToday % 60}m has passed today - enter a shorter duration"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add past time together") },
        text = {
            Column {
                Text(
                    "For time together before you installed the app, or a day it missed.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it.trim() },
                    label = { Text("Date (YYYY-MM-DD)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hoursText,
                        onValueChange = { hoursText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Hours") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Minutes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                    val date = parsedDate ?: return@TextButton
                    val startedAt = date.atStartOfDay(zone).toInstant().toEpochMilli()
                    val rawEndedAt = startedAt + totalMinutes * 60_000L
                    // Anchored at midnight, not "now" - for today, an entered duration longer than the
                    // elapsed part of the day would otherwise produce an endedAt in the future. Clip it.
                    val endedAt = minOf(rawEndedAt, System.currentTimeMillis())
                    onAdd(startedAt, endedAt)
                }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
