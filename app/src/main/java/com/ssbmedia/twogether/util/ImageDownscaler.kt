package com.ssbmedia.twogether.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * MAJOR fix (real-hardware regression): real phone-camera photos (3-5MB) essentially never finished
 * syncing over BLE in practice (~5-20 kB/s real throughput) within any reasonable timeout - this wasn't
 * caught by emulator testing because the emulator's synthetic test images were tiny. Downscaling before
 * a photo is ever queued for transfer is what actually makes BLE sync feasible at all - see
 * GattSyncManager's PHOTO_PHASE_TIMEOUT_MILLIS doc.
 *
 * DESIGN CHOICE (documented per the task spec): this app stores ONLY the downscaled copy, both locally
 * on the capturing device AND for what gets synced to the partner - it does NOT additionally keep a
 * separate full-resolution original around. This matches the app's existing simplicity-first approach
 * elsewhere (see GattSyncManager's "SIMPLIFICATION" doc, BackupManager's plain-zip/no-new-dependency
 * choice) - keeping two copies of every photo forever (one full-res local-only, one downscaled for sync)
 * would roughly double photo storage for the lifetime of the couple's account, and would mean the two
 * partners' copies of "the same" photo are no longer byte-identical (which some earlier testing verified
 * as a property of this app - flagging that here since this change deliberately ends it). A max edge of
 * ~1280px at ~78% JPEG quality is still comfortably good enough for full-screen viewing on any phone, at
 * a small fraction of a modern camera's original size.
 */
object ImageDownscaler {
    private const val MAX_DIMENSION = 1280
    private const val JPEG_QUALITY = 78

    /**
     * Downscales [file] IN PLACE (or onto a renamed sibling - see return value) if its longest edge
     * exceeds [MAX_DIMENSION]px; a small/already-fitting image (a meme, a small screenshot) is left
     * byte-for-byte untouched rather than needlessly re-encoded - both to avoid a pointless quality hit
     * and because re-encoding always produces JPEG bytes regardless of the source format, which would
     * otherwise leave a non-jpg-named file (e.g. gallery-imported .png/.webp) containing mismatched
     * JPEG content.
     *
     * Must be called off the main thread (BitmapFactory decode + Bitmap.compress are blocking disk + CPU
     * work) - both call sites (CameraScreen.kt, GalleryImportFlow.kt) already run this inside
     * Dispatchers.IO.
     *
     * Returns the File to use going forward: the SAME [file] if nothing needed to change (already small
     * enough, or the image couldn't be decoded at all - fails safe by leaving the original alone), or a
     * new sibling file (same base name, ".jpg" extension, original deleted) if it was actually
     * downscaled+re-encoded. Callers MUST use the returned File from this point on, not necessarily
     * [file] itself.
     */
    fun downscaleIfNeeded(file: File): File {
        if (!file.isFile) return file

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return file // not decodable as an image - leave it alone
        if (maxOf(width, height) <= MAX_DIMENSION) return file // already small enough

        val sampleSize = calculateInSampleSize(maxOf(width, height), MAX_DIMENSION)
        val sampled = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return file

        val scale = MAX_DIMENSION.toFloat() / maxOf(sampled.width, sampled.height)
        var bitmapToCompress = sampled
        if (scale < 1f) {
            val targetWidth = (sampled.width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (sampled.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(sampled, targetWidth, targetHeight, true)
            if (scaled !== sampled) bitmapToCompress = scaled
        }

        val jpegFile = File(file.parentFile, file.nameWithoutExtension + ".jpg")
        return try {
            jpegFile.outputStream().use { out ->
                bitmapToCompress.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (jpegFile != file) file.delete()
            jpegFile
        } catch (e: Exception) {
            jpegFile.delete()
            file
        } finally {
            bitmapToCompress.recycle()
            if (bitmapToCompress !== sampled) sampled.recycle()
        }
    }

    /** Standard power-of-2 BitmapFactory.Options.inSampleSize calculation: the largest sample size that
     * still leaves [longestEdge] at or above [target] after sampling, so the subsequent exact
     * Bitmap.createScaledBitmap pass only ever has to scale DOWN a little further, never up. */
    private fun calculateInSampleSize(longestEdge: Int, target: Int): Int {
        var sample = 1
        var edge = longestEdge
        while (edge / 2 >= target) {
            edge /= 2
            sample *= 2
        }
        return sample
    }
}
