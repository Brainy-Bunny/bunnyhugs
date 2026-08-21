package com.ssbmedia.twogether.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * User-requested: a simple rotate for Moments photos, available right after capture (CameraScreen's
 * post-capture review) and later from the photo viewer (MomentsScreen's MomentFullScreen) - for the
 * common "phone was sideways" case with no need to reach for a separate gallery app.
 *
 * SCOPE LIMITATION (disclosed, not silently shipped): this app has no mechanism to re-sync an already-
 * downloaded photo's BYTES to the partner's phone - GattSyncManager only ever fetches a photo once,
 * gated on Moment.photoDownloaded staying false until that first fetch completes (see its own doc,
 * "hasPhoto"/photoDownloaded plumbing). Editing this app's wire/backup protocol to support re-syncing
 * an edited photo is a real, separate feature (and GattSyncManager.kt is intentionally hand-edited only,
 * per this app's own established security-audit convention) - out of scope here. A rotate applies to
 * THIS device's own local copy only; the partner's already-downloaded copy keeps its original
 * orientation until a future dedicated re-sync feature exists.
 */
object PhotoEditor {

    /**
     * Rotates [file]'s image content by [degrees] (90/180/270) and overwrites it in place, following
     * this app's established temp-file-then-atomic-rename convention (see GattSyncManager.savePhotoBytes,
     * BackupManager.createBackup, UpdateChecker.downloadApk) rather than ImageDownscaler's narrower
     * write-straight-to-target shortcut - this is overwriting a photo the user already has, not producing
     * a brand new derived file, so a mid-write crash must never leave a corrupted or half-written photo
     * behind.
     *
     * BLOCKER fix (independent audit, live-reproducible): `BitmapFactory.decodeFile` returns raw SENSOR
     * pixels - it does NOT apply the file's EXIF Orientation tag, which is exactly how a real camera
     * capture (CameraX's `ImageCapture` writes sensor-orientation pixels + an EXIF tag, never rotates
     * pixels itself) or a phone-gallery-imported photo actually gets shown upright everywhere else in
     * Android, Coil included. This function used to rotate the SENSOR pixels by [degrees] and then write
     * a JPEG with NO Exif at all (`Bitmap.compress` never writes Exif) - so for any EXIF-oriented file,
     * tapping rotate silently cancelled out the tag Coil was already applying to display it correctly,
     * making the button look like a no-op (or land on the wrong angle) on precisely the photos this
     * feature exists for. Fixed by reading the file's OWN current orientation first and folding it into
     * the SAME matrix as the requested rotation, so the output bitmap's PIXELS end up fully, correctly
     * oriented - no residual Exif tag is needed (or written) after this, matching how [file] had no Exif
     * tag needed once this function is done with it.
     *
     * MAJOR fix (independent audit): decodes at bounded resolution (mirroring ImageDownscaler's own
     * inSampleSize pattern) rather than full sensor resolution - a full-res camera capture (this is
     * called from CameraScreen's post-capture review, BEFORE ImageDownscaler.downscaleIfNeeded ever
     * runs) can be 40-50MB as a decoded ARGB_8888 bitmap, TWICE that with the rotated copy live
     * simultaneously - a real OutOfMemoryError risk on mid/low-RAM devices. Bounded well above
     * ImageDownscaler's own 1280px final target (so this never visibly degrades the CameraScreen review
     * preview) but far below full sensor resolution - and moot for the MomentsScreen viewer's call site
     * anyway, since by then the file has already been through downscaleIfNeeded.
     *
     * Must be called off the main thread (decode + rotate + re-encode is blocking disk/CPU work).
     *
     * Returns `true` if the rotate succeeded and [file] now holds the rotated image, `false` if anything
     * went wrong (undecodable file, disk write failure, encode failure, out-of-memory) - [file] is left
     * completely untouched on failure, never partially written.
     */
    fun rotateInPlace(file: File, degrees: Float): Boolean {
        if (!file.isFile) return false
        val existingOrientationDegrees = try {
            when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
            if (longestEdge <= 0) return false
            val sampleSize = calculateInSampleSize(longestEdge, MAX_ROTATE_DIMENSION)
            val original = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            ) ?: return false

            var rotated: Bitmap? = null
            try {
                val matrix = Matrix().apply { postRotate(existingOrientationDegrees + degrees) }
                val rotatedBitmap = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
                rotated = rotatedBitmap

                val tempFile = File(file.parentFile, file.name + ".rotate.part")
                try {
                    val encodedOk = tempFile.outputStream().use { out ->
                        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    }
                    if (!encodedOk) {
                        tempFile.delete()
                        false
                    } else {
                        // Atomic swap: only replace the real file once the new one is fully,
                        // successfully written. Same-directory rename doesn't fail in practice on
                        // Android's filesystem, so (unlike the removed copyTo fallback this replaced)
                        // there's no path here that can truncate the real file and then throw - a
                        // failed rename just falls through to the catch below with the real file still
                        // fully intact.
                        tempFile.renameTo(file)
                    }
                } catch (e: Exception) {
                    tempFile.delete()
                    false
                }
            } finally {
                original.recycle()
                rotated?.let { if (it !== original) it.recycle() }
            }
        } catch (e: Throwable) {
            // Throwable, not Exception: an oversized decode can throw OutOfMemoryError, which is an
            // Error, not an Exception - a plain `catch (e: Exception)` here would let it propagate out
            // of this function's caller's withContext(Dispatchers.IO) and crash the app. Bounding the
            // decode above already makes this unlikely in practice; this is the last-resort backstop.
            false
        }
    }

    /** Same power-of-2 inSampleSize calculation as ImageDownscaler.calculateInSampleSize - duplicated
     * rather than shared since that one is private to a file with a deliberately different (lossy,
     * user-visible-quality-reducing) purpose; this one exists purely to bound memory use during rotation,
     * not to actually shrink the photo's stored resolution. */
    private fun calculateInSampleSize(longestEdge: Int, target: Int): Int {
        var sample = 1
        var edge = longestEdge
        while (edge / 2 >= target) {
            edge /= 2
            sample *= 2
        }
        return sample
    }

    /** Generous headroom above ImageDownscaler's own 1280px final target - CameraScreen's post-capture
     * review (this function's highest-risk call site, since it runs on the full sensor-resolution
     * capture before ImageDownscaler ever touches it) never visibly loses quality at this bound, since
     * the file gets downscaled to 1280px anyway once the user taps Keep. MomentsScreen's viewer call
     * site is already ≤1280px by the time a rotate can happen there, so this bound never even engages. */
    private const val MAX_ROTATE_DIMENSION = 2048

    // MINOR fix (independent audit): was 90, mismatched against ImageDownscaler's own 78 - every rotate
    // was a fresh, HIGHER-quality-than-necessary re-encode of an already-lossy JPEG (each one still a
    // real quality generation loss on top of the last), and made the file GROW past what downscaleIfNeeded
    // would have produced from the same source, working against that file's whole point of keeping a BLE
    // transfer feasible for a not-yet-synced photo. Matched to 78 so a rotate doesn't move the needle on
    // either quality or size beyond what this app already accepts as its standard.
    private const val JPEG_QUALITY = 78

    /** BUG fix (user-reported "rotated images are not saved"): [rotateInPlace] genuinely does persist
     * the rotation to disk - the actual bug is that Coil caches an image by its request `data` (a
     * Moment's `photoUri`) alone, with no awareness that this app can overwrite that SAME path's bytes
     * in place. "Same URI = same bytes" is Coil's whole cache-key assumption, and rotateInPlace breaks
     * it - so every screen showing this photo (the Moments grid, Home's memory card, a Milestone
     * preview, or even the very same full-screen viewer if closed and reopened) kept serving the
     * pre-rotate bytes straight out of its memory/disk cache, making a rotate that DID succeed look like
     * it silently reverted. Every call site building this photo's ImageRequest folds this into its own
     * memory/disk cache key alongside the URI, so a rotate is never served stale anywhere again.
     * File.lastModified() is a metadata stat, not a data read - cheap enough to call directly, but
     * callers should still route it through their own produceState/remember off Main if they're already
     * doing an IO check there (matching this codebase's established "no blocking IO in composition"
     * convention - see MomentThumbnail's hasLocalPhoto doc), rather than adding a second blocking call. */
    fun cacheBustKey(photoUri: String): Long = File(photoUri).lastModified()
}
