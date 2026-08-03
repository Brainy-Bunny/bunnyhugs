package com.ssbmedia.twogether.data.backup

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.data.datastore.AppSettings
import com.ssbmedia.twogether.data.datastore.LastConnectionInfo
import com.ssbmedia.twogether.data.datastore.PairingInfo
import com.ssbmedia.twogether.data.db.DateIdea
import com.ssbmedia.twogether.data.db.Moment
import com.ssbmedia.twogether.data.db.TimeCapsule
import com.ssbmedia.twogether.data.db.TogetherSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Feature 4: exports/imports everything needed to fully restore the app's local state - all Room
 * tables, the pairing DataStore (including Feature 1's persisted plaintext code and Feature 3's "last
 * connection" snapshot), settings, badge-unlocks, and copies of the actual Moment photo files - as a
 * single self-contained zip. Deliberately built on plain java.util.zip + org.json (both already part of
 * the Android platform) rather than a new serialization/zip dependency.
 *
 * Format (see [BACKUP_FORMAT_VERSION]):
 *   manifest.json       - one JSON object with pairing/settings/badgeUnlocks + the four Room tables
 *   photos/<id>_<name>  - one entry per Moment whose photo file still exists on disk
 *
 * Storage location: the public MediaStore Downloads/"Twogether Backups" collection (API 29+), falling
 * back to the legacy public Downloads/"Twogether Backups" folder on API 26-28 (pre-scoped-storage).
 * This was deliberately changed from an earlier getExternalFilesDir(null) approach: that app-private
 * external directory turned out to be wiped right along with everything else by `pm clear` / "Clear
 * storage" / uninstall - i.e. exactly the disaster (this phone's own data got wiped) backups exist to
 * recover from. MediaStore's shared Downloads collection is untouched by clearing or uninstalling THIS
 * app, survives a reinstall, and needs no new dangerous runtime permission on API 29+ (scoped storage
 * grants write access to the app's own MediaStore entries automatically). On API 26-28 only, this needs
 * WRITE_EXTERNAL_STORAGE (declared in the manifest with maxSdkVersion="28", inert on 29+) - if that's
 * ever not granted on one of those old OS versions, backup/restore fails gracefully with a clear message
 * rather than crashing.
 *
 * Restore file PICKING deliberately uses the system Storage Access Framework document picker
 * (ActivityResultContracts.OpenDocument, in RestoreBackupFlow.kt) rather than an in-app "list of backups
 * I made" query against MediaStore: that was tried first and found to be unreliable across exactly the
 * disaster-recovery scenario this feature exists for - MediaStore only lets an app query Downloads rows
 * it currently "owns" (owner_package_name == this package) without READ_EXTERNAL_STORAGE, and empirically
 * `pm clear` / a fresh reinstall can drop that ownership attribution on rows the app inserted in a PRIOR
 * install, making its own older backups invisible to its own query right when they're needed most. SAF's
 * picker has no such ownership dependency (the user grants access to a specific file, not a bookkeeping
 * row), works identically on every API level 26+, and needs no storage permission at all.
 */
object BackupManager {
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val BACKUP_FORMAT_VERSION = 1
    const val BACKUP_FOLDER_NAME = "Twogether Backups"
    private val RELATIVE_DIR = Environment.DIRECTORY_DOWNLOADS + "/" + BACKUP_FOLDER_NAME

    data class BackupResult(val success: Boolean, val uri: Uri? = null, val message: String)

    private fun legacyBackupsDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BACKUP_FOLDER_NAME)

    /** Copies [tempFile] (already fully and successfully written) into the real public backups
     * location, returning the resulting Uri, or null if that wasn't possible (e.g. missing
     * WRITE_EXTERNAL_STORAGE on API 26-28). Writing to a cache-dir temp file first and only publishing
     * once it's complete means a crash/low-storage failure mid-write can never leave a half-written
     * *.zip visible in the backups folder looking valid. */
    private fun publishBackup(context: Context, tempFile: File, displayName: String): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val wrote = resolver.openOutputStream(uri)?.use { out -> tempFile.inputStream().use { it.copyTo(out) } }
            if (wrote == null) {
                resolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val dir = legacyBackupsDir().apply { mkdirs() }
        val dest = File(dir, displayName)
        tempFile.copyTo(dest, overwrite = true)
        return Uri.fromFile(dest)
    }

    // ---------------------------------------------------------------------------------------------
    // Backup
    // ---------------------------------------------------------------------------------------------

    suspend fun createBackup(context: Context): BackupResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            val db = ServiceLocator.database
            val sessions = db.sessionDao().getAll()
            val dateIdeas = db.dateIdeaDao().getAll()
            val timeCapsules = db.timeCapsuleDao().getAll()
            val moments = db.momentDao().getAll()
            val pairing = ServiceLocator.pairingStore.current()
            val lastConnection = ServiceLocator.pairingStore.currentLastConnection()
            val settings = ServiceLocator.settingsStore.current()
            val badgeUnlocks = ServiceLocator.badgeUnlocksStore.current()

            val manifest = buildManifest(
                sessions, dateIdeas, timeCapsules, moments, pairing, lastConnection, settings, badgeUnlocks
            )

            // Write to a cache-dir temp file first and only move it into the real backups folder once
            // the zip is fully and successfully written, so a crash/low-storage failure mid-write can
            // never leave a half-written *.zip sitting in the user-visible backups folder looking valid.
            val tmpFile = File(context.cacheDir, "backup_tmp_$now.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmpFile))).use { zos ->
                zos.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zos.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                moments.forEach { moment ->
                    val photoFile = File(moment.photoUri)
                    if (photoFile.isFile) {
                        zos.putNextEntry(ZipEntry(photoZipEntryName(moment)))
                        photoFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }

            val fileName = "twogether_backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))}.zip"
            val sizeBytes = tmpFile.length()
            val publishedUri = publishBackup(context, tmpFile, fileName)
            tmpFile.delete()

            if (publishedUri == null) {
                ServiceLocator.settingsStore.setLastBackupResult(now, false)
                return@withContext BackupResult(false, null, "Backup failed: couldn't write to Downloads/$BACKUP_FOLDER_NAME (storage permission missing).")
            }

            ServiceLocator.settingsStore.setLastBackupResult(now, true)
            BackupResult(true, publishedUri, "Backup saved to Downloads/$BACKUP_FOLDER_NAME (${sizeBytes / 1024} KB, ${moments.size} photo(s)).")
        } catch (e: Exception) {
            ServiceLocator.settingsStore.setLastBackupResult(now, false)
            BackupResult(false, null, "Backup failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun photoZipEntryName(moment: Moment): String = "photos/${moment.id}_${File(moment.photoUri).name}"

    private fun buildManifest(
        sessions: List<TogetherSession>,
        dateIdeas: List<DateIdea>,
        timeCapsules: List<TimeCapsule>,
        moments: List<Moment>,
        pairing: PairingInfo,
        lastConnection: LastConnectionInfo,
        settings: AppSettings,
        badgeUnlocks: Map<String, Long>
    ): JSONObject = JSONObject().apply {
        put("backupFormatVersion", BACKUP_FORMAT_VERSION)
        put("createdAt", System.currentTimeMillis())

        put("pairing", JSONObject().apply {
            put("pairSecretHash", pairing.pairSecretHash)
            put("pairPlainCode", pairing.pairPlainCode)
            put("partnerName", pairing.partnerName)
            put("partnerEmoji", pairing.partnerEmoji)
            put("pairedAt", pairing.pairedAt)
            put("lastSecretHash", lastConnection.secretHash)
            put("lastPlainCode", lastConnection.plainCode)
            put("lastPartnerName", lastConnection.partnerName)
            put("lastPartnerEmoji", lastConnection.partnerEmoji)
            put("lastUnpairedAt", lastConnection.unpairedAt)
        })

        put("settings", JSONObject().apply {
            put("defaultSnoozeMinutes", settings.defaultSnoozeMinutes)
            put("notificationsEnabled", settings.notificationsEnabled)
            put("pinHash", settings.pinHash)
            put("pinEnabled", settings.pinEnabled)
            put("lastSyncAt", settings.lastSyncAt)
            put("deviceTieBreakByte", settings.deviceTieBreakByte)
        })

        put("badgeUnlocks", JSONObject().apply {
            badgeUnlocks.forEach { (badgeId, unlockedAt) -> put(badgeId, unlockedAt) }
        })

        put("sessions", JSONArray().apply {
            sessions.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("startedAt", s.startedAt)
                    put("endedAt", s.endedAt)
                    put("isManual", s.isManual)
                })
            }
        })

        put("dateIdeas", JSONArray().apply {
            dateIdeas.forEach { d ->
                put(JSONObject().apply {
                    put("id", d.id)
                    put("text", d.text)
                    put("category", d.category)
                    put("done", d.done)
                    put("updatedAt", d.updatedAt)
                    put("deleted", d.deleted)
                })
            }
        })

        put("timeCapsules", JSONArray().apply {
            timeCapsules.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("text", c.text)
                    put("unlockAtHours", c.unlockAtHours.toDouble())
                    put("createdAt", c.createdAt)
                    put("unlockedAt", c.unlockedAt)
                })
            }
        })

        put("moments", JSONArray().apply {
            moments.forEach { m ->
                put(JSONObject().apply {
                    put("id", m.id)
                    put("photoUri", m.photoUri)
                    put("takenAt", m.takenAt)
                    put("sessionId", m.sessionId)
                    put("photoZipEntry", if (File(m.photoUri).isFile) photoZipEntryName(m) else JSONObject.NULL)
                })
            }
        })
    }

    // ---------------------------------------------------------------------------------------------
    // Restore
    // ---------------------------------------------------------------------------------------------

    /**
     * Validates and extracts [zipFile] into a temp directory FIRST, fully parsing the manifest into
     * typed rows before touching anything live - a corrupt/malformed/foreign zip fails here with a
     * clear message and the real DB/DataStore/photos are never touched. Only once every piece has
     * parsed successfully does it apply the Room tables (in one transaction) and DataStore values, then
     * copy photo files into place.
     */
    suspend fun restoreBackup(context: Context, zipUri: Uri): BackupResult = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "restore_tmp_${System.currentTimeMillis()}")
        try {
            tempDir.mkdirs()

            var manifest: JSONObject? = null
            val extractedPhotos = mutableMapOf<String, File>() // zip entry name -> extracted temp file

            try {
                val input = context.contentResolver.openInputStream(zipUri)
                    ?: return@withContext BackupResult(false, null, "Couldn't open that backup file.")
                ZipInputStream(BufferedInputStream(input)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        when {
                            entry.isDirectory -> {}
                            name == MANIFEST_ENTRY -> {
                                manifest = JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                            }
                            name.startsWith("photos/") -> {
                                // Guard against zip-slip: only ever write inside tempDir, using just the
                                // file's base name (drop any directory components from the entry name).
                                val safeName = File(name).name
                                if (safeName.isNotBlank()) {
                                    val outFile = File(tempDir, safeName)
                                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                                    extractedPhotos[name] = outFile
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } catch (e: Exception) {
                return@withContext BackupResult(false, null, "That file isn't readable as a Twogether backup: ${e.message ?: e.javaClass.simpleName}")
            }

            val root = manifest ?: return@withContext BackupResult(false, null, "That file isn't a valid Twogether backup (no manifest found).")

            val requiredKeys = listOf("backupFormatVersion", "pairing", "settings", "badgeUnlocks", "sessions", "dateIdeas", "timeCapsules", "moments")
            for (key in requiredKeys) {
                if (!root.has(key)) return@withContext BackupResult(false, null, "This backup file is corrupt or incomplete (missing '$key').")
            }
            if (root.optInt("backupFormatVersion", -1) != BACKUP_FORMAT_VERSION) {
                return@withContext BackupResult(false, null, "This backup was made by an incompatible version of Twogether.")
            }

            val parsed = try {
                ParsedBackup(
                    sessions = parseSessions(root.getJSONArray("sessions")),
                    dateIdeas = parseDateIdeas(root.getJSONArray("dateIdeas")),
                    timeCapsules = parseTimeCapsules(root.getJSONArray("timeCapsules")),
                    moments = parseMoments(root.getJSONArray("moments")),
                    pairingJson = root.getJSONObject("pairing"),
                    settingsJson = root.getJSONObject("settings"),
                    badgeUnlocks = root.getJSONObject("badgeUnlocks").let { obj ->
                        obj.keys().asSequence().associateWith { obj.getLong(it) }
                    }
                )
            } catch (e: Exception) {
                return@withContext BackupResult(false, null, "This backup file is corrupt or incomplete (${e.message ?: e.javaClass.simpleName}).")
            }

            // Everything parsed successfully - now actually apply it.
            val db = ServiceLocator.database
            db.withTransaction {
                db.sessionDao().clearAll()
                parsed.sessions.forEach { db.sessionDao().insert(it) }
                db.dateIdeaDao().clearAll()
                if (parsed.dateIdeas.isNotEmpty()) db.dateIdeaDao().upsertAll(parsed.dateIdeas)
                db.timeCapsuleDao().clearAll()
                parsed.timeCapsules.forEach { db.timeCapsuleDao().insert(it) }
                db.momentDao().clearAll()
                parsed.moments.forEach { db.momentDao().insert(it.moment) }
            }

            val p = parsed.pairingJson
            ServiceLocator.pairingStore.restoreRaw(
                secretHash = p.optStringOrNull("pairSecretHash"),
                plainCode = p.optStringOrNull("pairPlainCode"),
                partnerName = p.optStringOrNull("partnerName"),
                partnerEmoji = p.optStringOrNull("partnerEmoji"),
                pairedAt = p.optLong("pairedAt", 0L),
                lastSecretHash = p.optStringOrNull("lastSecretHash"),
                lastPlainCode = p.optStringOrNull("lastPlainCode"),
                lastPartnerName = p.optStringOrNull("lastPartnerName"),
                lastPartnerEmoji = p.optStringOrNull("lastPartnerEmoji"),
                lastUnpairedAt = p.optLong("lastUnpairedAt", 0L)
            )

            val s = parsed.settingsJson
            ServiceLocator.settingsStore.restoreRaw(
                defaultSnoozeMinutes = s.optInt("defaultSnoozeMinutes", 15),
                notificationsEnabled = s.optBoolean("notificationsEnabled", true),
                pinHash = s.optStringOrNull("pinHash"),
                pinEnabled = s.optBoolean("pinEnabled", false),
                lastSyncAt = s.optLong("lastSyncAt", 0L),
                deviceTieBreakByte = if (s.isNull("deviceTieBreakByte")) null else s.optInt("deviceTieBreakByte")
            )

            ServiceLocator.badgeUnlocksStore.restoreRaw(parsed.badgeUnlocks)

            // Photo files last, once the DB rows that reference them are already committed.
            parsed.moments.forEach { moment ->
                val entryName = moment.photoZipEntryHint ?: return@forEach
                val extracted = extractedPhotos[entryName] ?: return@forEach
                val destFile = File(moment.photoUri)
                destFile.parentFile?.mkdirs()
                extracted.copyTo(destFile, overwrite = true)
            }

            BackupResult(true, zipUri, "Restored ${parsed.sessions.size} session(s), ${parsed.moments.size} photo(s), ${parsed.dateIdeas.size} date idea(s), ${parsed.timeCapsules.size} capsule(s).")
        } catch (e: Exception) {
            BackupResult(false, null, "Restore failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private data class ParsedBackup(
        val sessions: List<TogetherSession>,
        val dateIdeas: List<DateIdea>,
        val timeCapsules: List<TimeCapsule>,
        val moments: List<MomentWithZipHint>,
        val pairingJson: JSONObject,
        val settingsJson: JSONObject,
        val badgeUnlocks: Map<String, Long>
    )

    /** Carries the zip entry name for a moment's photo alongside the [Moment] row itself, purely so
     * restoreBackup() can look the extracted file back up after the DB insert - never persisted. */
    private data class MomentWithZipHint(val moment: Moment, val photoZipEntryHint: String?) {
        val id get() = moment.id
        val photoUri get() = moment.photoUri
    }

    private fun parseSessions(arr: JSONArray): List<TogetherSession> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        TogetherSession(
            id = o.getLong("id"),
            startedAt = o.getLong("startedAt"),
            endedAt = if (o.isNull("endedAt")) null else o.getLong("endedAt"),
            isManual = o.optBoolean("isManual", false)
        )
    }

    private fun parseDateIdeas(arr: JSONArray): List<DateIdea> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        DateIdea(
            id = o.getString("id"),
            text = o.getString("text"),
            category = o.optStringOrNull("category"),
            done = o.optBoolean("done", false),
            updatedAt = o.getLong("updatedAt"),
            deleted = o.optBoolean("deleted", false)
        )
    }

    private fun parseTimeCapsules(arr: JSONArray): List<TimeCapsule> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        TimeCapsule(
            id = o.getLong("id"),
            text = o.getString("text"),
            unlockAtHours = o.getDouble("unlockAtHours").toFloat(),
            createdAt = o.getLong("createdAt"),
            unlockedAt = if (o.isNull("unlockedAt")) null else o.getLong("unlockedAt")
        )
    }

    private fun parseMoments(arr: JSONArray): List<MomentWithZipHint> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val moment = Moment(
            id = o.getLong("id"),
            photoUri = o.getString("photoUri"),
            takenAt = o.getLong("takenAt"),
            sessionId = if (o.isNull("sessionId")) null else o.getLong("sessionId")
        )
        MomentWithZipHint(moment, o.optStringOrNull("photoZipEntry"))
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    /**
     * Restarts the app process from scratch after a successful restore. A restore rewrites the pairing
     * secret/BLE role state and every Room table out from under whatever's currently running in memory
     * (including the proximity foreground service's own in-flight BLE session) - reactive Flows pick up
     * the new Room/DataStore values fine on their own, but the running foreground service was already
     * mid-session under the OLD pairing secret, so a clean process restart is the simplest way to
     * guarantee everything (service, ViewModels, cached state) comes back up consistent with the
     * restored data, matching the task's "restart/reload the app state" requirement.
     */
    fun restartApp(context: Context) {
        context.stopService(android.content.Intent(context, com.ssbmedia.twogether.service.ProximityForegroundService::class.java))
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        launchIntent?.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        context.startActivity(launchIntent)
        Runtime.getRuntime().exit(0)
    }
}
