package com.ssbmedia.twogether.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.ble.AdvertiserManager
import com.ssbmedia.twogether.ble.BleConstants
import com.ssbmedia.twogether.ble.BlePermissions
import com.ssbmedia.twogether.ble.GattSyncManager
import com.ssbmedia.twogether.ble.ProximityStateMachine
import com.ssbmedia.twogether.ble.ScannerManager
import com.ssbmedia.twogether.data.datastore.ProximityPersistedState
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.notif.Notifications
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.util.BatteryOptimization
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Long-running foreground service: advertises + scans BLE to detect the paired partner nearby,
 * debounces that into a together/apart state with hysteresis, logs sessions, fires the 15-minute
 * photo nudge, drives reunion celebrations, and opportunistically syncs the date-ideas list.
 */
class ProximityForegroundService : LifecycleService() {

    private lateinit var advertiser: AdvertiserManager
    private lateinit var scanner: ScannerManager
    private lateinit var gattSync: GattSyncManager
    private val stateMachine = ProximityStateMachine()

    private var bleStarted = false
    private var tickerJob: Job? = null
    private var lastNotificationText: String = "Getting ready…"
    private var continuousTogetherSinceMillis: Long = 0L
    private var lastSeenDevice: BluetoothDevice? = null
    private var lastSeenTieBreak: Byte = 0
    private var bluetoothStateReceiver: BroadcastReceiver? = null

    // Tracks the last known battery-optimization-exemption state so updateBatteryOptimizationNotification
    // only actually calls notify()/cancel() when it flips, rather than on every 5s tick - matching
    // lastNotificationText's pattern for the status notification just above. Starts null (unknown) so the
    // very first tick after process start always evaluates and settles this for real.
    private var lastBatteryOptimizationIgnored: Boolean? = null

    // Tracks whether GATT sync (server-open or a client attempt) has been set up for the *current*
    // together-session, so it only gets (re-)armed once per session instead of on every single beacon
    // sighting. Reset whenever we go apart. This is what lets sync recover after the service restarts
    // while already together (restoreState() doesn't produce a fresh "became together" transition, so
    // without this, GATT sync would silently never come back up until the next real apart->together
    // transition - see startGattSyncIfNeeded's fix comment for the full explanation).
    private var gattReadyForSession = false

    // MAJOR fix (D): shared "one CLIENT-role sync attempt at a time" guard across all three trigger
    // paths (the 15-minute periodic ticker, the manualSyncRequests collector, and onPartnerSeen's
    // transition-triggered call) - see startGattSyncIfNeeded's doc for why this specifically guards the
    // client role. Without this, a periodic tick could fire connectAsClient() while an earlier
    // per-action-triggered attempt's photo phase (which can run for many seconds) is still in flight;
    // connectAsClient() unconditionally tears down and replaces any existing in-flight connection, so an
    // overlapping call would silently abort a real transfer already in progress rather than let it
    // finish - and since mergeRemoteSessions/mergeRemoteStubs (Repositories.kt) do a non-atomic
    // check-then-insert with no DB-level uniqueness constraint on syncId, two genuinely concurrent
    // exchanges could each independently decide "this syncId isn't present yet" and both insert,
    // producing duplicate rows (e.g. a photo appearing twice in the gallery).
    private val syncGuardLock = Any()
    private var clientSyncAttemptInProgress = false

