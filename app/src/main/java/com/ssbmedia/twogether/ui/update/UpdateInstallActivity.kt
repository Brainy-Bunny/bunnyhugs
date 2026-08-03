package com.ssbmedia.twogether.ui.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * Invisible middleman activity that a tap on the "Update available" notification (see
 * Notifications.showUpdateAvailableNotification) always goes through, instead of the notification's
 * PendingIntent launching a raw ACTION_VIEW install Intent directly.
 *
 * The reason: a PendingIntent itself can't run code, so if it pointed straight at the system package
 * installer and this device had never granted Twogether "install unknown apps" access, the tap would
 * either silently do nothing or dead-end on a generic OS dialog with no path back to actually fixing
 * it. Routing through a real Activity lets us check canRequestPackageInstalls() first and, if it's
 * not granted yet, send the user straight to the exact per-app settings screen that grants it - see
 * the task's requirement that "the flow doesn't crash or dead-end if that permission isn't yet
 * granted".
 *
 * Has no UI of its own (Theme.Twogether.Dialog is transparent/translucent - same trick SnoozeActivity
 * already uses) - it always finishes immediately after either launching Settings or the installer.
 */
class UpdateInstallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apkPath = intent?.getStringExtra(EXTRA_APK_PATH)
        val apkFile = apkPath?.let { File(it) }
        if (apkFile == null || !apkFile.exists()) {
            Toast.makeText(this, "Update file is missing — try \"Check for updates now\" again", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            guideToUnknownAppsSettings()
            finish()
            return
        }

        launchInstaller(apkFile)
        finish()
    }

    /** Sends the user to the exact "Install unknown apps" settings screen for Twogether, rather than
     * just failing silently - this is the expected/unavoidable first-run prompt described in the
     * task; the user re-taps the update notification once they've granted it. */
    private fun guideToUnknownAppsSettings() {
        try {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            startActivity(settingsIntent)
            Toast.makeText(
                this,
                "Allow Twogether to install updates, then tap the update notification again",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: ActivityNotFoundException) {
            // Some OEM ROMs don't ship this exact settings screen - fall back to telling the user
            // where to look manually rather than crashing.
            Toast.makeText(
                this,
                "Enable \"Install unknown apps\" for Twogether in system Settings, then try again",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun launchInstaller(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Couldn't open the installer. Update saved at: ${apkFile.name}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
    }
}
