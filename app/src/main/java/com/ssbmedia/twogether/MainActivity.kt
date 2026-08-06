package com.ssbmedia.twogether

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import com.ssbmedia.twogether.ble.BlePermissions
import com.ssbmedia.twogether.data.datastore.AppSettings
import com.ssbmedia.twogether.data.datastore.PairingInfo
import com.ssbmedia.twogether.data.datastore.ThemeMode
import com.ssbmedia.twogether.lock.AppLockManager
import com.ssbmedia.twogether.notif.Notifications
import com.ssbmedia.twogether.service.ProximityForegroundService
import com.ssbmedia.twogether.ui.backup.RestorePickerHost
import com.ssbmedia.twogether.ui.backup.onRestoreFilePicked
import com.ssbmedia.twogether.ui.lock.PinLockScreen
import com.ssbmedia.twogether.ui.nav.TwogetherNavHost
import com.ssbmedia.twogether.ui.onboarding.PairingScreen
import com.ssbmedia.twogether.ui.theme.TwogetherTheme

class MainActivity : ComponentActivity() {

    private val cameraTrigger = mutableIntStateOf(0)
    private val milestoneTrigger = mutableStateOf<String?>(null)

    // BUG fix: registered here as an Activity-level property (constructed before onCreate/onStart, per
    // AndroidX's own requirement that registerForActivityResult be called before STARTED), NOT inside any
    // composable - see RestorePickerHost's own doc for why. This is what actually survives a PIN relock
    // happening mid-pick; the composable that triggers it does not.
    private val restorePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onRestoreFilePicked(this, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RestorePickerHost.launchPicker = { restorePickerLauncher.launch(arrayOf("*/*")) }

        if (intent?.getBooleanExtra(Notifications.EXTRA_OPEN_CAMERA, false) == true) {
            cameraTrigger.intValue = 1
        }
        intent?.getStringExtra(Notifications.EXTRA_OPEN_MILESTONE_ID)?.let { milestoneTrigger.value = it }

        setContent {
            // Hoisted ABOVE TwogetherTheme (rather than loaded inside its content, like pairingInfo
            // still is below) because TwogetherTheme's own darkTheme parameter has to be decided BEFORE
            // its content composes - it can't reactively read a value that only becomes available once
            // its own children start running. Reused inside the content below too, so this isn't a
            // second/duplicate settings collection.
            var settings by remember { mutableStateOf<AppSettings?>(null) }
            LaunchedEffect(Unit) {
                ServiceLocator.settingsStore.settings.collect { settings = it }
            }
            // While settings hasn't loaded yet (settings == null, a brief one-or-two-frame window on
            // first launch), falls back to isSystemInDarkTheme() - i.e. exactly today's existing
            // behavior before this ThemeMode override existed - so the common case (a user who's never
            // touched this new setting, which is everyone until they explicitly change it) sees no
            // different first-frame behavior at all.
            val effectiveDarkTheme = when (settings?.themeMode ?: ThemeMode.SYSTEM) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            TwogetherTheme(darkTheme = effectiveDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // collectAsState(initial = ...) would render its default (unpaired, pinEnabled=false)
                    // for a frame or two before the real persisted DataStore value arrives - and since
                    // pairingInfo/settings load independently, they can resolve on different frames (e.g.
                    // "paired" arrives before "pinEnabled=true" does), letting the real NavHost content
                    // compose before the PIN lock gate has a chance to see the real value. Track "have we
                    // actually loaded the real value yet" explicitly for both, and render nothing (private
                    // content included) until both are known.
                    var pairingInfo by remember { mutableStateOf<PairingInfo?>(null) }
                    val trigger by cameraTrigger
                    val milestoneId by milestoneTrigger

                    LaunchedEffect(Unit) {
                        ServiceLocator.pairingStore.info.collect { pairingInfo = it }
                    }

                    LaunchedEffect(pairingInfo?.isPaired) {
                        if (pairingInfo?.isPaired == true) startProximityService()
                    }

                    val loadedPairing = pairingInfo
                    val loadedSettings = settings
                    when {
                        loadedPairing == null || loadedSettings == null -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = "💕", fontSize = 40.sp)
                                    Text(
                                        text = "Twogether",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
                                    )
                                    CircularProgressIndicator()
                                    // Book-dedication-style personal credit, shown only for the brief
                                    // moment this loading gate is up while DataStore resolves - subtle,
                                    // not attention-grabbing, and never gates or restricts anything.
                                    Text(
                                        text = "Inspired by My Cutie Kachvii 💚",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 16.dp)
                                    )
                                }
                            }
                        }
                        !loadedPairing.isPaired -> PairingScreen(onPaired = { startProximityService() })
                        loadedSettings.pinEnabled && AppLockManager.isLocked -> PinLockScreen()
                        else -> TwogetherNavHost(
                            cameraTrigger = trigger,
                            onUnpaired = { stopProximityService() },
                            openMilestoneId = milestoneId
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(Notifications.EXTRA_OPEN_CAMERA, false)) {
            cameraTrigger.intValue += 1
        }
        intent.getStringExtra(Notifications.EXTRA_OPEN_MILESTONE_ID)?.let { milestoneTrigger.value = it }
    }

    private fun startProximityService() {
        // ProximityForegroundService's manifest-declared type ("connectedDevice") requires
        // BLUETOOTH_SCAN/ADVERTISE/CONNECT to already be granted before it can call
        // Service.startForeground() - and once Context.startForegroundService() is invoked, Android
        // requires the service to call startForeground() within a few seconds no matter what, or the
        // whole app is killed with ForegroundServiceDidNotStartInTimeException. There's no safe way
        // for the service itself to recover from that once started, so the only real fix is to never
        // start it in the first place without the permission. HomeScreen shows a banner + button that
        // requests the permission and calls this same start path again once it's granted.
        if (!BlePermissions.hasBlePermissions(this)) return
        val serviceIntent = Intent(this, ProximityForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopProximityService() {
        stopService(Intent(this, ProximityForegroundService::class.java))
    }
}
