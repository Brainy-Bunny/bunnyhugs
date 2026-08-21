package com.ssbmedia.twogether.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
     * Must be called off the main thread (decode + rotate + re-encode is blocking disk/CPU work).
     *
     * Returns `true` if the rotate succeeded and [file] now holds the rotated image, `false` if anything
     * went wrong (undecodable file, disk write failure) - [file] is left completely untouched on failure,
     * never partially written.
     */
    fun rotateInPlace(file: File, degrees: Float): Boolean {
        if (!file.isFile) return false
        val original = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        val rotated = try {
            val matrix = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        } catch (e: Exception) {
            original.recycle()
            return false
        }

        val tempFile = File(file.parentFile, file.name + ".rotate.part")
        return try {
            tempFile.outputStream().use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            // Atomic swap: only replace the real file once the new one is fully, successfully written.
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            true
        } catch (e: Exception) {
            tempFile.delete()
            false
        } finally {
            if (rotated !== original) original.recycle()
            rotated.recycle()
        }
    }

    private const val JPEG_QUALITY = 90

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
