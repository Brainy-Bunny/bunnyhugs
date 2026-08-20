package com.ssbmedia.twogether

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
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
import androidx.fragment.app.FragmentActivity
import com.ssbmedia.twogether.ble.BlePermissions
import com.ssbmedia.twogether.data.datastore.AppSettings
import com.ssbmedia.twogether.data.datastore.PairingInfo
import com.ssbmedia.twogether.data.datastore.ThemeMode
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.lock.AppLockManager
import com.ssbmedia.twogether.notif.Notifications
import com.ssbmedia.twogether.service.ProximityForegroundService
import com.ssbmedia.twogether.ui.backup.RestorePickerHost
import com.ssbmedia.twogether.ui.backup.onRestoreFilePicked
import com.ssbmedia.twogether.ui.lock.PinLockScreen
import com.ssbmedia.twogether.ui.nav.TwogetherNavHost
import com.ssbmedia.twogether.ui.onboarding.PairingScreen
import com.ssbmedia.twogether.ui.theme.TwogetherTheme
import com.ssbmedia.twogether.util.VolumeShutterKeyHandler

// Item 5 (deferred UX fix, 4-model advisory audit): FragmentActivity, not ComponentActivity - required by
// androidx.biometric.BiometricPrompt's 1.1.0 constructor (see PinLockScreen's own doc + build.gradle.kts's
// comment on the biometric dependency for why). FragmentActivity extends ComponentActivity, so every
// existing API this class already used (setContent, registerForActivityResult, dispatchKeyEvent,
// onNewIntent) keeps working completely unchanged.
class MainActivity : FragmentActivity() {

    private val cameraTrigger = mutableIntStateOf(0)
    private val milestoneTrigger = mutableStateOf<String?>(null)
    // Item 24 (UX-FIX-PLAN.md): mirrors milestoneTrigger's own pattern exactly - see NavGraph's
    // openListId doc for the full latch-then-consume story.
    private val listIdTrigger = mutableStateOf<String?>(null)

    // Item 16 (camera overhaul), point 5: kept as a plain (non-Compose-state) field, not a State<Boolean>
    // - it's only ever read from dispatchKeyEvent below, a regular Activity method that runs completely
    // outside Compose's recomposition machinery, so there's no reactive-read benefit to Compose state
    // here and a plain var is simpler. Written from TwogetherNavHost's onCameraScreenActiveChanged
    // callback every time the current destination changes (see NavGraph.kt).
    private var isCameraScreenActive = false

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
        intent?.getStringExtra(Notifications.EXTRA_OPEN_LIST_ID)?.let { listIdTrigger.value = it }

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
                    val listId by listIdTrigger

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
                        // BUG fix: the PIN-lock check used to run AFTER the pairing check, so it was only
                        // ever reachable while paired - unpairing (which does NOT clear a configured PIN;
                        // only a restore forces pinEnabled=false, see BackupManager's own SECURITY doc)
                        // left the phone with a fully configured PIN that was silently never enforced
                        // again. An independent live testing round found this exact live-reproducible gap:
                        // enable PIN -> unpair -> relock/relaunch -> PairingScreen opens with NO PIN
                        // prompt, exposing the partner's name/emoji, a one-tap "Reconnect", and the
                        // destructive "Restore from a backup" flow to anyone holding the unlocked phone.
                        // Checking PIN lock FIRST, before the pairing branch, means it gates access
                        // regardless of pairing state - exactly matching what Settings' own "App lock
                        // (PIN) - required to open the app" copy already promises.
                        loadedSettings.pinEnabled && AppLockManager.isLocked -> PinLockScreen()
                        !loadedPairing.isPaired -> PairingScreen(onPaired = { startProximityService() })
                        else -> TwogetherNavHost(
                            cameraTrigger = trigger,
                            onUnpaired = { stopProximityService() },
                            openMilestoneId = milestoneId,
                            openListId = listId,
                            // BUG fix: an independent review round found this fix (clearing the
                            // in-memory trigger state) was incomplete - onCreate re-reads these same
                            // extras from `intent` on EVERY Activity recreation, including a plain
                            // rotation, which doesn't go through onNewIntent at all and so never gets a
                            // chance to re-derive a null. Without also removing the extra from the
                            // Intent itself, rotating right after consuming a notification tap silently
                            // re-triggered the exact same navigation all over again. removeExtra() means
                            // a later onCreate (from rotation, or process death + restore) sees nothing
                            // to re-read.
                            onCameraTriggerConsumed = {
                                cameraTrigger.intValue = 0
                                intent?.removeExtra(Notifications.EXTRA_OPEN_CAMERA)
                            },
                            onMilestoneIdConsumed = {
                                milestoneTrigger.value = null
                                intent?.removeExtra(Notifications.EXTRA_OPEN_MILESTONE_ID)
                            },
                            onCameraScreenActiveChanged = { isCameraScreenActive = it },
                            // Item 24 (UX-FIX-PLAN.md): same removeExtra()-on-consume reasoning as
                            // onMilestoneIdConsumed above.
                            onListIdConsumed = {
                                listIdTrigger.value = null
                                intent?.removeExtra(Notifications.EXTRA_OPEN_LIST_ID)
                            }
                        )
                    }
                }
            }
        }
    }

    // Item 16 (camera overhaul), point 5: volume-button-as-shutter. Key events reach an Activity before
    // any view (composables included) ever gets a chance at them, so this is the only place in a
    // single-Activity Compose app that can intercept KEYCODE_VOLUME_UP/DOWN before the OS turns them into
    // a media-volume change - by the time a View-level onKeyDown could see them it would already be too
    // late. VolumeShutterKeyHandler holds the actual (unit-tested) decision logic; this override is
    // deliberately just a thin adapter from the real KeyEvent onto that pure function, forwarding a
    // shutter request over AppEvents (the same Activity/service -> UI nudge pattern already used
    // elsewhere in this app - see AppEvents.cameraShutterRequests' own doc) when it applies.
    //
    // Consuming BOTH ACTION_DOWN and ACTION_UP (returning true, not calling super) for the matched key is
    // what actually suppresses the system volume change/on-screen volume UI - only firing the capture
    // itself on ACTION_DOWN is what stops one physical button press from triggering two captures.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (VolumeShutterKeyHandler.shouldConsumeAsShutter(isCameraScreenActive, event.keyCode)) {
            if (VolumeShutterKeyHandler.shouldTriggerCapture(event.action)) {
                AppEvents.requestCameraShutter()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(Notifications.EXTRA_OPEN_CAMERA, false)) {
            cameraTrigger.intValue += 1
        }
        intent.getStringExtra(Notifications.EXTRA_OPEN_MILESTONE_ID)?.let { milestoneTrigger.value = it }
        intent.getStringExtra(Notifications.EXTRA_OPEN_LIST_ID)?.let { listIdTrigger.value = it }
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
