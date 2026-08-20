package com.ssbmedia.twogether.data.update

import android.content.Context
import android.util.Log
import com.ssbmedia.twogether.BuildConfig
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.notif.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Auto-update feature: checks the public GitHub Releases API for a newer signed build of this app
 * and, if found, downloads it and posts a "tap to install" notification. This is the ONE deliberate
 * exception to the app's otherwise fully-offline design (see the INTERNET permission's manifest
 * comment) - nothing else in the app ever makes a network call.
 *
 * Version scheme: every release this project cuts is tagged `v<versionCode>` (e.g. `v1`, `v2` - see
 * the README/release notes for `gh release create --tag vN`), so comparing is just an integer
 * comparison against [BuildConfig.VERSION_CODE]; no semver parsing needed. Plain `HttpURLConnection`
 * + `org.json` (both already used elsewhere in this app, e.g. BackupManager) - a single JSON GET plus
 * one file download doesn't justify pulling in OkHttp/Retrofit.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/Brainy-Bunny/bunnyhugs/releases/latest"
    private const val APK_FILE_NAME = "twogether-update.apk"

    /** Don't let app-start's own check re-hit the network on every reopen - the daily UpdateWorker
     * covers the periodic case regardless; this just bounds the extra app-start check to a few times
     * a day at most, per the task's "don't re-check more than once every several hours" ask. Also used
     * (item 14, update-nag reach fix) as the real rate limiter for ProximityForegroundService's own
     * periodic check - see that class's tick()'s use of this same constant. */
    val MIN_CHECK_INTERVAL_MS: Long = TimeUnit.HOURS.toMillis(6)

    /** Item 14 (update-nag reach fix): pure predicate shared by every surface that decides whether to
     * show a "an update is ready" affordance (Settings' own card, and the new Home banner) - a pending
     * update recorded in AppSettings.pendingUpdateVersionCode is only actually actionable while it's
     * still genuinely newer than what's currently running; see that field's own doc for why a stale
     * (already-installed) pending flag must be ignored rather than shown. One function, not two
     * independent inline comparisons, so Home and Settings can never silently drift apart on this. */
    fun isPendingUpdateActionable(pendingVersionCode: Int, currentVersionCode: Int): Boolean =
        pendingVersionCode > currentVersionCode

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String
    )

    sealed class CheckOutcome {
        data class UpdateAvailable(val info: UpdateInfo, val apkFile: File) : CheckOutcome()
        object UpToDate : CheckOutcome()
        /** Newer release found but its APK could not be downloaded (network error mid-download etc). */
        object DownloadFailed : CheckOutcome()
        /** The GitHub API call itself failed (offline, rate-limited, malformed response, ...). */
        object CheckFailed : CheckOutcome()
    }

    /**
     * Full check-and-notify flow shared by the manual "Check for updates now" button and the daily
     * background [UpdateWorker]: hits the GitHub Releases API, and if its tag encodes a newer
     * versionCode than this build's own, downloads the release's APK asset and posts the "Update
     * available" notification. Never throws - every failure path is folded into [CheckOutcome].
     */
    suspend fun checkAndNotify(context: Context): CheckOutcome = withContext(Dispatchers.IO) {
        // fetchLatestRelease() returning null is ambiguous between "network/parse failure" and "no
        // releases published yet" - both are correctly reported as CheckFailed to the manual-check
        // UI (there's nothing actionable to tell the user apart between those two cases).
        val info = fetchLatestRelease() ?: return@withContext CheckOutcome.CheckFailed
        if (info.versionCode <= BuildConfig.VERSION_CODE) {
            // BUG fix: this build already covers whatever was pending (most likely the user installed
            // it via a still-live notification tap, without ever coming back through this function to
            // clear it explicitly) - see AppSettings.pendingUpdateVersionCode's doc.
            ServiceLocator.settingsStore.clearPendingUpdate()
            return@withContext CheckOutcome.UpToDate
        }

        val apkFile = downloadApk(context, info.downloadUrl)
            ?: return@withContext CheckOutcome.DownloadFailed

        Notifications.showUpdateAvailableNotification(context, apkFile, info.versionName)
        // BUG fix: durably records that this update is ready, independent of the OS notification's own
        // dismissal state (or notifications being disabled entirely) - see AppSettings.
        // pendingUpdateVersionCode's doc. Settings reads this to show a persistent "Update ready"
        // indicator that doesn't depend on the user having seen/kept the notification.
        ServiceLocator.settingsStore.setPendingUpdate(info.versionCode, info.versionName, apkFile.absolutePath)
        CheckOutcome.UpdateAvailable(info, apkFile)
    }

    private fun fetchLatestRelease(): UpdateInfo? {
        return try {
            val json = httpGetJson(RELEASES_LATEST_URL) ?: return null
            val tagName = json.optString("tag_name", "")
            val versionCode = tagName.removePrefix("v").toIntOrNull() ?: return null

            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            var apkAssetName: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    val url = asset.optString("browser_download_url", "")
                    if (url.isNotBlank()) {
                        apkUrl = url
                        apkAssetName = name
                        break
                    }
                }
            }
            val downloadUrl = apkUrl?.takeIf { isTrustedReleaseAssetUrl(it) } ?: run {
                if (apkUrl != null) Log.w(TAG, "Rejecting update: asset URL host is not a trusted GitHub host: $apkUrl")
                return null
            }

            // BUG fix: this used to be the raw tag (e.g. "v11") - which is deliberately versionCode, not
            // a human-readable version, so the update notification/dialog read "Twogether v11 is ready"
            // even though the user thinks of this release as "2.1". Not the release's free-text "name"
            // field either (e.g. "Twogether 2.1") - that's written by whoever cuts the release and
            // already tends to include the app's own name, which would double up awkwardly wherever this
            // versionName gets embedded in UI text. Instead, parsed from the APK asset's OWN filename,
            // which this project has consistently named "Twogether-<versionName>.apk" (e.g.
            // "Twogether-2.1.apk") ever since the very first release - structured, not free text, and
            // already the thing a human would actually call this version. Falls back to the tag if a
            // release's APK is ever named some other way.
            //
            // BUG fix: an independent review round caught that the fallback above was dead code in
            // practice - takeIf { it.isNotBlank() } only rejects a genuinely EMPTY result, not one that
            // simply didn't match the expected "Twogether-<x>.apk" shape (e.g. an asset literally named
            // "app-release.apk" parsed to the non-blank-but-garbled "app-release"). Now requires the
            // parsed result to actually look like a version (starts with a digit) before using it,
            // falling back to the tag exactly as originally documented for anything else.
            //
            // BUG fix: a later testing round found the strip above was case-SENSITIVE
            // (removePrefix/removeSuffix have no ignoreCase overload in the stdlib) while the asset match
            // above (endsWith(".apk", ignoreCase = true)) is case-insensitive - so an asset literally
            // named "Twogether-2.2.APK" matched the search but then failed to strip its suffix, leaving
            // "2.2.APK" as the parsed result; since '2' is still a digit, the guard above would have let
            // that garbled string straight through as the shown version. Stripped manually with an
            // explicit ignoreCase check on both ends instead, so casing in the asset name can never affect
            // the result.
            val versionName = apkAssetName
                ?.let { name ->
                    val withoutPrefix = if (name.startsWith("Twogether-", ignoreCase = true)) name.substring("Twogether-".length) else name
                    if (withoutPrefix.endsWith(".apk", ignoreCase = true)) withoutPrefix.dropLast(4) else withoutPrefix
                }
                ?.takeIf { it.isNotBlank() && it.first().isDigit() }
                ?: tagName

            UpdateInfo(
                versionCode = versionCode,
                versionName = versionName,
                downloadUrl = downloadUrl
            )
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            null
        }
    }

    /** MAJOR fix: an independent adversarial testing round found the download URL from the GitHub
     * Releases API response (`browser_download_url`) was used verbatim with no host/scheme check - if
     * the API response were ever tampered with in transit (or GitHub itself compromised at the API
     * layer) this would download and prompt-install a binary from an arbitrary host. Android's own
     * same-signing-key enforcement on install is the real backstop (an attacker can't get a
     * differently-signed APK to actually replace this one), which is why this was accepted as a MAJOR
     * rather than a blocker - but since the auto-updater is a permanent, load-bearing feature (not
     * something to remove), closing the gap between "the API said so" and "this URL is actually
     * GitHub's" is worth the few lines. Requires https and an actual GitHub release-asset host -
     * `github.com` (browser_download_url's normal host) or `objects.githubusercontent.com`
     * (redirect-signing CDN GitHub sometimes returns for this exact URL directly). */
    private fun isTrustedReleaseAssetUrl(url: String): Boolean {
        val uri = try {
            java.net.URI(url)
        } catch (e: Exception) {
            return false
        }
        // MINOR fix (ultimate-app-review, fresh-reviewer re-verify of the fix above): a trailing root
        // label dot ("github.com.") is a DNS-equivalent FQDN for the same host, but was falsely
        // REJECTED (fails safe, never a security hole - just an over-broad rejection) since neither
        // equals() nor endsWith() strip it. Normalized away before comparing.
        val host = (uri.host ?: return false).removeSuffix(".")
        // MINOR fix (ultimate-app-review, Fable F-3): the broad `.githubusercontent.com` suffix this
        // fix originally used doesn't mean "GitHub's release CDN" - it also covers `raw.` and `gist.`,
        // where ANY GitHub user can host arbitrary bytes under their own account. Under this fix's own
        // stated threat model (a tampered API response), an attacker just points `browser_download_url`
        // at `raw.githubusercontent.com/<their account>/.../evil.apk` and the old broad suffix accepted
        // it. Narrowed to the EXACT host `objects.githubusercontent.com` - the specific redirect-signing
        // CDN GitHub itself returns for a genuine release asset, never user-populated.
        return uri.scheme.equals("https", ignoreCase = true) &&
            (host.equals("github.com", ignoreCase = true) ||
                host.equals("objects.githubusercontent.com", ignoreCase = true))
    }

    private fun httpGetJson(urlString: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                // The GitHub REST API 403s anonymous requests that send no User-Agent at all.
                setRequestProperty("User-Agent", "Twogether-App-UpdateChecker")
            }
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "GitHub API request failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Streams [downloadUrl] to this app's app-specific external files directory (falls back to
     * cacheDir if external storage isn't mounted) - no storage permission needed either way, since
     * this is app-private storage, and it's exactly what file_paths.xml exposes via FileProvider for
     * the install intent.
     *
     * MAJOR fix: downloads to a temp ".part" file first and only renames it onto the real, fixed,
     * notification-linked [APK_FILE_NAME] path once the download is FULLY successful - same
     * temp-file-then-rename pattern already used by GattSyncManager.savePhotoBytes and
     * BackupManager.createBackup/publishBackup. Previously this wrote straight onto the final path,
     * truncating it immediately - so a later re-check (the daily UpdateWorker, or a manual re-check)
     * that starts downloading again while an earlier COMPLETE download is still sitting there
     * un-installed, and then fails partway (network drop), left the file corrupted while the "Update
     * available" notification still pointed at it. Now only a fully successful download ever touches the
     * real path, so an already-good, notification-linked APK can never be clobbered by a failed re-try.
     */
    private fun downloadApk(context: Context, downloadUrl: String): File? {
        var connection: HttpURLConnection? = null
        val dir = context.getExternalFilesDir(null) ?: context.cacheDir
        val outFile = File(dir, APK_FILE_NAME)
        val tempFile = File(dir, "$APK_FILE_NAME.part")
        return try {
            // MINOR fix (ultimate-app-review, Fable F-3): instanceFollowRedirects=true let a strictly
            // github.com/objects.githubusercontent.com URL redirect to ANY other https host with no
            // re-check - the isTrustedReleaseAssetUrl gate above only ever saw the ORIGINAL URL. Redirects
            // are now followed manually, re-validating every hop against the same trusted-host check
            // before following it, capped at a small number of hops as a sanity bound (GitHub's own
            // redirect chain for a release asset is 1-2 hops).
            var currentUrl = downloadUrl
            var hops = 0
            while (true) {
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                }
                connection.connect()
                val code = connection.responseCode
                if (code in setOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location == null || !isTrustedReleaseAssetUrl(location)) {
                        Log.w(TAG, "Refusing to follow update redirect to an untrusted host")
                        tempFile.delete()
                        return null
                    }
                    hops++
                    if (hops > 5) {
                        Log.w(TAG, "Too many update redirects")
                        tempFile.delete()
                        return null
                    }
                    currentUrl = location
                    continue
                }
                break
            }
            if (connection.responseCode !in 200..299) {
                tempFile.delete()
                return null
            }
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
            if (!tempFile.renameTo(outFile)) {
                tempFile.copyTo(outFile, overwrite = true)
                tempFile.delete()
            }
            outFile
        } catch (e: Exception) {
            Log.w(TAG, "APK download failed", e)
            tempFile.delete()
            null
        } finally {
            connection?.disconnect()
        }
    }
}
