package com.ssbmedia.twogether.ui.moments

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.data.datastore.AppSettings
import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.ui.components.DatePickerField
import com.ssbmedia.twogether.ui.components.TimePickerField
import com.ssbmedia.twogether.util.ImageDownscaler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * Backfill entry point: pick an EXISTING photo from the gallery and attach it to a PAST date, as
 * opposed to CameraScreen's live capture which always stamps "now". Deliberately mirrors that flow's
 * storage contract exactly, so the resulting Moment is indistinguishable from a camera-captured one to
 * everything downstream (MomentsScreen's grouping, CalendarScreen's day dots, GattSyncManager's sync,
 * BackupManager's backup/restore):
 *  - uses the modern system Photo Picker (ActivityResultContracts.PickVisualMedia) - no new runtime
 *    permission needed (unlike READ_MEDIA_IMAGES), matching this app's permission-minimizing philosophy;
 *  - copies the picked photo's bytes into this app's own private filesDir/moments/ dir (same directory
 *    CameraScreen's ImageCapture writes into) rather than storing the picked content:// Uri - a content
 *    Uri's read grant can be revoked/invalidated later and can't be pushed to the partner's phone the way
 *    a real local file can via GattSyncManager's photo-transfer phase;
 *  - inserts via the same MomentRepository.add(...) camera capture uses, with sessionId always null
 *    (a backfilled photo was never verified as taken during a tracked together-session, unlike a live
 *    capture which tags the currently-open session if there is one) and the user-chosen takenAt instead
 *    of "now" - isRemote/photoDownloaded both default to false/true respectively (see Moment's doc),
 *    exactly like a camera capture, so no special-casing is needed anywhere in the sync path.
 *
 * [content] is a slot so both MomentsScreen (gallery FAB) and CameraScreen ("choose from gallery
 * instead" link) can trigger this same picker + copy + date-assignment flow from their own UI without
 * duplicating any of this logic; it receives a `launch` callback to open the picker.
 */
@Composable
fun GalleryImportHost(
    onImported: () -> Unit,
    content: @Composable (launch: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by ServiceLocator.settingsStore.settings.collectAsState(initial = AppSettings())
    val dayStartHour = settings.dayStartHour
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var suggestedDate by remember { mutableStateOf<LocalDate?>(null) }
    // UX-FIX-PLAN.md Phase 2 item 12: the photo's own EXIF time-of-day (if it had one), kept separate
    // from [suggestedDate] since the date field is user-editable text but the time isn't shown/edited
    // anywhere in this dialog - this is purely carried through to takenAt at save time. Null means EXIF
    // had no time component (or no EXIF at all), in which case takenAt falls back to noon, same as before.
    var exifTime by remember { mutableStateOf<LocalTime?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    // BUG fix: a copy failure (storage full, a revoked/expired content:// grant, etc - see
    // copyPickedImageToMomentsDir's own doc for why it returns null rather than throwing) used to just
    // silently close this dialog with isSaving reset and nothing else - no error, no moment added, no
    // indication anything had even been attempted. An independent audit round flagged this as the one
    // gallery-import failure path with zero user-visible feedback, unlike every other failure path in
    // this app (setPin, backup restore, etc) which all show something.
    var saveError by remember { mutableStateOf<String?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            pendingUri = uri
            val exifDateTime = readExifDateTime(context, uri)
            suggestedDate = exifDateTime?.date
            exifTime = exifDateTime?.time
            saveError = null
        }
    }

    content {
        pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    pendingUri?.let { uri ->
        BackfillPhotoDateDialog(
            suggestedDate = suggestedDate,
            dayStartHour = dayStartHour,
            isSaving = isSaving,
            saveError = saveError,
            onDismiss = { if (!isSaving) pendingUri = null },
            onConfirm = { date, togetherRange ->
                isSaving = true
                saveError = null
                scope.launch {
                    val savedFile = withContext(Dispatchers.IO) { copyPickedImageToMomentsDir(context, uri) }
                    if (savedFile != null) {
                        val zone = ZoneId.systemDefault()
                        // UX-FIX-PLAN.md Phase 2 item 12 (fix 1 of 2): keep the photo's real EXIF
                        // time-of-day instead of always hardcoding noon - see resolveTakenAtMillis' doc.
                        val takenAt = resolveTakenAtMillis(date, exifTime, zone)

                        // Optional "we were together" companion entry (owner-requested addition): a
                        // backfilled photo alone was deliberately never enough on its own to create
                        // together-time (a photo isn't proof the whole day was spent together - see
                        // CalendarScreen's separate hasPhoto/hasTogetherTime day markers), so this stays
                        // an explicit opt-in rather than something inferred automatically from the photo
                        // existing. addManualSession is the SAME function CalendarScreen's own backfill
                        // dialog uses - isManual=true, counts toward stats/streaks like any other
                        // manual entry, excluded from Time Capsule eligibility by the same anti-cheat
                        // rule as every other manual session, syncs to the partner the same way.
                        //
                        // UX-FIX-PLAN.md Phase 2 item 12 (fix 2 of 2): created BEFORE the sessionContaining
                        // lookup below (not after, as this used to be ordered) - the just-created session
                        // must already be visible to that lookup, since a "we were together" range is
                        // exactly the kind of session a photo taken that same day should match against.
                        val manualSessionId = togetherRange?.let { range ->
                            ServiceLocator.sessionRepository.addManualSession(range.first, range.second)
                        }

                        // Auto-detect "taken while together" instead of always defaulting to apart: any
                        // EXISTING session (BLE-detected or manual, including the one just created above)
                        // whose window actually contains this photo's real takenAt wins first; if none
                        // does but the user explicitly ticked "we were together" for this exact photo, that
                        // explicit answer is trusted as a fallback even if the exact from/to typed times
                        // don't perfectly bracket the EXIF timestamp (e.g. rounding) - see
                        // resolveImportedPhotoSessionId's own doc.
                        val allSessions = ServiceLocator.sessionRepository.getAll()
                        val lastSeenAt = ServiceLocator.proximityStateStore.current().lastSeenAt
                        val sessionIdForMoment = resolveImportedPhotoSessionId(
                            sessions = allSessions,
                            takenAt = takenAt,
                            manualSessionId = manualSessionId,
                            lastSeenAt = lastSeenAt
                        )
                        ServiceLocator.momentRepository.add(savedFile.absolutePath, sessionIdForMoment, takenAt)

                        // Same reasoning as CameraScreen's onImageSaved and CalendarScreen's
                        // addManualSession - don't make a freshly-backfilled photo (and any companion
                        // together-time entry) wait for the next reconnect/15-minute catch-all if we're
                        // already together right now.
                        if (ServiceLocator.proximityStateStore.current().isTogether) {
                            AppEvents.requestManualSync()
                        }
                    }
                    isSaving = false
                    if (savedFile != null) {
                        pendingUri = null
                        onImported()
                    } else {
                        // BUG fix: see saveError's own doc - dialog stays open (not silently dismissed)
                        // so the user knows the import failed and can retry or cancel explicitly.
                        saveError = "Couldn't import that photo - try again"
                    }
                }
            }
        )
    }
}

/** UX-FIX-PLAN.md Phase 2 item 12: builds the real takenAt instant for a backfilled photo from the
 * user-confirmed date plus the photo's own EXIF time-of-day, falling back to noon only when EXIF had no
 * time component at all (no EXIF, or a tag that failed to parse) - previously this always hardcoded noon
 * regardless of what the photo's own metadata said. A plain top-level function (not inlined into the
 * Composable) so it's unit-testable without any Compose/Android test dependency. */
internal fun resolveTakenAtMillis(date: LocalDate, exifTime: LocalTime?, zone: ZoneId): Long =
    date.atTime(exifTime ?: LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()

/** UX-FIX-PLAN.md Phase 2 item 12: resolves which [TogetherSession] (if any) a gallery-imported photo
 * should be tagged with, so MomentsScreen's `moment.sessionId != null` check (see this file's top-of-file
 * doc) reports "taken while together" correctly instead of always defaulting to apart. Two ways to win,
 * either is sufficient:
 *  1. [takenAt] falls inside ANY existing session's window - [StatsCalculator.sessionContaining] already
 *     applies the same open-session clamp every other read in this app uses, so this can't be tricked by
 *     a stale/orphaned open session either.
 *  2. Falling back to [manualSessionId] - the session (if any) JUST created by this exact import's own
 *     "we were together" checkbox. This is the direct fix for the contradiction bug the plan calls out:
 *     the user explicitly said "we were together that day" and a real session now exists for that
 *     window, so the photo must not still read "Taken apart" even if its EXIF timestamp sits a few
 *     minutes outside the typed from/to range (rounding, a slightly-off phone clock, etc.) - the user's
 *     explicit answer for this exact photo is trusted over a strict boundary check.
 * A plain top-level function (not inlined) so both branches are independently unit-testable. */
internal fun resolveImportedPhotoSessionId(
    sessions: List<TogetherSession>,
    takenAt: Long,
    manualSessionId: Long?,
    now: Long = System.currentTimeMillis(),
    lastSeenAt: Long = 0L
): Long? {
    val matching = StatsCalculator.sessionContaining(sessions, takenAt, now, lastSeenAt)
    return matching?.id ?: manualSessionId
}

/** UX-FIX-PLAN.md Phase 2 item 12: the photo's own EXIF date, plus its time-of-day when the tag actually
 * carried one. [time] used to be discarded entirely (readExifDate/parseExifDateTime only ever returned a
 * bare LocalDate) - see resolveTakenAtMillis for where this now feeds into the real takenAt instead of a
 * hardcoded noon. */
private data class ExifDateTime(val date: LocalDate, val time: LocalTime)

/** Reads EXIF DateTimeOriginal (falling back to DateTime) off the picked image via a file descriptor
 * (rather than a raw ContentResolver InputStream) so seeking works reliably for formats that need it.
 * Returns null on any failure (missing tag, unreadable/corrupt EXIF, permission hiccup) - callers treat
 * that identically to "no EXIF date", defaulting the date field to today for the user to edit instead
 * (and, per resolveTakenAtMillis, the time to noon). */
private fun readExifDateTime(context: Context, uri: Uri): ExifDateTime? = try {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        val exif = ExifInterface(pfd.fileDescriptor)
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        raw?.let(::parseExifDateTime)
    }
} catch (e: Exception) {
    null
}

private fun parseExifDateTime(raw: String): ExifDateTime? = try {
    // EXIF's DateTime tags are "yyyy:MM:dd HH:mm:ss", not ISO - see the TIFF/EXIF spec. The tag always
    // carries both a date AND a time component in this format (there's no EXIF variant with a date only),
    // so a successful parse always yields a real time-of-day, not just a date.
    val parsed = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)
    parsed?.let {
        val localDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(it.time), ZoneId.systemDefault())
        ExifDateTime(localDateTime.toLocalDate(), localDateTime.toLocalTime())
    }
} catch (e: Exception) {
    null
}

/** Copies the picked photo's actual bytes into filesDir/moments/ - see this file's top-of-file doc for
 * why a plain content:// reference isn't good enough. Returns null (rather than throwing into the
 * caller's coroutine) if the source stream can't be opened or the copy fails partway (e.g. storage
 * full); the dialog simply stays open with isSaving reset so the user can retry or cancel. */
private fun copyPickedImageToMomentsDir(context: Context, uri: Uri): File? {
    return try {
        val outputDir = File(context.filesDir, "moments").apply { mkdirs() }
        val ext = extensionFor(context.contentResolver, uri)
        val outputFile = File(outputDir, "gallery_${System.currentTimeMillis()}.$ext")
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            outputFile.outputStream().use { output -> input.copyTo(output) }
            true
        } ?: false
        if (!copied) return null
        // MAJOR fix: downscale before this Moment is ever queued for BLE sync - see ImageDownscaler's
        // doc. Caller already runs this inside Dispatchers.IO.
        ImageDownscaler.downscaleIfNeeded(outputFile)
    } catch (e: Exception) {
        null
    }
}

private fun extensionFor(resolver: ContentResolver, uri: Uri): String {
    val mime = resolver.getType(uri)
    return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
}

/**
 * Date-assignment step of the backfill flow.
 *
 * UX-FIX-PLAN.md Phase 3 item 18: the date field was a raw YYYY-MM-DD free-text field, and the from/to
 * companion-entry fields were raw 24-hour H:MM free text - both replaced here with native
 * [DatePickerField]/[TimePickerField] (a picker can't produce a typo/unparseable value the way free text
 * could), matching the same shape CalendarScreen's AddManualSessionDialog now uses. Existing validation
 * (future dates rejected, a same-day "To" time later than right-now rejected) is kept as a backstop per
 * the plan's own instruction, even though the pickers themselves can no longer produce most of what it
 * used to catch.
 *
 * Also offers an OPT-IN "we were together" companion entry (owner-requested addition): checking it
 * reveals a from/to time-of-day range (both implicitly on the photo's own date - a photo is tied to one
 * specific day, so there's no separate date-range picker here, unlike CalendarScreen's general backfill
 * dialog), and [onConfirm]'s second parameter carries the resulting (startedAt, endedAt) millis pair for
 * the caller to hand to SessionRepository.addManualSession - or null if left unchecked, matching the
 * PRE-EXISTING default behavior where a backfilled photo alone never implied any together-time (see
 * CalendarScreen's separate hasPhoto/hasTogetherTime day markers - a photo isn't proof of a whole day
 * spent together, which is why this stays an explicit choice rather than being inferred automatically). */
@Composable
private fun BackfillPhotoDateDialog(
    suggestedDate: LocalDate?,
    dayStartHour: Int,
    isSaving: Boolean,
    saveError: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, Pair<Long, Long>?) -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    // The default suggestion is the logical day (so a late-night photo pre-fills the same day the app
    // counts it under, with the user's day-start setting). The future rule below still uses the calendar
    // today, same as CalendarScreen's backfill dialog, so a calendar-today entry before the day start is
    // never blocked.
    val logicalToday = remember(dayStartHour) { StatsCalculator.logicalDayOf(System.currentTimeMillis(), zone, dayStartHour) }
    var date by remember(suggestedDate, logicalToday) { mutableStateOf(suggestedDate ?: logicalToday) }
    var wasTogether by remember { mutableStateOf(false) }
    var fromTime by remember { mutableStateOf(LocalTime.of(9, 0)) }
    var toTime by remember { mutableStateOf(LocalTime.of(10, 0)) }

    val dateError: String? = when {
        date.isAfter(today) -> "Date can't be in the future"
        else -> null
    }
    // BUG fix: for a same-day (today) entry, a "To" time later than the actual current wall-clock time
    // used to save silently, creating a manual TogetherSession that ends in the future - unlike
    // CalendarScreen's own backfill dialog (see its elapsedMinutesToday check), this screen had no
    // equivalent guard at all. A future-dated session isn't caught by StatsCalculator's open-session
    // clamp either (that only clamps sessions with no endedAt - this one has a real, just-wrong, endedAt),
    // so it silently inflated all-time hours/longest-session until that moment in the future actually
    // arrived.
    val nowTimeToday = remember(date, today) {
        if (date == today) LocalTime.now(zone) else null
    }

    // Only evaluated/shown when wasTogether is checked - a blank/invalid time range must never block
    // saving the photo itself, since the together-time part is optional.
    val togetherError: String? = if (!wasTogether) null else when {
        !toTime.isAfter(fromTime) -> "\"To\" must be after \"From\""
        nowTimeToday != null && toTime.isAfter(nowTimeToday) -> "\"To\" can't be later than the current time"
        else -> null
    }
    val error = dateError ?: togetherError

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("When was this taken?") },
        text = {
            Column {
                Text(
                    if (suggestedDate != null) {
                        "We read this date from the photo - edit it if it's wrong."
                    } else {
                        "Enter the date this photo was actually taken."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                DatePickerField(
                    label = "Date",
                    date = date,
                    onDateChange = { date = it },
                    maxDate = today,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                dateError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = wasTogether, onCheckedChange = { wasTogether = it }, enabled = !isSaving)
                    Text("We were together that day", style = MaterialTheme.typography.bodyMedium)
                }

                if (wasTogether) {
                    Text(
                        "From when to when? We'll work out the hours.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TimePickerField(
                            label = "From",
                            time = fromTime,
                            onTimeChange = { fromTime = it },
                            enabled = !isSaving,
                            modifier = Modifier.weight(1f)
                        )
                        TimePickerField(
                            label = "To",
                            time = toTime,
                            onTimeChange = { toTime = it },
                            enabled = !isSaving,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                    }
                    togetherError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                saveError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = error == null && !isSaving,
                onClick = {
                    val togetherRange = if (wasTogether) {
                        val startedAt = date.atTime(fromTime).atZone(zone).toInstant().toEpochMilli()
                        val endedAt = date.atTime(toTime).atZone(zone).toInstant().toEpochMilli()
                        startedAt to endedAt
                    } else null
                    onConfirm(date, togetherRange)
                }
            ) { Text(if (isSaving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(enabled = !isSaving, onClick = onDismiss) { Text("Cancel") } }
    )
}
