package com.ssbmedia.twogether.data.backup

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.data.datastore.AppSettings
import com.ssbmedia.twogether.data.datastore.LastConnectionInfo
import com.ssbmedia.twogether.data.datastore.PairingInfo
import com.ssbmedia.twogether.data.db.DEFAULT_LIST_ID
import com.ssbmedia.twogether.data.db.DateIdea
import com.ssbmedia.twogether.data.db.ListCategory
import com.ssbmedia.twogether.data.db.Milestone
import com.ssbmedia.twogether.data.db.Moment
import com.ssbmedia.twogether.data.db.MomentNote
import com.ssbmedia.twogether.data.db.TimeCapsule
import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.data.repo.MomentRepository
import com.ssbmedia.twogether.notif.MilestoneAlarmScheduler
import com.ssbmedia.twogether.notif.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Feature 4: exports/imports everything needed to restore the app's local state - all Room tables,
 * non-secret settings, badge-unlocks, and copies of the actual Moment photo files - as a single
 * self-contained zip. Deliberately built on plain java.util.zip + org.json (both already part of the
 * Android platform) rather than a new serialization/zip dependency.
 *
 * SECURITY, not stale: the pairing DataStore's live BLE credentials (pairSecretHash/pairPlainCode/
 * lastSecretHash/lastPlainCode) and the PIN hash are deliberately NEVER written to or read from this zip
 * - see buildManifest's and restoreBackup's own SECURITY comments. A restore therefore always leaves the
 * phone unpaired with PIN lock off, even when restoring onto the exact phone that made the backup -
 * re-pairing is a required manual step afterward, not an optional cleanup.
 *
 * Format (see [BACKUP_FORMAT_VERSION]):
 *   manifest.json       - one JSON object with pairing/settings/badgeUnlocks + every Room table
 *                          (sessions, dateIdeas, timeCapsules, moments, momentNotes, milestones,
 *                          listCategories)
 *   photos/<id>_<name>  - one entry per Moment whose photo file still exists on disk locally (a
 *                          remote-stub Moment - see Moment.isRemote's doc - never has one)
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
    private const val TAG = "BackupManager"
    private const val MANIFEST_ENTRY = "manifest.json"
    /** v1 -> v2: added sessions[].syncId, moments[].syncId/isRemote, momentNotes[], milestones[],
     * settings.localDeviceId.
     * v2 -> v3 (Feature 2, photo sync): added moments[].photoDownloaded, splitting "do I actually hold
     * the photo bytes" out of isRemote (see Moment.photoDownloaded's doc). restoreBackup() accepts ANY
     * of v1/v2/v3 - an older backup simply has no photoDownloaded key, and parseMoments below defaults
     * it the exact same way AppDatabase.MIGRATION_3_4 backfills existing rows on a live upgrade
     * (`!isRemote` - a non-remote row in an old backup necessarily has its own real photo bytes since
     * v1/v2 never had remote-stub rows without them; a remote-stub row correctly starts false, letting
     * the very next together-session's photo-transfer phase go fetch it for real).
     * Since v3, no format-version bump (tombstone sync additions): sessions[].updatedAt/deleted (manual-
     * session delete) and moments[].updatedAt/deleted (moment delete) were both added without bumping
     * BACKUP_FORMAT_VERSION - it isn't actually read anywhere to branch parsing behavior, so a bump would
     * be cosmetic only. Both restore cleanly from an older backup via plain optLong/optBoolean defaults
     * (see parseSessions/parseMoments below), the same backward-compat shape as syncId's own default a
     * few lines below.
     * Also since v3, no format-version bump ("Our Lists"): added the listCategories[] section and changed
     * dateIdeas[].category -> dateIdeas[].listId. Same reasoning - restoreBackup() already treats every
     * momentNotes/milestones-shaped array as optional (root.optJSONArray, missing == empty), and
     * parseDateIdeas below defaults a missing listId to [DEFAULT_LIST_ID] the same way parseSessions
     * defaults a missing syncId - so a pre-"Our Lists" backup restores every one of its date ideas
     * straight into the default "Date Ideas" list, and restoreBackup()'s own fallback (see its doc)
     * synthesizes that list's row if the backup predates listCategories entirely. */
    private const val BACKUP_FORMAT_VERSION = 3
    const val BACKUP_FOLDER_NAME = "Twogether Backups"
    private val RELATIVE_DIR = Environment.DIRECTORY_DOWNLOADS + "/" + BACKUP_FOLDER_NAME

    /** [permanent] is only meaningful when [success] is false: true means retrying this exact restore is
     * pointless, as opposed to false meaning the attempt could plausibly succeed on a retry (e.g.
     * genuinely interrupted by process death mid-restore). restoreBackupDurable/resumePendingRestoreIfAny
     * use this to decide whether to keep the pending-restore flag+cached file around for another attempt,
     * or give up and clear it - without this, a permanently-bad backup file would otherwise get silently
     * re-validated and re-fail on every single app launch forever. Two distinct producers set this true:
     * most returns from restoreBackup() itself (the zip is corrupt/foreign/incompatible - determined
     * during validation, BEFORE anything live is touched), and resumePendingRestoreIfAny's own give-up
     * path once MAX_SILENT_RESTORE_RETRIES is reached (by definition AFTER up to that many live DB wipes
     * already happened - "pointless to retry further", not "nothing live was touched"). */
    data class BackupResult(val success: Boolean, val uri: Uri? = null, val message: String, val permanent: Boolean = false)

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
            val momentNotes = db.momentNoteDao().getAll()
            val milestones = db.milestoneDao().getAll()
            val listCategories = db.listCategoryDao().getAll()
            val pairing = ServiceLocator.pairingStore.current()
            val lastConnection = ServiceLocator.pairingStore.currentLastConnection()
            val settings = ServiceLocator.settingsStore.current()
            val badgeUnlocks = ServiceLocator.badgeUnlocksStore.current()
            // BLOCKER fix, round 2 (ultimate-app-review, post-restart full-scope round, Opus): needed so
            // buildManifest can close any open session properly (see its own doc) instead of dropping
            // or leaving it open - this device's OWN current lastSeenAt, read fresh right at backup time.
            val lastSeenAtMillis = com.ssbmedia.twogether.data.datastore.ProximityStateStore(context).current().lastSeenAt

            val manifest = buildManifest(
                sessions, dateIdeas, timeCapsules, moments, momentNotes, milestones, listCategories,
                pairing, lastConnection, settings, badgeUnlocks, lastSeenAtMillis
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
                    // Plain synchronous Java I/O below has no suspension point of its own, so a caller
                    // racing this whole function against a timeout (see UpdateInstallActivity's
                    // pre-update safety-net backup) would otherwise never have its cancellation actually
                    // take effect until this entire loop finished - Kotlin cancellation is cooperative,
                    // only checked at a suspension point or an explicit check like this one. One check
                    // per photo is enough granularity to make that timeout genuinely bounded without
                    // adding meaningful overhead to the normal (uncancelled) weekly/manual backup path.
                    ensureActive()
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
            BackupResult(true, publishedUri, "Backup saved to Downloads/$BACKUP_FOLDER_NAME (${formatBackupSize(sizeBytes)}, ${moments.size} photo(s)).")
        } catch (e: CancellationException) {
            // MUST rethrow, never swallow - a blanket catch(e: Exception) below would otherwise also
            // catch this (CancellationException IS an Exception) and let this coroutine "complete
            // normally" with a BackupResult despite its Job having been cancelled (e.g. by
            // UpdateInstallActivity's pre-update backup timeout via ensureActive() in the photo-copy
            // loop above, or the caller's own scope going away mid-backup) - which breaks structured
            // concurrency's cancellation propagation for whoever's awaiting this call. Deliberately NOT
            // recording this as a failed backup via setLastBackupResult either - a cancellation isn't a
            // real failure the way an IO error is, so the previous successful backup's timestamp is
            // left as the more accurate "last backup" record.
            throw e
        } catch (e: Exception) {
            ServiceLocator.settingsStore.setLastBackupResult(now, false)
            BackupResult(false, null, "Backup failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** BUG fix: the result message used to do plain integer `sizeBytes / 1024`, so any backup under
     * 1024 bytes (a genuinely tiny but completely valid backup - e.g. a couple with no photos yet)
     * displayed as "0 KB", reading to the user like nothing was actually backed up. Shows bytes for
     * anything under 1 KB, and rounds (not truncates) KB/MB for everything else. */
    private fun formatBackupSize(sizeBytes: Long): String = when {
        sizeBytes < 1024 -> "$sizeBytes bytes"
        sizeBytes < 1024 * 1024 -> "${Math.round(sizeBytes / 1024.0)} KB"
        else -> "${"%.1f".format(sizeBytes / (1024.0 * 1024.0))} MB"
    }

    private fun photoZipEntryName(moment: Moment): String = "photos/${moment.id}_${File(moment.photoUri).name}"

    private fun buildManifest(
        sessions: List<TogetherSession>,
        dateIdeas: List<DateIdea>,
        timeCapsules: List<TimeCapsule>,
        moments: List<Moment>,
        momentNotes: List<MomentNote>,
        milestones: List<Milestone>,
        listCategories: List<ListCategory>,
        pairing: PairingInfo,
        lastConnection: LastConnectionInfo,
        settings: AppSettings,
        badgeUnlocks: Map<String, Long>,
        lastSeenAtMillis: Long
    ): JSONObject = JSONObject().apply {
        put("backupFormatVersion", BACKUP_FORMAT_VERSION)
        val backupCreatedAt = System.currentTimeMillis()
        put("createdAt", backupCreatedAt)

        // SECURITY: pairSecretHash/lastSecretHash and pinHash are DELIBERATELY never written here.
        // pairSecretHash is byte-sliced directly into the live BLE handshake token (see
        // BleConstants.deriveBytesFromHexHash) - anyone who obtains it can authenticate as either
        // partner's phone on BLE indefinitely, no brute-forcing needed, since the hash IS the secret at
        // that point, not just a stand-in for the 6-digit code. Because this backup zip gets written to
        // a PUBLIC location (Downloads/Twogether Backups - see publishBackup), including it there would
        // let the couple's most sensitive secret leave the two-phone boundary this whole app promises to
        // respect, via any app/PC with access to Downloads. Restoring a backup onto a new phone now
        // requires manually re-pairing afterward instead of silently reconnecting - a small deliberate
        // trade-off for not leaving live BLE credentials sitting in a shared folder. pairPlainCode/
        // lastPlainCode are excluded for the same reason (the plaintext code is what pairSecretHash is
        // itself derived from). pinHash is excluded on the same principle - the app-lock PIN's hash has
        // no business in a file outside the two phones either.
        put("pairing", JSONObject().apply {
            put("partnerName", pairing.partnerName)
            put("partnerEmoji", pairing.partnerEmoji)
            put("pairedAt", pairing.pairedAt)
            put("lastPartnerName", lastConnection.partnerName)
            put("lastPartnerEmoji", lastConnection.partnerEmoji)
            put("lastUnpairedAt", lastConnection.unpairedAt)
        })

        put("settings", JSONObject().apply {
            put("defaultSnoozeMinutes", settings.defaultSnoozeMinutes)
            put("notificationsEnabled", settings.notificationsEnabled)
            // MINOR fix: always false, never settings.pinEnabled - pinHash itself is deliberately never
            // written above, so a manifest claiming pinEnabled=true with no hash to check against would
            // be a "PIN lock on with nothing that can ever unlock it" trap for anything that reads this
            // field without also independently forcing it false the way this build's own restoreBackup
            // does (e.g. a downgraded/older build reading a backup made by this one).
            put("pinEnabled", false)
            put("lastSyncAt", settings.lastSyncAt)
            put("deviceTieBreakByte", settings.deviceTieBreakByte)
            put("localDeviceId", settings.localDeviceId)
        })

        put("badgeUnlocks", JSONObject().apply {
            badgeUnlocks.forEach { (badgeId, unlockedAt) -> put(badgeId, unlockedAt) }
        })

        put("sessions", JSONArray().apply {
            // BLOCKER fix (ultimate-app-review, Fable F-2): an OPEN session (endedAt == null) must
            // never be serialized VERBATIM into a backup. StatsCalculator.effectiveOpenSessionEnd
            // bounds an open session against the RESTORING device's own live lastSeenAt, not against
            // anything in the backup itself - so restoring while genuinely together with a partner
            // (lastSeenAt ~ now) credited the WHOLE backup-to-restore gap (live-verified: a 30-day-old
            // open session restored ~30 days of credit and irreversibly unlocked every time capsule up
            // to 500h, reproducing the exact B63 disaster its own fix was meant to close).
            //
            // BLOCKER fix, round 2 (ultimate-app-review, post-restart full-scope round, Opus): the
            // original version of this fix DROPPED open sessions entirely rather than closing them -
            // live-verified this silently lost real together-time from every weekly BackupWorker
            // backup for any couple with a long-running open session (a cohabiting couple's session can
            // span days). Now closes each open session here, at BACKUP time, using the exact same
            // effectiveOpenSessionEnd formula the live Stats/Home screens already trust for this
            // device's OWN current session - anchored to THIS device's real lastSeenAt (a genuinely
            // trustworthy witness right now, unlike the restoring device's unrelated lastSeenAt later).
            // The backup then always contains only CLOSED sessions, matching the same policy
            // GattSyncManager.buildPayload already enforces for BLE sync (S19) - nothing on the wire or
            // in a backup should ever need a receiver's guess about "how open is this."
            sessions.forEach { s ->
                val closedEndedAt = s.endedAt
                    ?: com.ssbmedia.twogether.stats.StatsCalculator.effectiveOpenSessionEnd(s.startedAt, backupCreatedAt, lastSeenAtMillis)
                put(JSONObject().apply {
                    put("id", s.id)
                    put("startedAt", s.startedAt)
                    put("endedAt", closedEndedAt)
                    put("isManual", s.isManual)
                    put("syncId", s.syncId)
                    put("updatedAt", s.updatedAt)
                    put("deleted", s.deleted)
                })
            }
        })

        put("dateIdeas", JSONArray().apply {
            dateIdeas.forEach { d ->
                put(JSONObject().apply {
                    put("id", d.id)
                    put("text", d.text)
                    put("listId", d.listId)
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
                    // BUG fix: was missing entirely, so a restore always defaulted this to 0 regardless
                    // of what it actually was - see parseTimeCapsules' matching fix for the full failure
                    // scenario (a restored capsule's anti-cheat effective threshold silently drifts
                    // upward by the couple's full current manual-hours credit).
                    put("manualHoursAtCreation", c.manualHoursAtCreation.toDouble())
                    // Feature: Time Capsule sync (AppDatabase.MIGRATION_8_9) - same shape every other
                    // synced entity's backup manifest already carries.
                    put("syncId", c.syncId)
                    put("updatedAt", c.updatedAt)
                    put("deleted", c.deleted)
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
                    put("syncId", m.syncId)
                    put("isRemote", m.isRemote)
                    put("photoDownloaded", m.photoDownloaded)
                    put("updatedAt", m.updatedAt)
                    put("deleted", m.deleted)
                })
            }
        })

        put("momentNotes", JSONArray().apply {
            momentNotes.forEach { n ->
                put(JSONObject().apply {
                    put("momentSyncId", n.momentSyncId)
                    put("authorDeviceId", n.authorDeviceId)
                    put("text", n.text)
                    put("updatedAt", n.updatedAt)
                    put("deleted", n.deleted)
                })
            }
        })

        put("milestones", JSONArray().apply {
            milestones.forEach { m ->
                put(JSONObject().apply {
                    put("id", m.id)
                    put("label", m.label)
                    put("month", m.month)
                    put("day", m.day)
                    put("year", m.year)
                    put("createdAt", m.createdAt)
                    put("updatedAt", m.updatedAt)
                    put("deleted", m.deleted)
                })
            }
        })

        put("listCategories", JSONArray().apply {
            listCategories.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("createdAt", c.createdAt)
                    put("updatedAt", c.updatedAt)
                    put("deleted", c.deleted)
                })
            }
        })
    }

    // ---------------------------------------------------------------------------------------------
    // Restore
    // ---------------------------------------------------------------------------------------------

    private const val PENDING_RESTORE_FILE_NAME = "pending_restore.zip"

    /** See resumePendingRestoreIfAny's MAJOR fix doc - the total number of real, destructive
     * restoreBackup() attempts one pending restore gets before it's given up on, counted from ONE shared
     * counter across BOTH restoreBackupDurable's own initial attempt and every resumePendingRestoreIfAny
     * silent auto-resume after it (see the MEDIUM fix, round 4 note in restoreBackupDurable - counting
     * only the silent-resume attempts left the real total one higher than this constant promised).
     * Deliberately small: each attempt already re-wipes and repopulates the live Room tables before it
     * can fail again, so this bounds a real, if rare, destructive-retry-loop risk, not a cheap no-op that
     * could safely retry indefinitely. */
    private const val MAX_SILENT_RESTORE_RETRIES = 3

    // MEDIUM fix: restoreBackup is explicitly NOT atomic across Room + DataStore (see restoreBackupDurable's
    // own doc), so two calls interleaving would genuinely corrupt state into a hybrid of both backups -
    // e.g. the silent app-start auto-resume (resumePendingRestoreIfAny) and a user picking a fresh backup
    // via RestoreBackupButton in the same narrow window right after launch. Serializes every restore
    // attempt through this mutex so a second caller waits for the first to fully finish rather than
    // running concurrently against the same DB/DataStore.
    private val restoreMutex = Mutex()

    /**
     * Wraps [restoreBackup] with durability across process death (the real-world version of "restore
     * ended in the middle" - a low-memory kill or the user force-closing the app, not a graceful cancel).
     * Copies [zipUri]'s bytes into app-private storage FIRST (a picked SAF content:// Uri's read grant
     * isn't guaranteed to survive a process restart, and the original source - a Downloads file, a
     * USB-mounted location, etc - might not even be reachable a second time), then durably marks a
     * "restore pending" flag, BEFORE handing off to the existing [restoreBackup] logic unchanged.
     *
     * True atomicity across the whole restore (Room tables + DataStore + photo files) isn't achievable -
     * Room and DataStore are two separate storage engines with no shared transaction between them, so
     * there's no single commit that could roll everything back if interrupted partway. This is the
     * practical alternative: make the restore trivially RE-RUNNABLE ([restoreBackup]'s Room path already
     * clearAll()s + reinserts, and its DataStore restoreRaw() calls already overwrite wholesale, so
     * running the exact same restore twice produces the same end state, not a doubled one) and durably
     * remember that one is owed, so [resumePendingRestoreIfAny] can pick it back up on the next app start
     * no matter how the process died, instead of leaving a half-restored phone with no recovery path.
     */
    suspend fun restoreBackupDurable(context: Context, zipUri: Uri): BackupResult = withContext(Dispatchers.IO) {
        restoreMutex.withLock {
            val cachedFile = File(context.filesDir, PENDING_RESTORE_FILE_NAME)
            // MINOR fix, round 4: copies to a SEPARATE temp file first, only overwriting the real
            // cachedFile once the copy fully succeeds - the previous version wrote straight into
            // cachedFile, which TRUNCATES it in place. If a still-genuinely-pending restore's cached copy
            // already lived at this same fixed path (e.g. restore A was interrupted, then before the next
            // launch the user taps Restore again and picks file B, whose read fails halfway), that
            // in-place truncation would silently destroy restore A's still-good cached zip, corrupting it
            // into a truncated hybrid - which resumePendingRestoreIfAny would then dutifully wipe the live
            // DB attempting to restore from.
            val tempCopyFile = File(context.filesDir, "$PENDING_RESTORE_FILE_NAME.incoming")
            try {
                context.contentResolver.openInputStream(zipUri)?.use { input ->
                    tempCopyFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext BackupResult(false, null, "Couldn't open that backup file.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tempCopyFile.delete()
                return@withContext BackupResult(false, null, "Couldn't read that backup file: ${e.message ?: e.javaClass.simpleName}")
            }
            // MINOR fix, round 5: wrapped in the same try/catch shape as the copy above - the previous
            // version left this bare, so a renameTo() failure (rare on same-volume filesDir, but not
            // impossible) followed by the copyTo() fallback ALSO throwing (e.g. out of storage) would
            // propagate uncaught out of this function, through RestoreBackupFlow's un-guarded call site,
            // into a crash instead of the same graceful BackupResult(false, ...) every other failure path
            // in this function produces.
            try {
                if (!tempCopyFile.renameTo(cachedFile)) {
                    tempCopyFile.copyTo(cachedFile, overwrite = true)
                    tempCopyFile.delete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tempCopyFile.delete()
                return@withContext BackupResult(false, null, "Couldn't read that backup file: ${e.message ?: e.javaClass.simpleName}")
            }
            ServiceLocator.settingsStore.setPendingRestorePath(cachedFile.absolutePath)
            // MEDIUM fix, round 4: counts THIS call's own attempt against the same shared counter
            // resumePendingRestoreIfAny increments on every silent auto-resume - without this, a real
            // destructive restoreBackup() invocation happened here uncounted, so the total bound on
            // destructive attempts across repeated process kills was actually MAX_SILENT_RESTORE_RETRIES
            // + 1, not MAX_SILENT_RESTORE_RETRIES as documented/intended. setPendingRestorePath just reset
            // the counter to 0 above, so this always increments to 1 - never itself hits the cap - but it
            // means resumePendingRestoreIfAny's own later attempts correctly continue counting from 1, not
            // from 0 as if this call had never happened.
            ServiceLocator.settingsStore.incrementPendingRestoreAttempts()
            val result = restoreBackup(context, Uri.fromFile(cachedFile))
            // MEDIUM fix: a permanent failure (corrupt/foreign/incompatible zip - determined during
            // validation, before anything live was touched) can never succeed by retrying, so clear the
            // pending state now rather than leaving resumePendingRestoreIfAny to silently re-fail the
            // exact same way on every future app launch forever, with the cached zip pinned in filesDir
            // the whole time. A non-permanent failure (genuine interruption) still leaves both in place.
            if (result.success || result.permanent) {
                ServiceLocator.settingsStore.setPendingRestorePath(null)
                cachedFile.delete()
            }
            result
        }
    }

    /** Called once on every app start (TwogetherApp.onCreate) - if a previous [restoreBackupDurable] call
     * was interrupted before it could clear the pending flag (or failed and hasn't been retried since),
     * this resumes it from the same cached local file, repeating until it actually succeeds. A no-op
     * (returns null immediately) when nothing is pending, which is the overwhelmingly common case on
     * every normal app start. */
    // MINOR fix: wrapped in withContext(Dispatchers.IO), matching restoreBackupDurable - without it,
    // cachedFile.isFile/.delete() (and everything restoreBackup itself does) ran on whatever dispatcher
    // TwogetherApp.onCreate's applicationScope.launch happens to use (Main), the exact class of bug this
    // diff's own CancellationException/dispatcher fixes elsewhere were about avoiding.
    suspend fun resumePendingRestoreIfAny(context: Context): BackupResult? = withContext(Dispatchers.IO) {
        restoreMutex.withLock {
            val pendingPath = ServiceLocator.settingsStore.current().pendingRestorePath ?: return@withLock null
            val cachedFile = File(pendingPath)
            if (!cachedFile.isFile) {
                // The cached copy itself is gone (e.g. filesDir cleared some other way) - nothing left to
                // resume from; clear the now-meaningless flag rather than leaving it stuck forever.
                ServiceLocator.settingsStore.setPendingRestorePath(null)
                return@withLock null
            }

            // MAJOR fix, round 3: incremented BEFORE attempting, not after restoreBackup() returns. The
            // scenario this whole durable-resume mechanism exists for is process death DURING
            // restoreBackup (a low-memory kill, the user force-closing the app) - a counter only bumped
            // on a completed call never sees that case at all, since the process is gone before it could
            // run. Each attempt is now durably recorded as "about to happen" before any destructive work
            // starts, so even a kill mid-restoreBackup on every single subsequent launch still converges:
            // this pending restore gets AT MOST MAX_SILENT_RESTORE_RETRIES actual attempts at the
            // destructive DB wipe TOTAL - counting restoreBackupDurable's own first attempt (see its
            // MEDIUM fix, round 4 note) plus every silent auto-resume attempt here - no matter how many
            // get interrupted, not an unbounded number across repeated kills.
            val attemptNumber = ServiceLocator.settingsStore.incrementPendingRestoreAttempts()
            if (attemptNumber > MAX_SILENT_RESTORE_RETRIES) {
                ServiceLocator.settingsStore.setPendingRestorePath(null)
                cachedFile.delete()
                // MINOR fix: without this, giving up left the user unpaired/PIN-less with a partially-
                // restored phone and zero indication anything had even been attempted -
                // TwogetherApp.onCreate discards this whole function's return value silently on every
                // normal call.
                Notifications.showRestoreGaveUpNotification(context)
                return@withLock BackupResult(false, null, "Gave up resuming this restore after $MAX_SILENT_RESTORE_RETRIES attempts.", permanent = true)
            }

            val result = restoreBackup(context, Uri.fromFile(cachedFile))
            // See restoreBackupDurable's matching comment - a permanent failure must clear the pending
            // state too, not just a success, or it silently re-fails the same way forever. A non-permanent
            // failure that hasn't hit the cap above leaves both in place, to be retried on the next launch.
            if (result.success || result.permanent) {
                ServiceLocator.settingsStore.setPendingRestorePath(null)
                cachedFile.delete()
            }
            result
        }
    }

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
                                    try {
                                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: ZipException) {
                                        // MEDIUM fix, round 3: zis.copyTo(fos) reads from the ZIP stream
                                        // as well as writing to local storage - a truncated/corrupt
                                        // deflate stream in this entry throws HERE too, not just a
                                        // genuine local-disk failure. Without this specific catch (ahead
                                        // of the generic IOException one below), that would be
                                        // misclassified as "device low on storage" AND non-permanent,
                                        // letting a genuinely corrupt backup silently re-attempt up to
                                        // MAX_SILENT_RESTORE_RETRIES times instead of failing cleanly
                                        // once. Rethrow so the OUTER catch (which correctly marks zip-
                                        // structure corruption permanent) handles it instead.
                                        throw e
                                    } catch (e: EOFException) {
                                        throw e
                                    } catch (e: IOException) {
                                        // This write targets LOCAL cache storage, not the backup zip
                                        // itself - a failure here (most realistically this device being
                                        // low on storage) says nothing about whether the backup FILE is
                                        // valid, and retrying once storage frees up could well succeed.
                                        // Classified non-permanent, unlike a genuinely corrupt
                                        // zip/manifest below - and given its own message, not the
                                        // misleading "isn't readable as a Twogether backup" one.
                                        return@withContext BackupResult(false, null, "Couldn't extract the backup - this device may be low on storage.")
                                    }
                                    extractedPhotos[name] = outFile
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext BackupResult(false, null, "That file isn't readable as a Twogether backup: ${e.message ?: e.javaClass.simpleName}", permanent = true)
            }

            val root = manifest ?: return@withContext BackupResult(false, null, "That file isn't a valid Twogether backup (no manifest found).", permanent = true)

            val requiredKeys = listOf("backupFormatVersion", "pairing", "settings", "badgeUnlocks", "sessions", "dateIdeas", "timeCapsules", "moments")
            for (key in requiredKeys) {
                if (!root.has(key)) return@withContext BackupResult(false, null, "This backup file is corrupt or incomplete (missing '$key').", permanent = true)
            }
            // momentNotes/milestones are intentionally NOT in requiredKeys above - a v1 backup (made
            // before this feature batch) simply won't have them, and that's fine; optJSONArray below
            // treats a missing array the same as an empty one rather than failing the whole restore.
            val version = root.optInt("backupFormatVersion", -1)
            if (version < 1 || version > BACKUP_FORMAT_VERSION) {
                return@withContext BackupResult(false, null, "This backup was made by an incompatible version of Twogether.", permanent = true)
            }

            // MINOR fix (ultimate-app-review, round-2 re-verification, Opus): parseSessions can now
            // silently drop a row as implausible (same as the wire sync path already can) - the restore-
            // complete dialog below reports this, mirroring the "N items skipped" signal
            // GattSyncManager/OurListsScreen already show for sync (F-4 assertion 6), so a rejection is
            // never silent regardless of which path it happens on.
            val rawSessionCount = root.getJSONArray("sessions").length()
            val parsed = try {
                ParsedBackup(
                    sessions = parseSessions(root.getJSONArray("sessions"), root.optLong("createdAt", System.currentTimeMillis())),
                    dateIdeas = parseDateIdeas(root.getJSONArray("dateIdeas")),
                    timeCapsules = parseTimeCapsules(root.getJSONArray("timeCapsules")),
                    moments = parseMoments(root.getJSONArray("moments"), context),
                    momentNotes = parseMomentNotes(root.optJSONArray("momentNotes")),
                    milestones = parseMilestones(root.optJSONArray("milestones")),
                    listCategories = parseListCategories(root.optJSONArray("listCategories")),
                    pairingJson = root.getJSONObject("pairing"),
                    settingsJson = root.getJSONObject("settings"),
                    badgeUnlocks = root.getJSONObject("badgeUnlocks").let { obj ->
                        obj.keys().asSequence().associateWith { obj.getLong(it) }
                    }
                )
            } catch (e: Exception) {
                return@withContext BackupResult(false, null, "This backup file is corrupt or incomplete (${e.message ?: e.javaClass.simpleName}).", permanent = true)
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
                db.momentNoteDao().clearAll()
                if (parsed.momentNotes.isNotEmpty()) db.momentNoteDao().upsertAll(parsed.momentNotes)
                db.milestoneDao().clearAll()
                if (parsed.milestones.isNotEmpty()) db.milestoneDao().upsertAll(parsed.milestones)
                db.listCategoryDao().clearAll()
                if (parsed.listCategories.isNotEmpty()) db.listCategoryDao().upsertAll(parsed.listCategories)
                // Fallback for a backup made before "Our Lists" existed (no listCategories section at
                // all, or one that's simply empty for some other reason): every restored DateIdea above
                // was defaulted to DEFAULT_LIST_ID by parseDateIdeas' own fallback, so without a matching
                // list_categories row the UI would have no card to show those ideas under at all. Synthesize
                // the same default "Date Ideas" row AppDatabase.MIGRATION_7_8 seeds on a live upgrade.
                //
                // MAJOR fix: also covers a backup where DEFAULT_LIST_ID is PRESENT but tombstoned
                // (deleted=true) - e.g. a backup taken on an older build, before ListCategoryRepository.
                // delete()/mergeRemote() were hardened to refuse ever deleting/tombstoning this one list
                // (see their own docs for why). Restoring such a row as-is here goes through the raw DAO,
                // bypassing both of those guards entirely, and would resurrect the exact "orphan gets
                // reassigned to a list that doesn't resolve -> unbounded reactive write loop" bug those
                // guards exist to prevent. Force it active regardless of what the backup's own copy says,
                // the same way mergeRemote() already refuses an incoming delete-tombstone for it.
                val now = System.currentTimeMillis()
                val restoredDefault = parsed.listCategories.firstOrNull { it.id == DEFAULT_LIST_ID }
                if (restoredDefault == null) {
                    db.listCategoryDao().upsert(ListCategory(id = DEFAULT_LIST_ID, name = "Date Ideas", createdAt = now, updatedAt = now))
                } else if (restoredDefault.deleted) {
                    db.listCategoryDao().upsert(restoredDefault.copy(deleted = false, updatedAt = now))
                }
            }

            // SECURITY: secretHash/plainCode/lastSecretHash/lastPlainCode/pinHash are deliberately NEVER
            // read from the backup JSON, even if present (an OLD backup made before this fix still has
            // them) - restoring them would reintroduce a secret this app now refuses to ever let leave
            // app-private storage. isPaired/exists are both derived from secretHash being non-blank (see
            // PairingInfo/LastConnectionInfo), so leaving these null correctly routes a restored phone
            // to the normal pairing screen instead of any broken partially-paired state. pinEnabled is
            // forced false alongside the missing pinHash for the same reason PIN-lock-on-with-no-hash-
            // to-check-against would otherwise be a locked-out-forever state - the couple re-enables PIN
            // and sets a fresh one from Settings if they want it back, same as re-pairing.
            val p = parsed.pairingJson
            ServiceLocator.pairingStore.restoreRaw(
                secretHash = null,
                plainCode = null,
                partnerName = p.optStringOrNull("partnerName"),
                partnerEmoji = p.optStringOrNull("partnerEmoji"),
                pairedAt = p.optLong("pairedAt", 0L),
                lastSecretHash = null,
                lastPlainCode = null,
                lastPartnerName = p.optStringOrNull("lastPartnerName"),
                lastPartnerEmoji = p.optStringOrNull("lastPartnerEmoji"),
                lastUnpairedAt = p.optLong("lastUnpairedAt", 0L)
            )
            // SECURITY fix: see PairingSessionGeneration's own doc - an independent testing round found
            // a retained PairingViewModel (still alive since an earlier, already-completed pairing flow
            // in this same process) could resurface with its stale mid-flow step/pendingCode the moment
            // MainActivity swaps back to PairingScreen after this restore clears pairing above - letting
            // the user land on a dead-end step and silently re-pair with the pre-restore secret via
            // "Skip for now", directly violating the "a restore ALWAYS leaves the phone unpaired"
            // guarantee this whole function's SECURITY comments document.
            com.ssbmedia.twogether.ui.onboarding.PairingSessionGeneration.value++

            val s = parsed.settingsJson
            ServiceLocator.settingsStore.restoreRaw(
                defaultSnoozeMinutes = s.optInt("defaultSnoozeMinutes", 15),
                notificationsEnabled = s.optBoolean("notificationsEnabled", true),
                pinHash = null,
                pinEnabled = false,
                lastSyncAt = s.optLong("lastSyncAt", 0L),
                deviceTieBreakByte = if (s.isNull("deviceTieBreakByte")) null else s.optInt("deviceTieBreakByte"),
                localDeviceId = s.optStringOrNull("localDeviceId")
            )

            ServiceLocator.badgeUnlocksStore.restoreRaw(parsed.badgeUnlocks)

            // Photo files last, once the DB rows that reference them are already committed.
            //
            // HIGH SECURITY fix, defense-in-depth: moment.photoUri is already sanitized at parse time
            // (see parseMoments' own doc), so this canonical-path re-check should never actually trigger
            // in practice - but it's the same cheap insurance GattSyncManager.savePhotoBytes already
            // applies against its own already-safe path, for exactly the same reason: a future regression
            // upstream of this point (a bug in parseMoments, a new code path that builds a Moment some
            // other way) should never turn back into an arbitrary-file-write, silently.
            val momentsDirCanonical = try {
                File(context.filesDir, "moments").canonicalFile
            } catch (e: Exception) {
                null
            }
            parsed.moments.forEach { moment ->
                val entryName = moment.photoZipEntryHint ?: return@forEach
                val extracted = extractedPhotos[entryName] ?: return@forEach
                val destFile = File(moment.photoUri)
                val destCanonical = try {
                    destFile.canonicalFile
                } catch (e: Exception) {
                    return@forEach
                }
                if (momentsDirCanonical == null || destCanonical.parentFile != momentsDirCanonical) {
                    Log.w(TAG, "Refusing to restore photo for moment ${moment.moment.syncId} - resolved path $destCanonical is outside $momentsDirCanonical")
                    return@forEach
                }
                destFile.parentFile?.mkdirs()
                extracted.copyTo(destFile, overwrite = true)
            }

            // MINOR fix: a restore onto a phone that already had its own local photos (not the primary
            // "wipe and reinstall" scenario this feature targets, but a legitimate secondary one - e.g.
            // restoring an older backup to undo a mistake) used to leave every pre-existing photo file on
            // disk as an invisible, permanently orphaned duplicate - nothing referenced them anymore
            // (the Room table above was already wholesale-replaced), but nothing ever deleted them
            // either, silently doubling the app's photo storage. Deletes anything left in moments/ that
            // isn't referenced by the just-restored moment set, matching the "REPLACE everything" the
            // confirm dialog already promises.
            if (momentsDirCanonical != null) {
                val referencedNames = parsed.moments.mapNotNull { File(it.photoUri).name }.toSet()
                momentsDirCanonical.listFiles()?.forEach { f ->
                    if (f.isFile && f.name !in referencedNames) f.delete()
                }
            }

            // Feature F: re-arm every restored milestone's yearly alarm - a fresh install (the disaster
            // scenario backup/restore exists for) starts with none scheduled at all.
            MilestoneAlarmScheduler.scheduleAll(context, parsed.milestones.filter { !it.deleted })

            val droppedSessionCount = rawSessionCount - parsed.sessions.size
            BackupResult(
                true, zipUri,
                "Restored ${parsed.sessions.size} session(s), ${parsed.moments.size} photo(s), " +
                    "${parsed.dateIdeas.size} date idea(s), ${parsed.timeCapsules.size} capsule(s), " +
                    "${parsed.milestones.size} milestone(s)." +
                    if (droppedSessionCount > 0) {
                        " ($droppedSessionCount session(s) skipped as implausible - check this phone's clock is correct.)"
                    } else {
                        ""
                    }
            )
        } catch (e: CancellationException) {
            throw e
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
        val momentNotes: List<MomentNote>,
        val milestones: List<Milestone>,
        val listCategories: List<ListCategory>,
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

    private fun parseSessions(arr: JSONArray, backupCreatedAt: Long): List<TogetherSession> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        // syncId defaults to a fresh random UUID for a v1 backup (made before Feature A existed) - safe
        // to backfill independently here, same reasoning as AppDatabase.MIGRATION_2_3's backfill.
        val syncId = o.optStringOrNull("syncId")?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
        val startedAt = o.getLong("startedAt")
        // BLOCKER fix, defense-in-depth (ultimate-app-review, Fable F-2): buildManifest now always
        // closes an open session before writing it (see its own doc), but a backup from an OLDER build,
        // or a hand-crafted/corrupted one, could still carry endedAt==null. Rather than trust the
        // RESTORING device's own live lastSeenAt to bound it (that's the original bug - it measures the
        // gap between two unrelated observations instead of elapsed togetherness), close it here using
        // the BACKUP's own createdAt.
        //
        // BLOCKER fix, round 2 (ultimate-app-review, post-restart full-scope round, Opus): the original
        // version of this fallback used `maxOf(startedAt, backupCreatedAt)` - correct for a session that
        // was genuinely just-opened at backup time, but live-verified WRONG for a STALE open session
        // already sitting in the DB before the backup was even taken (service killed / permission
        // revoked / reboot - states StatsCalculator.effectiveOpenSessionEnd's own doc says are reachable):
        // it credited the full stale gap again, reproducing the original disaster through the ordinary
        // upgrade path (an old-build backup restored on this build). Capped to never credit more than
        // the same absence-timeout window every other "how long was an open session really open" answer
        // in this app already uses (S18) - conservative (may under-credit a session that really was
        // fresh at backup time), which is the safe direction for an ingestion path that can no longer
        // ask the original device what actually happened. `maxOf(startedAt, ...)` outer bound keeps this
        // from ever going negative if backupCreatedAt is somehow before startedAt.
        val endedAt = if (o.isNull("endedAt")) {
            maxOf(startedAt, minOf(backupCreatedAt, startedAt + com.ssbmedia.twogether.ble.ProximityStateMachine.DEFAULT_ABSENCE_TIMEOUT_MILLIS))
        } else {
            o.getLong("endedAt")
        }
        val updatedAt = clampBackupUpdatedAt(o.optLong("updatedAt", 0L))
        // BLOCKER fix (ultimate-app-review, post-restart full-scope round, Opus): this parser performed
        // NO bounds validation at all - unlike GattSyncManager's wire path, ANY startedAt/endedAt from a
        // backup zip merged in verbatim. Live-verified: a crafted backup with `startedAt = Long.MIN_VALUE`
        // restored, then crashed the app in an unrecoverable OutOfMemoryError crash-loop the moment
        // StatsCalculator tried to compute stats from it (only `pm clear` escaped). Same shared validator
        // GattSyncManager.deserializeSessions uses, so the two paths can't independently drift out of
        // sync the way they just did.
        //
        // MAJOR fix, round 2 (ultimate-app-review, round-2 re-verification, Opus): the FIRST version of
        // this validation used the RESTORING device's own `System.currentTimeMillis()` as the upper
        // bound, unmodified - live-verified this silently discarded EVERY session in an otherwise
        // completely legitimate backup whenever the restoring phone's clock was merely behind the
        // backup (a factory-reset/replacement phone with no SIM/wifi defaulting to its ROM build date is
        // exactly disaster-recovery restore's core scenario, per this file's own header doc), with the
        // restore dialog still reporting apparent success. Anchored to `maxOf(now, backupCreatedAt)`
        // instead: the backup's own createdAt is always >= every legitimate timestamp it contains (it
        // was taken after them), so using it as a floor for the ceiling preserves the exact same
        // protection against the Long.MIN_VALUE-class overflow attack (still caught by the lower bound
        // and duration ceiling regardless of this value) while no longer punishing an honestly-slow
        // restoring clock for a backup that's already in the past relative to real time.
        if (!com.ssbmedia.twogether.util.SessionBoundsValidator.isPlausible(
                startedAt, endedAt, maxOf(System.currentTimeMillis(), backupCreatedAt),
                com.ssbmedia.twogether.ble.GattSyncManager.MAX_CLOCK_SKEW_TOLERANCE_MILLIS,
                com.ssbmedia.twogether.ble.GattSyncManager.MAX_PLAUSIBLE_SESSION_DURATION_MILLIS
            )
        ) {
            Log.w(TAG, "Rejecting implausible session from backup: startedAt=$startedAt endedAt=$endedAt")
            return@mapNotNull null
        }
        TogetherSession(
            id = o.getLong("id"),
            startedAt = startedAt,
            endedAt = endedAt,
            isManual = o.optBoolean("isManual", false),
            syncId = syncId,
            // v1/v2 backups (made before this feature existed) have no updatedAt/deleted keys - default
            // to "never tombstoned", same defensive optLong/optBoolean pattern as syncId's backward-compat
            // handling just above.
            updatedAt = updatedAt,
            deleted = o.optBoolean("deleted", false)
        )
    }

    private fun parseDateIdeas(arr: JSONArray): List<DateIdea> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        DateIdea(
            id = o.getString("id"),
            text = o.getString("text"),
            // A pre-"Our Lists" backup has no listId key at all (it had category instead, now dropped) -
            // default to the default list, same defensive-fallback reasoning as GattSyncManager's own
            // deserializeDateIdeas optString default.
            listId = o.optStringOrNull("listId") ?: DEFAULT_LIST_ID,
            done = o.optBoolean("done", false),
            updatedAt = clampBackupUpdatedAt(o.getLong("updatedAt")),
            deleted = o.optBoolean("deleted", false)
        )
    }

    /** BLOCKER fix (mirrors GattSyncManager.clampWireUpdatedAt's doc): a backup zip's manifest.json is
     * just as untrusted as a live BLE peer's payload - a crafted or corrupted backup with a far-future
     * `updatedAt` would otherwise let a restored row permanently win every future LWW merge against this
     * couple's real edits. Same clamp, same tolerance window. */
    private fun clampBackupUpdatedAt(wireUpdatedAt: Long): Long =
        wireUpdatedAt.coerceAtMost(System.currentTimeMillis() + 5 * 60_000L)

    /**
     * BUG fix: manualHoursAtCreation used to be omitted entirely from the manifest, so every restored
     * capsule silently defaulted to 0 - the anti-cheat model (see TimeCapsuleRepository's doc) computes
     * a capsule's EFFECTIVE unlock threshold as `unlockAtHours + (currentManualCredit -
     * manualHoursAtCreation)`, so restoring with this at 0 (instead of whatever the couple's manual
     * credit actually was at creation) makes the effective threshold silently rise by the couple's full
     * CURRENT manual-hours credit - a real, irreversible-feeling regression a user could observe as
     * "my capsule got harder to unlock after I restored a backup". `optDouble` defaults to 0.0 for a v1/
     * v2/pre-this-fix backup that genuinely never had this key, which is the correct behavior for THOSE
     * older backups (there's no better value to fall back to) - only the round-trip through a
     * current-format backup needed fixing.
     */
    /** BLOCKER fix, defense-in-depth (Feature: Time Capsule sync, AppDatabase.MIGRATION_8_9): this
     * parser used to accept any `unlockAtHours` verbatim with zero validation (unlike every other
     * synced entity's `parse*`) - a corrupted or hand-crafted backup with a negative/NaN/absurd value
     * could reach CapsulesScreen's threshold math (division/comparison against it) with unpredictable
     * results. Same "typo guardrail" bound CapsulesScreen's own MAX_CAPSULE_UNLOCK_HOURS and
     * GattSyncManager.deserializeTimeCapsules both already enforce. `syncId`/`updatedAt`/`deleted`
     * default safely for a v1..v8-format backup (made before this feature existed): a fresh UUID, this
     * device's own restore-time clamp of `createdAt`, and "never tombstoned" respectively - same
     * backward-compat pattern `manualHoursAtCreation`'s own fix above already established. */
    private fun parseTimeCapsules(arr: JSONArray): List<TimeCapsule> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        val unlockAtHours = o.getDouble("unlockAtHours").toFloat()
        if (!unlockAtHours.isFinite() || unlockAtHours <= 0f || unlockAtHours > 5000f) {
            Log.w(TAG, "Rejecting implausible time capsule from backup: unlockAtHours=$unlockAtHours")
            return@mapNotNull null
        }
        val createdAt = o.getLong("createdAt")
        TimeCapsule(
            id = o.getLong("id"),
            text = o.getString("text"),
            unlockAtHours = unlockAtHours,
            createdAt = createdAt,
            unlockedAt = if (o.isNull("unlockedAt")) null else o.getLong("unlockedAt"),
            manualHoursAtCreation = o.optDouble("manualHoursAtCreation", 0.0).toFloat(),
            syncId = o.optStringOrNull("syncId")?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
            updatedAt = clampBackupUpdatedAt(o.optLong("updatedAt", createdAt)),
            deleted = o.optBoolean("deleted", false)
        )
    }

    /**
     * HIGH SECURITY fix: `photoUri` here is UNTRUSTED data from the backup zip's manifest.json - a
     * crafted/tampered backup could set it to an arbitrary absolute path (e.g. this app's own Room DB
     * file or a DataStore preferences file) for restoreBackup()'s later `File(moment.photoUri).../
     * copyTo(overwrite=true)` step to overwrite. This is the exact same vulnerability class as
     * MomentRepository.mergeRemoteStubs' SECURITY fix for the live BLE sync path (see its own doc) -
     * that fix was never carried over to the restore path until now. Same remedy: the wire/file value is
     * used for NOTHING but a best-effort file-extension hint (via MomentRepository.extensionFromHint,
     * validated against a hardcoded allowlist), and the actual local photoUri is always the
     * deterministic MomentRepository.localPhotoFile(context, syncId, ...) path instead - computed here,
     * at parse time, so the DB row itself never holds an attacker-influenced PHOTO-URI path at any point.
     * `syncId` itself (also from the same untrusted manifest) is a SEPARATE input to that same
     * localPhotoFile() call - an independent review round caught that it wasn't being validated either,
     * so a crafted syncId could still steer the computed path outside filesDir/moments/ even with
     * photoUri itself fully ignored. That's now fixed centrally inside localPhotoFile() itself (see its
     * own doc), which also covers mergeRemoteStubs' identical wire-data syncId for the BLE sync path.
     */
    private fun parseMoments(arr: JSONArray, context: Context): List<MomentWithZipHint> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val syncId = o.optStringOrNull("syncId")?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
        val isRemote = o.optBoolean("isRemote", false)
        val rawPhotoUriHint = o.getString("photoUri")
        val moment = Moment(
            id = o.getLong("id"),
            photoUri = MomentRepository.localPhotoFile(context, syncId, MomentRepository.extensionFromHint(rawPhotoUriHint)).absolutePath,
            takenAt = o.getLong("takenAt"),
            sessionId = if (o.isNull("sessionId")) null else o.getLong("sessionId"),
            syncId = syncId,
            isRemote = isRemote,
            // Feature 2: a v1/v2 backup (made before photoDownloaded existed) defaults exactly like
            // AppDatabase.MIGRATION_3_4's live-upgrade backfill - see BACKUP_FORMAT_VERSION's doc above.
            photoDownloaded = o.optBoolean("photoDownloaded", !isRemote),
            // A pre-tombstone-sync backup has no updatedAt/deleted keys - default to "never tombstoned",
            // same defensive optLong/optBoolean pattern as syncId's/photoDownloaded's backward-compat
            // handling above.
            updatedAt = o.optLong("updatedAt", 0L),
            deleted = o.optBoolean("deleted", false)
        )
        MomentWithZipHint(moment, o.optStringOrNull("photoZipEntry"))
    }

    private fun parseMomentNotes(arr: JSONArray?): List<MomentNote> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MomentNote(
                momentSyncId = o.getString("momentSyncId"),
                authorDeviceId = o.getString("authorDeviceId"),
                text = o.optString("text", ""),
                updatedAt = clampBackupUpdatedAt(o.getLong("updatedAt")),
                deleted = o.optBoolean("deleted", false)
            )
        }
    }

    private fun parseMilestones(arr: JSONArray?): List<Milestone> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Milestone(
                id = o.getString("id"),
                label = o.getString("label"),
                // BLOCKER fix: month/day here are UNTRUSTED data from the backup zip's manifest.json,
                // same trust boundary as photoUri/syncId above. An independent testing round found that
                // an out-of-range month (e.g. 0 or 13) reaches MilestoneAlarmScheduler's
                // YearMonth.of(year, month) uncaught, which - since it's called on every app start to
                // re-arm every milestone's alarm - crashes the app on EVERY subsequent cold start with no
                // in-app recovery path (only `pm clear`, which wipes all data, escapes it). A crafted
                // backup with a single bad milestone permanently bricked the app the moment it was
                // restored. Clamped to an always-constructible range here, at parse time, same as
                // GattSyncManager.deserializeMilestones' matching fix for the identical issue reachable
                // via a malicious paired peer over BLE.
                month = o.getInt("month").coerceIn(1, 12),
                day = o.getInt("day").coerceIn(1, 31),
                year = if (o.isNull("year")) null else o.optInt("year"),
                createdAt = o.getLong("createdAt"),
                updatedAt = clampBackupUpdatedAt(o.getLong("updatedAt")),
                deleted = o.optBoolean("deleted", false)
            )
        }
    }

    private fun parseListCategories(arr: JSONArray?): List<ListCategory> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ListCategory(
                id = o.getString("id"),
                name = o.getString("name"),
                createdAt = o.getLong("createdAt"),
                updatedAt = clampBackupUpdatedAt(o.getLong("updatedAt")),
                deleted = o.optBoolean("deleted", false)
            )
        }
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