    override fun onCreate() {
        super.onCreate()
        advertiser = AdvertiserManager(this)
        scanner = ScannerManager(this)
        gattSync = GattSyncManager(
            this,
            ServiceLocator.dateIdeaRepository,
            ServiceLocator.listCategoryRepository,
            ServiceLocator.sessionRepository,
            ServiceLocator.momentRepository,
            ServiceLocator.momentNoteRepository,
            ServiceLocator.milestoneRepository,
            ServiceLocator.timeCapsuleRepository,
            ServiceLocator.settingsStore,
            lifecycleScope
        )

        Notifications.ensureChannels(this)

        // IMPORTANT: this service's manifest-declared foregroundServiceType is "connectedDevice" only.
        // On Android 12+, once Context.startForegroundService() has been called, this service MUST
        // call Service.startForeground() with a permitted type within a few seconds, no matter what -
        // there is no graceful "bail out early" available inside onCreate(): calling stopSelf()
        // *without* first calling startForeground() still crashes the whole app a few seconds later
        // with ForegroundServiceDidNotStartInTimeException, and passing foregroundServiceType NONE/0
        // as a "no special type" fallback is *also* rejected outright with
        // InvalidForegroundServiceTypeException since the manifest declares a real type. So the only
        // correct fix lives at the call sites (MainActivity.startProximityService, BootReceiver,
        // HomeScreen's permission-grant flow): never call startForegroundService() for this service
        // unless BLUETOOTH_SCAN/ADVERTISE/CONNECT is already granted. The try/catch below is just a
        // last-resort net for anything unexpected slipping through that guard.
        val initialNotification = Notifications.buildStatusNotification(this, lastNotificationText)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, Notifications.STATUS_NOTIFICATION_ID, initialNotification, type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed - this should be unreachable now that callers check " +
                "BlePermissions.hasBlePermissions() before starting the service", e)
            stopSelf()
            return
        }

        registerBluetoothStateReceiver()

        // Sequenced deliberately: the ticker's very first tick must only run *after* restoreState() (and
        // its self-heal check) has actually finished reading/repairing persisted state, otherwise a
        // ticker tick racing ahead on its own independent coroutine could act on default/incomplete
        // in-memory state and overwrite what restoreState() was about to fix.
        lifecycleScope.launch {
            try {
                restoreState()
            } catch (e: Exception) {
                // restoreState() touches DataStore and Room, either of which can throw (IOException,
                // CorruptionException from a torn preferences file, a Room edge case, etc). This service
                // is START_STICKY: an uncaught exception here would crash the whole process, Android
                // would restart the service, onCreate() would run again and hit the same persistent
                // condition immediately - a genuine infinite crash-loop. Log and fall through with
                // default (apart, unrestored) in-memory state instead - the ticker below still runs fine
                // from a clean slate (each of its ticks is independently guarded too, see startTicker()),
                // and a torn DataStore file self-heals via the corruptionHandler configured in
                // DataStores.kt on its next read/write rather than throwing forever.
                Log.e(TAG, "restoreState() failed on startup - continuing with default in-memory state instead of crashing", e)
            }
            startTicker()
        }

        lifecycleScope.launch {
            AppEvents.manualSyncRequests.collect {
                // An uncaught exception here would kill this collector coroutine outright (a plain
                // collect{} isn't resilient to that like the ticker's try/catch loop is) - silently
                // breaking "Sync now" for the rest of the service's lifetime instead of just failing this
                // one attempt. Catch and report failure instead.
                try {
                    val device = lastSeenDevice
                    if (stateMachine.isTogether && device != null) {
                        startGattSyncIfNeeded(device, lastSeenTieBreak, manual = true)
                    } else {
                        AppEvents.emitSyncCompleted(false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Manual sync request handling failed", e)
                    AppEvents.emitSyncCompleted(false)
                }
            }
        }

        lifecycleScope.launch {
            AppEvents.unpaired.collect {
                try {
                    // Stop broadcasting/scanning with the just-deleted pairing's secret right away, rather
                    // than waiting for the next ticker tick's ensureBleRunning() to merely skip re-starting
                    // (see AppEvents.unpaired's doc for the full story).
                    advertiser.stop()
                    scanner.stop()
                    bleStarted = false
                    gattReadyForSession = false
                    gattSync.stopServer()
                    gattSync.disconnectClient()
                    resetClientSyncGuard()
                } catch (e: Exception) {
                    Log.e(TAG, "Unpaired-event teardown failed", e)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    /** Restores in-memory proximity state from DataStore after process restart, and self-heals an open
     * TogetherSession row that's implausibly stale (the service wasn't running long enough to have
     * closed it itself - e.g. app killed/relaunched, or a reboot, while genuinely together) instead of
     * leaving it open (and any UI reading "openSession != null" as "together") forever. */
    private suspend fun restoreState() {
        val persisted = ServiceLocator.proximityStateStore.current()
        val now = System.currentTimeMillis()
        val staleGap = if (persisted.lastSeenAt > 0L) now - persisted.lastSeenAt else Long.MAX_VALUE
        val isStale = persisted.isTogether && staleGap > stateMachine.absenceTimeoutMillis

        val effectiveTogether = persisted.isTogether && !isStale
        stateMachine.restoreState(effectiveTogether, persisted.lastSeenAt)
        continuousTogetherSinceMillis = if (effectiveTogether) persisted.continuousTogetherSince else 0L

        selfHealOrphanedSession(persisted, isStale, now)

        if (isStale) {
            // Estimate the real moment they actually went apart (last confirmed sighting + the absence
            // timeout - the same estimate selfHealOrphanedSession's clampedEnd uses just above) rather
            // than stamping "now". Stamping "now" here used to make a genuine reunion happening shortly
            // after this restart look like it had barely any gap at all (measured from the restart
            // moment instead of the real, possibly much earlier, apart moment) - silently skipping the
            // reunion celebration in handleBecameTogether()'s isReunion check even though the couple had
            // genuinely been apart for well over the 1-hour threshold.
            val estimatedApartSince = if (persisted.lastSeenAt > 0L) {
                (persisted.lastSeenAt + stateMachine.absenceTimeoutMillis).coerceAtMost(now)
            } else {
                now
            }
            ServiceLocator.proximityStateStore.update {
                it.copy(isTogether = false, continuousTogetherSince = 0L, currentSessionId = -1L, lastApartSince = estimatedApartSince)
            }
        }
    }

    private suspend fun selfHealOrphanedSession(persisted: ProximityPersistedState, isStale: Boolean, now: Long) {
        val openSession = ServiceLocator.sessionRepository.getOpenSession() ?: return
        if (persisted.isTogether && !isStale) return // plausibly still together right now; normal flow (a real apart transition) will close it correctly.
        // Bounded by the newest CONFIRMED sighting (maxOf(lastSeenAt, startedAt)) + the absence timeout,
        // never by a bare "now" - see StatsCalculator.effectiveOpenSessionEnd's doc. Falling back to
        // "now" when lastSeenAt was 0/absent used to bake the entire wall-clock gap into the row here,
        // permanently and irreversibly: restoring a backup that contains an open session onto a
        // replacement phone (whose proximity DataStore is at defaults, since restore deliberately does
        // not restore proximity state) landed exactly in this branch and credited every day between the
        // backup and the restore as real together-time.
        val clampedEnd = StatsCalculator.effectiveOpenSessionEnd(
            startedAt = openSession.startedAt,
            now = now,
            lastSeenAt = persisted.lastSeenAt,
            absenceTimeoutMillis = stateMachine.absenceTimeoutMillis
        ).coerceAtLeast(openSession.startedAt)
        Log.i(TAG, "Self-healing orphaned open session id=${openSession.id} (service wasn't running to close it)")
        ServiceLocator.sessionRepository.endSession(openSession, clampedEnd)
    }

    private fun registerBluetoothStateReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> {
                        // Bluetooth just came back on (was toggled off, or off at boot) - without this,
                        // AdvertiserManager/ScannerManager silently no-op forever once BT is off, and
                        // detection stays permanently dark until the whole service process restarts.
                        Log.i(TAG, "Bluetooth turned back on - resetting BLE state and retrying")
                        bleStarted = false
                        lifecycleScope.launch { ensureBleRunning() }
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        // Explicitly tear down advertiser/scanner here, not just this service's own
                        // bleStarted flag: AdvertiserManager.start()/ScannerManager.start() both
                        // early-return true when their internal isAdvertising/isScanning flags are
                        // already true, and Android doesn't reliably invoke onStartFailure/onScanFailed
                        // just because the radio was switched off out from under an active
                        // advertise/scan - so those flags would otherwise stay stuck true forever.
                        // Without this, the STATE_ON branch's ensureBleRunning() call would hit that
                        // stale-flag fast path and do nothing, never actually re-registering with the
                        // Bluetooth stack - proximity detection would stay silently dead until the
                        // whole service process was killed and restarted. stop() on both managers is
                        // safe to call here even though the adapter is already off/off-ing: their
                        // adapter/advertiser/scanner lookups just come back null and the calls no-op,
                        // but the internal flags still get reset to false either way.
                        advertiser.stop()
                        scanner.stop()
                        bleStarted = false
                        // Also tear down GATT sync state here, not just advertiser/scanner: if Bluetooth
                        // is toggled off and back on quickly enough that the absence-timeout window never
                        // elapses, no apart transition ever runs (handleBecameApart is the only other
                        // place that calls gattSync.stopServer()/disconnectClient()), so a server-role
                        // phone would otherwise keep holding a dead BluetoothGattServer handle for the
                        // rest of the together-session - startServer()'s "already listening" early-return
                        // would then make every later sync attempt silently do nothing. gattReadyForSession
                        // is reset too so onPartnerSeen re-arms a fresh startServer()/connectAsClient() call
                        // once BLE actually comes back (see registerBluetoothStateReceiver's STATE_ON branch).
                        gattSync.stopServer()
                        gattSync.disconnectClient()
                        gattReadyForSession = false
                        resetClientSyncGuard()
                    }
                }
            }
        }
        bluetoothStateReceiver = receiver
        try {
            ContextCompat.registerReceiver(this, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register Bluetooth state receiver", e)
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            var secondsElapsed = 0L
            while (isActive) {
                try {
                    tick(secondsElapsed)
                } catch (e: Exception) {
                    // The service is START_STICKY: an uncaught exception here (DataStore IOException,
                    // disk-full, a Room edge case, etc) would crash the whole process, Android would
                    // restart the service, and it would immediately hit the same persistent condition
                    // again on the very next tick - a genuine crash-loop. Log and keep ticking instead.
                    Log.e(TAG, "Ticker tick failed - continuing rather than crashing the service", e)
                }
                delay(5_000)
                secondsElapsed += 5
            }
        }
    }

    private suspend fun tick(secondsElapsed: Long) {
        val now = System.currentTimeMillis()

        ensureBleRunning()

        if (stateMachine.checkAbsence(now)) {
            handleBecameApart(now)
        }

        if (stateMachine.isTogether) {
            checkPhotoReminder(now)
        }

        if (secondsElapsed % 60 == 0L) {
            // Route through the same clamped/merged StatsCalculator path the UI uses, rather than a
            // hand-rolled unclamped/unmerged sum - see StatsCalculator's doc: this feeds the same
            // irreversible unlockEligible() write, but runs continuously in the background regardless
            // of whether any screen is open, so an unclamped stale/orphaned open session here would
            // inflate forever with nobody watching. lastSeenAt/absenceTimeoutMillis are the service's
            // own in-memory stateMachine values (the same ones its own write-path clamping already
            // uses in handleBecameApart/selfHealOrphanedSession), not the possibly-stale persisted copy.
            // Time Capsules use TRUE total hours (manual backfill included, same as every other stat in
            // this app) together with an auto-adjusting per-capsule threshold, so manual backfill can
            // never accelerate an unlock - see TimeCapsuleRepository.unlockEligible's doc for the full
            // anti-cheat reasoning (manual hours cancel out of the actual unlock condition entirely).
            val sessions = ServiceLocator.sessionRepository.getAll()
            val hours = StatsCalculator.compute(
                sessions,
                now = now,
                lastSeenAt = stateMachine.lastSeenAt,
                absenceTimeoutMillis = stateMachine.absenceTimeoutMillis
            ).totalHoursAllTime
            val manualCredit = StatsCalculator.manualHoursCredit(
                sessions,
                now = now,
                lastSeenAt = stateMachine.lastSeenAt,
                absenceTimeoutMillis = stateMachine.absenceTimeoutMillis
            )
            ServiceLocator.timeCapsuleRepository.unlockEligible(hours.toFloat(), manualCredit)
        }

        // Catch-all periodic sync while continuously together: the transition-based trigger (on the
        // apart->together edge) and the per-action triggers (Date Ideas add/check/delete, a new photo
        // captured) cover the common cases, but anything that slips through those - a trigger that
        // silently didn't fire, content added by some future feature that forgets to wire its own
        // trigger, or simply a very long together-session where new content keeps accumulating - would
        // otherwise wait for the NEXT apart->together edge, which might not happen again this session.
        // Every 15 minutes while genuinely together, just try again regardless of what triggered (or
        // didn't trigger) since the last attempt - startGattSyncIfNeeded's own tie-break/role logic and
        // the merge layer's idempotency mean a redundant sync here is a harmless no-op, not a duplicate.
        if (secondsElapsed % 900 == 0L && stateMachine.isTogether) {
            try {
                val device = lastSeenDevice
                if (device != null) {
                    startGattSyncIfNeeded(device, lastSeenTieBreak, manual = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Periodic catch-all sync failed - will retry in 15 minutes", e)
            }
        }

        updateNotification(now)
        updateBatteryOptimizationNotification()

        // Guard against clobbering an in-flight unpair(): SettingsScreen.unpair() writes
        // isTogether=false directly to the store as part of tearing down the pairing, on a
        // different (application-scoped) coroutine. If a tick that was already in flight reaches this
        // write shortly afterward, it would otherwise overwrite that with the stale in-memory
        // stateMachine.isTogether (still true until the next real absence check, up to
        // absenceTimeoutMillis away) - resuming a bogus "still together" state that a fast re-pair
        // could then pick back up until the next absence check corrects it. Skip the write entirely
        // once the pairing is gone; ensureBleRunning() above already independently tears down BLE for
        // this case, so there's nothing meaningful left for this tick to persist.
        if (!ServiceLocator.pairingStore.current().isPaired) return

        ServiceLocator.proximityStateStore.update {
            it.copy(
                isTogether = stateMachine.isTogether,
                lastSeenAt = stateMachine.lastSeenAt,
                lastSeenElapsedRealtime = stateMachine.lastSeenElapsedRealtime
            )
        }
    }

    private suspend fun ensureBleRunning() {
        if (!BlePermissions.hasBlePermissions(this)) { bleStarted = false; return }
        val pairing = ServiceLocator.pairingStore.current()
        if (!pairing.isPaired) {
            // Second safety net for the AppEvents.unpaired teardown above (e.g. this service wasn't
            // running at the moment unpair() ran, so it never saw that event, and only notices the
            // pairing is gone here on a later tick) - actually stop, don't just skip re-starting.
            if (bleStarted) { advertiser.stop(); scanner.stop() }
            bleStarted = false
            return
        }
        val secretHash = pairing.pairSecretHash ?: return
        val secretPrefix = hashToPrefixBytes(secretHash)
        val ownTieBreak = ServiceLocator.settingsStore.getOrCreateTieBreakByte().toByte()

        // Deliberately re-checked every tick rather than latched permanently true: AdvertiserManager /
        // ScannerManager's start() is a cheap no-op when already active, but returns false (and their
        // internal onStartFailure/onScanFailed callbacks reset their own isActive flags to false) when
        // the radio is off or a start attempt failed - re-calling start() here each tick is what lets
        // the periodic ticker naturally retry instead of a one-time latch giving up forever.
        val advertiseOk = advertiser.start(secretPrefix, ownTieBreak)
        val scanOk = scanner.start(secretPrefix) { device, tieBreak, _ -> onPartnerSeen(device, tieBreak) }
        bleStarted = advertiseOk && scanOk
    }

    private fun hashToPrefixBytes(hexHash: String): ByteArray =
        BleConstants.deriveBytesFromHexHash(hexHash, 0, BleConstants.SECRET_PREFIX_BYTES)

    private fun onPartnerSeen(device: BluetoothDevice, tieBreak: Byte) {
        lastSeenDevice = device
        lastSeenTieBreak = tieBreak
        lifecycleScope.launch {
            try {
                val now = System.currentTimeMillis()
                val becameTogether = stateMachine.onBeaconSeen(now)
                val shouldArmGattSync = becameTogether || (stateMachine.isTogether && !gattReadyForSession)
                if (shouldArmGattSync) {
                    // Set this synchronously, before any suspending call below, so that a burst of rapid
                    // scan callbacks arriving faster than one full handleBecameTogether()/
                    // startGattSyncIfNeeded() can run to completion (they interleave with each other at
                    // suspension points on the same dispatcher) can't all pass this same guard and each
                    // independently call connectAsClient/startServer, leaking GATT client registrations.
                    gattReadyForSession = true
                }
                if (becameTogether) {
                    handleBecameTogether(now, device, tieBreak)
                } else if (shouldArmGattSync) {
                    // We're together but GATT sync was never armed for this session - typically because
                    // the service process restarted (app killed/relaunched, or device rebooted) while the
                    // two phones were already together, so restoreState() brought us straight to
                    // isTogether=true without ever going through the transition above. Without this,
                    // "Sync now" (and auto-sync) would stay silently broken until the next genuine
                    // apart->together transition, since nothing else calls startGattSyncIfNeeded().
                    startGattSyncIfNeeded(device, tieBreak)
                }
            } catch (e: Exception) {
                // A beacon-detection crash (Room/DataStore edge case, etc) must not kill the process -
                // this coroutine is launched fresh per beacon sighting, so an uncaught exception here
                // would repeat on every future sighting too (START_STICKY restart -> same condition ->
                // crash again). Log and let the next sighting retry instead.
                Log.e(TAG, "onPartnerSeen handling failed", e)
            }
        }
    }

    private suspend fun handleBecameTogether(now: Long, device: BluetoothDevice, tieBreak: Byte) {
        val persisted = ServiceLocator.proximityStateStore.current()
        val gapMillis = if (persisted.lastApartSince > 0) now - persisted.lastApartSince else Long.MAX_VALUE
        val isReunion = persisted.lastApartSince > 0 && gapMillis >= StatsCalculator.REUNION_GAP_MILLIS &&
            StatsCalculator.isSameCalendarDay(persisted.lastApartSince, now)

        val sessionId = ServiceLocator.sessionRepository.startSession(now)
        continuousTogetherSinceMillis = now

        ServiceLocator.proximityStateStore.update {
            it.copy(
                isTogether = true,
                continuousTogetherSince = now,
                currentSessionId = sessionId,
                reminderFiredForSession = false,
                snoozeUntil = 0L,
                pendingReunionCelebration = it.pendingReunionCelebration || isReunion
            )
        }

        if (isReunion) {
            vibrateReunion()
            AppEvents.emitReunion()
        }

        startGattSyncIfNeeded(device, tieBreak)
        updateNotification(now)
    }

    private suspend fun handleBecameApart(now: Long) {
        val openSession = ServiceLocator.sessionRepository.getOpenSession()
        // lastApartSince must match the session's own endedAt (the ESTIMATED real apart instant), not
        // the raw wall-clock `now` this tick happened to run at - otherwise reunion counting drifts: the
        // live celebration (ProximityForegroundService, keyed off lastApartSince) and the historical
        // Stats.reunionCount (StatsCalculator, keyed off the DB session's endedAt) can end up straddling
        // different calendar days for the exact same real-world apart event, since `now` always lags the
        // clamped instant by however long the ticker took to notice the absence timeout - occasionally
        // enough to cross local midnight. Mirrors restoreState()'s estimatedApartSince for the same reason.
        val apartSince = if (openSession != null) {
            // Clamp instead of always stamping "now": if the service was asleep/dead for a stretch
            // while genuinely together (process death, reboot) and only now catches up to the absence
            // timeout on restart, using "now" would credit the entire downtime gap as together-time.
            // lastSeenAt reflects the last real sighting (restored from persisted state if this is a
            // post-restart catch-up), so the session can never be credited past that + the timeout.
            val clampedEnd = StatsCalculator.effectiveOpenSessionEnd(
                startedAt = openSession.startedAt,
                now = now,
                lastSeenAt = stateMachine.lastSeenAt,
                absenceTimeoutMillis = stateMachine.absenceTimeoutMillis
            ).coerceAtLeast(openSession.startedAt)
            ServiceLocator.sessionRepository.endSession(openSession, clampedEnd)
            clampedEnd
        } else {
            now
        }
        continuousTogetherSinceMillis = 0L
        ServiceLocator.proximityStateStore.update {
            it.copy(
                isTogether = false,
                continuousTogetherSince = 0L,
                currentSessionId = -1L,
                reminderFiredForSession = false,
                snoozeUntil = 0L,
                lastApartSince = apartSince
            )
        }
        Notifications.cancelPhotoReminder(this)
        gattReadyForSession = false
        gattSync.stopServer()
        gattSync.disconnectClient()
        resetClientSyncGuard()
        updateNotification(now)
    }

    private suspend fun checkPhotoReminder(now: Long) {
        val settings = ServiceLocator.settingsStore.current()
        if (!settings.notificationsEnabled) return
        val persisted = ServiceLocator.proximityStateStore.current()
        if (!persisted.isTogether || persisted.continuousTogetherSince <= 0L) return

        if (!persisted.reminderFiredForSession) {
            if (now - persisted.continuousTogetherSince >= FIFTEEN_MINUTES_MILLIS) {
                // Only consume the one-per-session reminder if it actually posted (e.g. not silently
                // skipped for lack of POST_NOTIFICATIONS) - otherwise a denied permission would burn
                // this session's only reminder for nothing, and the ticker naturally retries every 5s
                // until permission is granted or the session ends.
                if (Notifications.showPhotoReminder(this)) {
                    ServiceLocator.proximityStateStore.update { it.copy(reminderFiredForSession = true) }
                }
            }
        } else if (persisted.snoozeUntil > 0L && now >= persisted.snoozeUntil) {
            if (Notifications.showPhotoReminder(this)) {
                ServiceLocator.proximityStateStore.update { it.copy(snoozeUntil = 0L) }
            }
        }
    }

    /**
     * Arms GATT sync for the current together-session: whichever side has the lower tie-break byte
     * opens (and, importantly, *keeps open* - see below) the GATT server; the other side connects as
     * a client. Called both on a fresh apart->together transition and, via onPartnerSeen's
     * `gattReadyForSession` check, once after a service restart that resumes an already-together
     * state.
     *
     * IMPORTANT: the server side deliberately stays open for the rest of the together-session instead
     * of auto-closing after a fixed window. It used to close itself 20s after opening, which meant
     * "Sync now" (or any sync attempt) only actually worked in the ~20s right after a fresh
     * together-transition on the server-role device - any attempt outside that narrow window (e.g. the
     * common case of tapping "Sync now" minutes into an already-together session) had the client side
     * connect to a server that had already shut itself down, failing immediately with no clear reason
     * shown to the user. handleBecameApart() is what actually tears the server down now, which is the
     * only time it's no longer needed.
     */
    private suspend fun startGattSyncIfNeeded(device: BluetoothDevice, partnerTieBreakByte: Byte, manual: Boolean = false) {
        val ownTieBreak = ServiceLocator.settingsStore.getOrCreateTieBreakByte()
        val partnerTieBreak = partnerTieBreakByte.toInt() and 0xFF
        if (ownTieBreak == partnerTieBreak) {
            // Re-roll our own byte so this couple doesn't get stuck permanently: the very next sync
            // attempt (next beacon sighting this session, or the next manual "Sync now") retries with a
            // fresh independent draw, converging to inequality in all but a vanishingly rare repeat.
            ServiceLocator.settingsStore.regenerateTieBreakByte()
            // IMPORTANT: regenerating the STORED byte above is not enough on its own - AdvertiserManager.
            // start() early-returns true whenever its internal isAdvertising flag is already true,
            // completely ignoring whatever tieBreakByte argument ensureBleRunning() passes it on later
            // ticks. Without stopping it here, the radio keeps broadcasting the OLD byte forever (until
            // some unrelated BT toggle/unpair/service restart happens to reset isAdvertising), while our
            // own stored/compared byte has already moved on - so the partner keeps comparing against our
            // stale advertised byte, and role assignment never reliably converges. stop() resets
            // isAdvertising to false so the next ensureBleRunning() tick (at most 5s away) genuinely
            // restarts advertising with the freshly-regenerated byte instead of no-op'ing.
            advertiser.stop()
            // Also re-arm this session's GATT sync: this collision path can be reached with
            // gattReadyForSession already latched true (onPartnerSeen sets it unconditionally, before
            // ever calling into here), so without resetting it, onPartnerSeen's shouldArmGattSync guard
            // would never fire again for the rest of this together-session and no retry would actually
            // happen even once the partner picks up our rebroadcast byte.
            gattReadyForSession = false
            if (manual) AppEvents.emitSyncCompleted(false)
            return // rare collision, retry next attempt with the freshly re-rolled byte and a fresh advertisement
        }
        gattReadyForSession = true

        val secretHash = ServiceLocator.pairingStore.current().pairSecretHash
        if (secretHash == null) {
            if (manual) AppEvents.emitSyncCompleted(false)
            return
        }
        val handshakeKey = BleConstants.deriveBytesFromHexHash(secretHash, BleConstants.HANDSHAKE_TOKEN_HEX_OFFSET, BleConstants.HANDSHAKE_TOKEN_BYTES)

        val onResult: (Boolean) -> Unit = { success ->
            lifecycleScope.launch {
                if (success) ServiceLocator.settingsStore.setLastSyncAt(System.currentTimeMillis())
                // MINOR fix (H): always emit (not just for manual=true triggers) - Date Ideas screen's
                // "Last synced Xm ago" should refresh after ANY sync that actually completed, including
                // the 15-minute periodic catch-all, not just a manual "Sync now" tap. Harmless when
                // nothing is collecting (MutableSharedFlow just buffers/drops).
                // MAJOR fix (ultimate-app-review, Fable F-4, assertion 6): a successful merge can still
                // have dropped rows as implausible - report the count so the UI doesn't say an
                // unqualified "Synced!" over real, silent data loss.
                AppEvents.emitSyncCompleted(success, if (success) gattSync.lastSyncDroppedImplausibleCount else 0)
            }
        }

        if (ownTieBreak < partnerTieBreak) {
            // A GATT peripheral can't proactively pull data - tapping "Sync now" here can never itself
            // resolve to an on-demand success, only a real connection from the partner's client can (see
            // GattSyncManager.startServer's "already listening" doc). Tell the UI right away so it can
            // show an honest "listening" message instead of always falling through to its generic
            // failure/timeout text, which is misleading when the couple genuinely is together and passive
            // sync is in fact working.
            if (manual) AppEvents.emitSyncListening()
            gattSync.startServer(handshakeKey, onResult)
        } else {
            // MAJOR fix (D): only ever one CLIENT-role attempt in flight - see clientSyncAttemptInProgress's
            // doc above. The server role isn't guarded here: GattSyncManager.startServer is already
            // idempotent (a no-op re-open when already listening, see its own doc) and non-blocking, so
            // it doesn't represent an exclusive "attempt in flight" the way connectAsClient's
            // JSON-exchange-plus-photo-phase does.
            val acquired = synchronized(syncGuardLock) {
                if (clientSyncAttemptInProgress) false else { clientSyncAttemptInProgress = true; true }
            }
            if (!acquired) {
                Log.d(TAG, "Skipping client sync attempt - another one is already in flight")
                if (manual) AppEvents.emitSyncCompleted(false)
                return
            }
            gattSync.connectAsClient(device, handshakeKey) { success ->
                synchronized(syncGuardLock) { clientSyncAttemptInProgress = false }
                onResult(success)
            }
        }
    }

    private fun vibrateReunion() {
        val pattern = longArrayOf(0, 120, 80, 120, 80, 220)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
        }
    }

    private fun updateNotification(now: Long) {
        val text = if (stateMachine.isTogether) {
            val since = if (continuousTogetherSinceMillis > 0L) continuousTogetherSinceMillis else now
            val elapsedMillis = (now - since).coerceAtLeast(0L)
            "💕 Together for ${formatDuration(elapsedMillis)}"
        } else {
            if (stateMachine.lastSeenAt > 0L) {
                val elapsed = (now - stateMachine.lastSeenAt).coerceAtLeast(0L)
                "Apart · last saw them ${formatDuration(elapsed)} ago"
            } else {
                "Apart · haven't seen them yet"
            }
        }
        if (text == lastNotificationText) return
        lastNotificationText = text
        val notification = Notifications.buildStatusNotification(this, text)
        NotificationManagerCompat.from(this).apply {
            if (BlePermissions.hasNotificationPermission(this@ProximityForegroundService)) {
                notify(Notifications.STATUS_NOTIFICATION_ID, notification)
            }
        }
    }

    /**
     * Feature 1: shows/cancels the persistent battery-optimization nag notification as this app's
     * Doze/App Standby exemption state changes. Piggybacks on this service's existing 5s ticker (rather
     * than wiring a separate ON_RESUME hook) since the ticker already runs continuously regardless of
     * which screen - or no screen - is open, so this stays accurate even if the user never reopens the
     * app after dismissing the onboarding dialog. Only calls notify()/cancel() when the state actually
     * flips (see lastBatteryOptimizationIgnored), so this is not spamming the notification manager every
     * 5 seconds - and critically, the notification actually disappears (cancel(), not just an updated
     * text) the moment the user grants the exemption, whether that happened via the onboarding dialog,
     * tapping this very notification, or manually in system Settings.
     */
    private fun updateBatteryOptimizationNotification() {
        val ignoring = BatteryOptimization.isIgnoring(this)
        if (ignoring == lastBatteryOptimizationIgnored) return
        if (ignoring) {
            // cancel() is always effective (idempotent even if nothing was showing), so it's always safe
            // to latch this state immediately.
            Notifications.cancelBatteryWarning(this)
            lastBatteryOptimizationIgnored = true
        } else {
            // MINOR fix (F): only latch "already nagged for this state" once the notification actually
            // posted - Notifications.showBatteryWarning silently no-ops if POST_NOTIFICATIONS isn't
            // granted (same pattern as showPhotoReminder). Latching unconditionally used to mean: deny
            // notifications once while not-ignoring, latch false here regardless, and then NEVER post
            // even after later granting POST_NOTIFICATIONS - every later tick's
            // `ignoring == lastBatteryOptimizationIgnored` check would keep short-circuiting before ever
            // retrying the notify() call.
            if (Notifications.showBatteryWarning(this)) {
                lastBatteryOptimizationIgnored = false
            }
        }
    }

    /** Releases the client-role sync-attempt guard (see clientSyncAttemptInProgress's doc) - called from
     * every path that forcibly tears down the GATT client outside of connectAsClient's own onSyncDone
     * callback (unpair, Bluetooth toggled off, an apart transition, service destruction), so the guard
     * can never get stuck permanently true if that external teardown happens to suppress the callback. */
    private fun resetClientSyncGuard() {
        synchronized(syncGuardLock) { clientSyncAttemptInProgress = false }
    }

    private fun formatDuration(millis: Long): String {
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    override fun onDestroy() {
        super.onDestroy()
        tickerJob?.cancel()
        advertiser.stop()
        scanner.stop()
        gattSync.stopServer()
        gattSync.disconnectClient()
        resetClientSyncGuard()
        bluetoothStateReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) { /* not registered / already gone */ }
        }
        bluetoothStateReceiver = null
    }

    companion object {
        private const val TAG = "ProximityService"
        private const val FIFTEEN_MINUTES_MILLIS = 15 * 60 * 1000L
    }
}
