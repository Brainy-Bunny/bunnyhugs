package com.ssbmedia.twogether.ui.moments

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.events.AppEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
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
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var suggestedDate by remember { mutableStateOf<LocalDate?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            pendingUri = uri
            suggestedDate = readExifDate(context, uri)
        }
    }

    content {
        pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    pendingUri?.let { uri ->
        BackfillPhotoDateDialog(
            suggestedDate = suggestedDate,
            isSaving = isSaving,
            onDismiss = { if (!isSaving) pendingUri = null },
            onConfirm = { date ->
                isSaving = true
                scope.launch {
                    val savedFile = withContext(Dispatchers.IO) { copyPickedImageToMomentsDir(context, uri) }
                    if (savedFile != null) {
                        val takenAt = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        // sessionId is deliberately always null here - see this file's top-of-file doc.
                        ServiceLocator.momentRepository.add(savedFile.absolutePath, null, takenAt)
                        // Same reasoning as CameraScreen's onImageSaved and CalendarScreen's
                        // addManualSession - don't make a freshly-backfilled photo wait for the next
                        // reconnect/15-minute catch-all if we're already together right now.
                        if (ServiceLocator.proximityStateStore.current().isTogether) {
                            AppEvents.requestManualSync()
                        }
                    }
                    isSaving = false
                    pendingUri = null
                    if (savedFile != null) onImported()
                }
            }
        )
    }
}

/** Reads EXIF DateTimeOriginal (falling back to DateTime) off the picked image via a file descriptor
 * (rather than a raw ContentResolver InputStream) so seeking works reliably for formats that need it.
 * Returns null on any failure (missing tag, unreadable/corrupt EXIF, permission hiccup) - callers treat
 * that identically to "no EXIF date", defaulting the date field to today for the user to edit instead. */
private fun readExifDate(context: Context, uri: Uri): LocalDate? = try {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        val exif = ExifInterface(pfd.fileDescriptor)
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        raw?.let(::parseExifDateTime)
    }
} catch (e: Exception) {
    null
}

private fun parseExifDateTime(raw: String): LocalDate? = try {
    // EXIF's DateTime tags are "yyyy:MM:dd HH:mm:ss", not ISO - see the TIFF/EXIF spec.
    val parsed = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)
    parsed?.let { Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault()).toLocalDate() }
} catch (e: Exception) {
    null
}

/** Copies the picked photo's actual bytes into filesDir/moments/ - see this file's top-of-file doc for
 * why a plain content:// reference isn't good enough. Returns null (rather than throwing into the
 * caller's coroutine) if the source stream can't be opened or the copy fails partway (e.g. storage
 * full); the dialog simply stays open with isSaving reset so the user can retry or cancel. */
private fun copyPickedImageToMomentsDir(context: Context, uri: Uri): File? = try {
    val outputDir = File(context.filesDir, "moments").apply { mkdirs() }
    val ext = extensionFor(context.contentResolver, uri)
    val outputFile = File(outputDir, "gallery_${System.currentTimeMillis()}.$ext")
    val copied = context.contentResolver.openInputStream(uri)?.use { input ->
        outputFile.outputStream().use { output -> input.copyTo(output) }
        true
    } ?: false
    if (copied) outputFile else null
} catch (e: Exception) {
    null
}

private fun extensionFor(resolver: ContentResolver, uri: Uri): String {
    val mime = resolver.getType(uri)
    return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
}

/** Date-assignment step of the backfill flow - same YYYY-MM-DD text field + validation style as
 * CalendarScreen's AddManualSessionDialog (future dates rejected, invalid text rejected). No duration
 * field: a photo doesn't have one, just a single date. */
@Composable
private fun BackfillPhotoDateDialog(
    suggestedDate: LocalDate?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    var dateText by remember(suggestedDate) {
        mutableStateOf((suggestedDate ?: today).format(DateTimeFormatter.ISO_LOCAL_DATE))
    }

    val parsedDate = remember(dateText) {
        try { LocalDate.parse(dateText, DateTimeFormatter.ISO_LOCAL_DATE) } catch (e: DateTimeParseException) { null }
    }
    val error: String? = when {
        parsedDate == null -> "Enter a valid date as YYYY-MM-DD"
        parsedDate.isAfter(today) -> "Date can't be in the future"
        else -> null
    }

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
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it.trim() },
                    label = { Text("Date (YYYY-MM-DD)") },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = error == null && !isSaving,
                onClick = { parsedDate?.let(onConfirm) }
            ) { Text(if (isSaving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(enabled = !isSaving, onClick = onDismiss) { Text("Cancel") } }
    )
}
