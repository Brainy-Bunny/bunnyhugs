package com.ssbmedia.twogether.data.update

import android.content.Context
import android.util.Log
import com.ssbmedia.twogether.BuildConfig
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
     * a day at most, per the task's "don't re-check more than once every several hours" ask. */
    val MIN_CHECK_INTERVAL_MS: Long = TimeUnit.HOURS.toMillis(6)

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
        if (info.versionCode <= BuildConfig.VERSION_CODE) return@withContext CheckOutcome.UpToDate

        val apkFile = downloadApk(context, info.downloadUrl)
            ?: return@withContext CheckOutcome.DownloadFailed

        Notifications.showUpdateAvailableNotification(context, apkFile, info.versionName)
        CheckOutcome.UpdateAvailable(info, apkFile)
    }

    private fun fetchLatestRelease(): UpdateInfo? {
        return try {
            val json = httpGetJson(RELEASES_LATEST_URL) ?: return null
            val tagName = json.optString("tag_name", "")
            val versionCode = tagName.removePrefix("v").toIntOrNull() ?: return null

            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    val url = asset.optString("browser_download_url", "")
                    if (url.isNotBlank()) {
                        apkUrl = url
                        break
                    }
                }
            }
            val downloadUrl = apkUrl ?: return null

            // Deliberately NOT the release's free-text "name" field (e.g. "Twogether v1.1") - that's
            // written by whoever cuts the release and already tends to include the app's own name,
            // which would double up awkwardly wherever this versionName gets embedded in UI text (the
            // notification body, the manual-check dialog). The tag itself (e.g. "v2") is guaranteed
            // short and consistently formatted, since it's exactly what checkAndNotify just parsed.
            UpdateInfo(
                versionCode = versionCode,
                versionName = tagName,
                downloadUrl = downloadUrl
            )
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            null
        }
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
     * the install intent. Overwrites any previously-downloaded update.
     */
    private fun downloadApk(context: Context, downloadUrl: String): File? {
        var connection: HttpURLConnection? = null
        return try {
            val dir = context.getExternalFilesDir(null) ?: context.cacheDir
            val outFile = File(dir, APK_FILE_NAME)
            connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            outFile
        } catch (e: Exception) {
            Log.w(TAG, "APK download failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }
}
