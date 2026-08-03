package com.ssbmedia.twogether

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.ssbmedia.twogether.ble.BlePermissions
import com.ssbmedia.twogether.data.datastore.AppSettings
import com.ssbmedia.twogether.data.datastore.PairingInfo
import com.ssbmedia.twogether.lock.AppLockManager
import com.ssbmedia.twogether.notif.Notifications
import com.ssbmedia.twogether.service.ProximityForegroundService
import com.ssbmedia.twogether.ui.lock.PinLockScreen
import com.ssbmedia.twogether.ui.nav.TwogetherNavHost
import com.ssbmedia.twogether.ui.onboarding.PairingScreen
import com.ssbmedia.twogether.ui.theme.TwogetherTheme

class MainActivity : ComponentActivity() {

    private val cameraTrigger = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.getBooleanExtra(Notifications.EXTRA_OPEN_CAMERA, false) == true) {
            cameraTrigger.intValue = 1
        }

        setContent {
            TwogetherTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // collectAsState(initial = ...) would render its default (unpaired, pinEnabled=false)
                    // for a frame or two before the real persisted DataStore value arrives - and since
                    // pairingInfo/settings load independently, they can resolve on different frames (e.g.
                    // "paired" arrives before "pinEnabled=true" does), letting the real NavHost content
                    // compose before the PIN lock gate has a chance to see the real value. Track "have we
                    // actually loaded the real value yet" explicitly for both, and render nothing (private
                    // content included) until both are known.
                    var pairingInfo by remember { mutableStateOf<PairingInfo?>(null) }
                    var settings by remember { mutableStateOf<AppSettings?>(null) }
                    val trigger by cameraTrigger

                    LaunchedEffect(Unit) {
                        ServiceLocator.pairingStore.info.collect { pairingInfo = it }
                    }
                    LaunchedEffect(Unit) {
                        ServiceLocator.settingsStore.settings.collect { settings = it }
                    }

                    LaunchedEffect(pairingInfo?.isPaired) {
                        if (pairingInfo?.isPaired == true) startProximityService()
                    }

                    val loadedPairing = pairingInfo
                    val loadedSettings = settings
                    when {
                        loadedPairing == null || loadedSettings == null -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        !loadedPairing.isPaired -> PairingScreen(onPaired = { startProximityService() })
                        loadedSettings.pinEnabled && AppLockManager.isLocked -> PinLockScreen()
                        else -> TwogetherNavHost(
                            cameraTrigger = trigger,
                            onUnpaired = { stopProximityService() }
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
