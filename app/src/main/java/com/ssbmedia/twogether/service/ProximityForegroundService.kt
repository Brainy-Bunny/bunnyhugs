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
import com.ssbmedia.twogether.badges.BadgeCatalog
import com.ssbmedia.twogether.data.datastore.ProximityPersistedState
import com.ssbmedia.twogether.data.update.UpdateChecker
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.notif.Notifications
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.util.BatteryOptimization
import com.ssbmedia.twogether.util.RelativeTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Long-running foreground service: advertises + scans BLE to detect the paired partner nearby,
 * debounces that into a together/apart state with hysteresis, logs sessions, fires the
 * (user-configurable, see AppSettings.photoReminderMinutes) photo nudge and any list/list-item
 * reminders, drives reunion celebrations, and opportunistically syncs the date-ideas list.
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
            ServiceLocator.dayNoteRepository,
            ServiceLocator.milestoneRepository,
            ServiceLocator.timeCapsuleRepository,
            ServiceLocator.settingsStore,
            ServiceLocator.pairingStore,
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

        // Reunion-count non-retroactivity feature: sessionGraceMinutes is now a live, user-configurable
        // setting (AppSettings.sessionGraceMinutes) instead of the old hardcoded SESSION_GRACE_MILLIS
        // constant - see withinGraceWindow/graceWindowExpired's own docs. Read once here so this whole
        // restoreState() call is internally consistent about which grace window it's applying.
        val graceMillis = ServiceLocator.settingsStore.current().sessionGraceMinutes.coerceAtLeast(1) * 60_000L

        // Item 9 (UX-FIX-PLAN.md): a persisted pendingApartSince that's still within the grace window
        // means the process died/restarted mid-grace-window - the fast isTogether flip already correctly
        // happened (persisted.isTogether is false), but the underlying TogetherSession row and the
        // continuous-together timer are still "live" pending a possible resume, not something to reset.
        // See selfHealOrphanedSession's own doc for how this interacts with the stale-session self-heal.
        val stillWithinGrace = !effectiveTogether && withinGraceWindow(persisted.pendingApartSince, now, graceMillis)
        continuousTogetherSinceMillis = if (effectiveTogether || stillWithinGrace) persisted.continuousTogetherSince else 0L

        selfHealOrphanedSession(persisted, isStale, stillWithinGrace, now)

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

        backfillReunionCountIfNeeded(persisted, now)
    }

    /**
     * Reunion-count non-retroactivity feature: one-time migration for an install that already has real
     * session history from before this feature shipped - see
     * [com.ssbmedia.twogether.data.datastore.ProximityPersistedState.reunionCount]/[reunionCountBackfilled]'s
     * own docs for the full design rationale. Every distinct real-world reunion this couple has ever had
     * was, until this feature existed, ALWAYS evaluated under the single hardcoded
     * [StatsCalculator.REUNION_GAP_MILLIS] (60 min) - the only threshold that has ever applied to any of
     * this app's history - so rescanning once with that fixed legacy value
     * ([StatsCalculator.legacyReunionCountForBackfill]) is genuinely unambiguous, not a "best guess."
     *
     * Called from every [restoreState] (i.e. every service start), but [ProximityPersistedState.
     * reunionCountBackfilled] makes every call after the very first a cheap no-op - this must never run
     * twice, or a live [com.ssbmedia.twogether.data.datastore.AppSettings.reunionThresholdMinutes] change
     * could get silently re-applied to the couple's ENTIRE history the next time the service happens to
     * restart, which is exactly the retroactive-recomputation bug this whole feature exists to prevent.
     */
    private suspend fun backfillReunionCountIfNeeded(persisted: ProximityPersistedState, now: Long) {
        if (persisted.reunionCountBackfilled) return
        val sessions = ServiceLocator.sessionRepository.getAll()
        val legacyCount = StatsCalculator.legacyReunionCountForBackfill(
            sessions,
            now = now,
            lastSeenAt = persisted.lastSeenAt,
            absenceTimeoutMillis = stateMachine.absenceTimeoutMillis
        )
        ServiceLocator.proximityStateStore.update {
            // Re-check inside the transaction (same defensive read-fresh-inside-edit{} pattern
            // ProximityStateStore.update's own doc requires of every caller) - if some other path already
            // completed the backfill by the time this write lands, never clobber a reunionCount that may
            // have since moved on from live handleBecameTogether() increments. Extracted to a pure
            // companion helper (backfilledReunionState) - unit-tested directly via reflection, same
            // reasoning as isReunion/nextReunionCount above.
            backfilledReunionState(it, legacyCount)
        }
    }

    private suspend fun selfHealOrphanedSession(persisted: ProximityPersistedState, isStale: Boolean, stillWithinGrace: Boolean, now: Long) {
        val openSession = ServiceLocator.sessionRepository.getOpenSession() ?: return
        if (persisted.isTogether && !isStale) return // plausibly still together right now; normal flow (a real apart transition) will close it correctly.
        // Item 9 (UX-FIX-PLAN.md): a session still inside its grace window when the process died is NOT
        // orphaned/stale - it's exactly the same "waiting to see if they reconnect" state a live apart
        // transition leaves behind, just interrupted by a restart. Leave it open; tick()'s own
        // checkGraceExpiry (running on the next tick, since the ticker starts right after restoreState()
        // completes) will close it for real once/if the window actually elapses, and
        // handleBecameTogether's resume path can still pick it back up on a reconnect in the meantime.
        if (stillWithinGrace) return
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
                        // MINOR fix (test-code-allmodels round 2, Opus - unfixed twin of GattSyncManager's
                        // buildPayload crash-loop fix): this was the one lifecycleScope.launch in this file
                        // with no try/catch, unlike every sibling launch (tick, restoreState, manualSyncRequests,
                        // unpaired, onPartnerSeen, periodic sync). ensureBleRunning() touches DataStore
                        // (pairingStore/settingsStore) - a plain IOException there (disk full, transient FS
                        // error) would otherwise crash this START_STICKY service, which Android would then
                        // restart only to hit the same condition again on the next Bluetooth toggle.
                        lifecycleScope.launch {
                            try {
                                ensureBleRunning()
                            } catch (e: Exception) {
                                Log.e(TAG, "ensureBleRunning failed after Bluetooth turned back on - will retry on the next tick", e)
                            }
                        }
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

        // Item 9 (UX-FIX-PLAN.md): checked on every tick while apart (not just the one tick where the
        // transition happened) - a TogetherSession row handleBecameApart left open only actually gets
        // closed once SESSION_GRACE_MILLIS has elapsed since the apart transition. See checkGraceExpiry's
        // own doc.
        if (!stateMachine.isTogether) {
            checkGraceExpiry(now)
        }

        if (stateMachine.isTogether) {
            checkPhotoReminder(now)
            // Item 24 (UX-FIX-PLAN.md): same "only while genuinely together" gate as the photo reminder
            // just above - see checkListReminders' own doc.
            checkListReminders(now)
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
            // reunionCount is the persisted, live-incrementing counter (see ProximityPersistedState.
            // reunionCount's own doc) - passed straight through, never recomputed here.
            val proximityStateForStats = ServiceLocator.proximityStateStore.current()
            val stats = StatsCalculator.compute(
                sessions,
                now = now,
                lastSeenAt = stateMachine.lastSeenAt,
                absenceTimeoutMillis = stateMachine.absenceTimeoutMillis,
                reunionCount = proximityStateForStats.reunionCount
            )
            val manualCredit = StatsCalculator.manualHoursCredit(
                sessions,
                now = now,
                lastSeenAt = stateMachine.lastSeenAt,
                absenceTimeoutMillis = stateMachine.absenceTimeoutMillis
            )
            // User-requested: fires a notification per capsule THIS call actually unlocked - see
            // Notifications.showCapsuleUnlockedNotification's own doc for why this fires here rather
            // than only when the Capsules screen happens to be open.
            val newlyUnlockedCapsules = ServiceLocator.timeCapsuleRepository.unlockEligible(stats.totalHoursAllTime.toFloat(), manualCredit)
            newlyUnlockedCapsules.forEach { capsule ->
                Notifications.showCapsuleUnlockedNotification(this, capsule.id)
            }

            // Item 15 (UX-FIX-PLAN.md): record each newly-unlocked badge's timestamp right here, in the
            // background tick, rather than only when the user happens to have the Badges screen open (the
            // old bug - BadgesViewModel.recordNewlyUnlocked was only ever invoked from a LaunchedEffect on
            // BadgesScreen). A badge earned in March but not viewed until July used to get persisted as
            // "unlocked on Jul 14" - wrong. This runs continuously in the background (same as the Time
            // Capsule unlock check just above), so the timestamp is captured close to when it actually
            // happens instead. recordUnlockIfNeeded is itself idempotent (first-write-wins, see
            // BadgeUnlocksStore's doc), so calling it here every minute for every already-unlocked badge is
            // harmless - only a badge crossing its threshold for the first time actually writes anything.
            // BadgesScreen's own recording call is left in place too (still correct, just now usually a
            // no-op) as a same-instant fallback for whoever has the screen open at the exact unlock moment.
            val unlockedBadgeIds = BadgeCatalog.statuses(stats).filter { it.unlocked }.map { it.badge.id }
            unlockedBadgeIds.forEach { ServiceLocator.badgeUnlocksStore.recordUnlockIfNeeded(it, now) }
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

        // Item 14 (update-nag reach fix): piggybacks a periodic update check on this service's own
        // ticker, using the same cadence-gating pattern as the two blocks above (secondsElapsed % N ==
        // 0L) - rather than relying only on WorkManager's own OS-level scheduling (UpdateWorker, which
        // Doze or an OEM battery manager can defer/skip) or the app-start check (which only fires once
        // per PROCESS start - and this long-lived foreground service is exactly what can keep the
        // process alive for days without a fresh start, which is how a real user went days on v2.6
        // without ever being prompted to update to v2.7's data-loss fix). Checked every 30 minutes here;
        // UpdateChecker.MIN_CHECK_INTERVAL_MS (the same constant the app-start check throttles against)
        // is what actually rate-limits real network calls to at most once per 6h - this cadence just
        // decides how often to even CONSIDER checking, not how often it hits the network.
        if (secondsElapsed % 1800 == 0L) {
            try {
                val settings = ServiceLocator.settingsStore.current()
                if (settings.autoUpdateCheckEnabled && now - settings.lastUpdateCheckAt > UpdateChecker.MIN_CHECK_INTERVAL_MS) {
                    ServiceLocator.settingsStore.setLastUpdateCheckAt(now)
                    UpdateChecker.checkAndNotify(this)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Periodic update check from the foreground service failed - will retry next cadence", e)
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
                // REVERTED (ultimate-app-review round 2, Opus): the round 1 self-echo guard this used to
                // be (isSelfEchoBeacon, dropped a sighting whose tie-break byte matched this device's own)
                // is gone. Live testing proved it was actively harmful: since the existing tie-break-
                // collision re-roll (startGattSyncIfNeeded, further down this file) only runs once
                // stateMachine.isTogether is true, a guard that unconditionally drops every matching
                // sighting BEFORE onBeaconSeen() made a genuine collision (~1/256 by random draw, and
                // GUARANTEED after restoring a backup made on the partner's own phone, since
                // BackupManager restores both deviceTieBreakByte and localDeviceId verbatim) permanently
                // undetectable - two real paired phones would simply never see each other again. Worse,
                // live-reproducing the exact scenario this guard was built for (partner's Bluetooth fully
                // disabled) showed the "4+ minutes together with partner's radio off" symptom happening
                // regardless of the guard, with the phantom sightings carrying the PARTNER's tie-break
                // byte, not this device's own - so the self-echo diagnosis was wrong to begin with.
                // Round 3 (Opus) narrowed this further: force-restarting the SCANNING device (a fresh
                // process, fresh scan registration) did NOT stop the phantom sightings, but killing the
                // ADVERTISING device's emulator process did, instantly - so this is the emulator's
                // virtualized Bluetooth transport continuing to serve the guest's advert on the host side
                // after the guest radio reports off, not a scanner-side cache. Very unlikely to occur on
                // real hardware (which never delivers a device's own adverts back to its own scanner, and
                // has no such host-level advertiser artifact).
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
        val settings = ServiceLocator.settingsStore.current()
        // Reunion-count non-retroactivity feature: reads the LIVE, user-configurable
        // AppSettings.reunionThresholdMinutes at this exact instant - not the old hardcoded
        // StatsCalculator.REUNION_GAP_MILLIS constant. This is what makes each reunion's evaluation a
        // genuine one-time, point-in-time decision: whatever threshold is in effect RIGHT NOW is applied
        // once, here, and never revisited - a later change to reunionThresholdMinutes can only ever affect
        // the NEXT transition this function evaluates, never this one after the fact (see
        // reunionCount below, and AppSettings.reunionThresholdMinutes' own doc for the full guarantee).
        //
        // Item 10 (UX-FIX-PLAN.md): dropped the same-calendar-day requirement this used to also check
        // (via StatsCalculator.isSameCalendarDay) - an overnight or multi-day reunion was silently never
        // celebrated before, however long the real gap was. Only the gap threshold matters now, mirroring
        // StatsCalculator.countReunions' own (now legacy-only) fix.
        val reunionThresholdMillis = settings.reunionThresholdMinutes.coerceAtLeast(1) * 60_000L
        // Extracted to a pure companion helper (see its own doc) - unit-tested directly via reflection,
        // mirroring withinGraceWindow/graceWindowExpired's established pattern, rather than only
        // indirectly through the full Service (which this project's plain-JVM unit tests can't
        // instantiate - no Robolectric/Context available).
        val isReunion = isReunion(persisted.lastApartSince, now, reunionThresholdMillis)

        // Reunion-count non-retroactivity feature: sessionGraceMinutes is now live/user-configurable too
        // (AppSettings.sessionGraceMinutes) - see withinGraceWindow's own doc. NOTE: unlike the old
        // hardcoded pair (SESSION_GRACE_MILLIS 10min « REUNION_GAP_MILLIS 60min), these two settings are
        // now independently user-editable, so that structural "grace is always far shorter than reunion"
        // invariant is no longer guaranteed - a user who sets reunionThresholdMinutes below
        // sessionGraceMinutes can see a grace-window "resume" ALSO flagged as a reunion (the session/timer
        // still resumes rather than restarting, since isReunion above is independent of resumableSession
        // below). That's an accepted edge case of making both settings independent, not a bug: a
        // sufficiently low reunion threshold is a deliberate "count almost every gap" choice.
        val graceMillis = settings.sessionGraceMinutes.coerceAtLeast(1) * 60_000L

        // Item 9 (UX-FIX-PLAN.md): if the apart transition that just ended is still within its grace
        // window and left a TogetherSession row open, this is a RESUME - a brief BLE gap that must not
        // reset the couple's continuous "together" streak. Falls through to the normal "new session" path
        // if the grace window already expired (checkGraceExpiry may have beaten this beacon sighting to
        // it and already closed the session) or there was never a pending apart to begin with.
        //
        // STORED-SESSION FIX (session-split): a resume used to pick the SAME session row back up
        // (sessionId = resumableSession.id, startedAt left untouched) so the row's eventual endedAt-minus-
        // startedAt span silently included the apart gap itself as together-time - a real, confirmed bug
        // (a 1h/15min-gap/1h round trip reported 2h15m of together-time instead of 2h00m). A resume now
        // closes the OLD row for real at the TRUE apart instant and opens a genuinely NEW row at this
        // reconnect instant, so the couple's history ends up as two accurate segments (0-60min, 75-135min
        // in that example) whose SUM is exactly 2h00m - StatsCalculator's existing interval-merge/sum logic
        // needs no changes at all, it already correctly sums whatever real closed intervals exist.
        val resumableSession = if (withinGraceWindow(persisted.pendingApartSince, now, graceMillis)) {
            ServiceLocator.sessionRepository.getOpenSession()
        } else {
            null
        }

        val sessionId: Long
        if (resumableSession != null) {
            // persisted.lastApartSince already holds the TRUE apart instant, clamped exactly the same way
            // every other "when did they actually go apart" read in this file is (StatsCalculator.
            // effectiveOpenSessionEnd, via handleBecameApart's own apartSince computation just above -
            // see its doc) - it is NOT recomputed here via effectiveOpenSessionEnd directly, because by
            // this point stateMachine.lastSeenAt has already been advanced to `now` by this same beacon
            // sighting's earlier onBeaconSeen(now) call (see onPartnerSeen), which would make that clamp
            // resolve to `now` (the RECONNECT instant) instead of the pre-gap sighting - reproducing
            // exactly the same inflation bug this fix exists to remove. lastApartSince was captured by
            // handleBecameApart BEFORE the gap, from the correct (then-current) stateMachine.lastSeenAt,
            // so it's the right value to reuse verbatim. coerceAtLeast defends against a degenerate span
            // the same way every sibling clamp call site in this file already does.
            val trueApartInstant = persisted.lastApartSince.coerceAtLeast(resumableSession.startedAt)
            ServiceLocator.sessionRepository.endSession(resumableSession, trueApartInstant)
            sessionId = ServiceLocator.sessionRepository.startSession(now)

            // DISPLAY-TIMER FIX: continuousTogetherSinceMillis (and its persisted mirror) used to be left
            // completely untouched across a resume, on the theory that the row itself was being resumed
            // unchanged - now that the row is genuinely new (see above), leaving this reference pointing at
            // the ORIGINAL streak start would double-count the gap a second way: `now - continuousSince`
            // would jump to include the 15-minute gap the instant reconnect happens (e.g. reading 1h15m
            // immediately on reconnect in the worked example, then climbing to 2h15m by the end) - the same
            // shape of bug as the stored-session one above, just in the live display instead of the DB.
            // resumedContinuousTogetherSince rebases this reference so `now - result` evaluates back to
            // EXACTLY however much genuine together-time had accrued as of trueApartInstant (persisted.
            // continuousTogetherSince still holds that pre-freeze reference - handleBecameApart
            // deliberately never touches it, see its own doc), then grows normally in real time from here.
            // A pure, unit-tested function (see SessionSplitDisplayTimerAuditTest) - it also correctly folds in
            // however many prior resumes already happened, since each one's rebase already absorbed the
            // ones before it.
            continuousTogetherSinceMillis = resumedContinuousTogetherSince(
                priorContinuousTogetherSince = persisted.continuousTogetherSince,
                trueApartInstant = trueApartInstant,
                now = now
            )
        } else {
            sessionId = ServiceLocator.sessionRepository.startSession(now)
            continuousTogetherSinceMillis = now
        }

        ServiceLocator.proximityStateStore.update {
            it.copy(
                isTogether = true,
                // Always continuousTogetherSinceMillis now (computed just above for BOTH branches) rather
                // than the old `if (resumableSession != null) it.continuousTogetherSince else now` - see
                // the DISPLAY-TIMER FIX comment above for why the resume branch needs a freshly rebased
                // value rather than the untouched original.
                continuousTogetherSince = continuousTogetherSinceMillis,
                currentSessionId = sessionId,
                reminderFiredForSession = false,
                snoozeUntil = 0L,
                pendingApartSince = 0L,
                pendingReunionCelebration = it.pendingReunionCelebration || isReunion,
                // Item 24 (UX-FIX-PLAN.md): a fresh continuous-together session gets a clean slate of
                // list/list-item reminders to re-fire, same reasoning as reminderFiredForSession's reset
                // just above.
                remindersFiredForSession = emptySet(),
                // Reunion-count non-retroactivity feature: incremented by exactly 1, in this SAME
                // transactional update{} call (not a second separate write) - see ProximityPersistedState.
                // reunionCount's own doc for why this is a live counter and not something ever recomputed
                // from history. `it.reunionCount` (the fresh in-transaction value, per ProximityStateStore.
                // update's own doc) rather than `persisted.reunionCount` read before this transaction
                // started, so a concurrent increment (shouldn't happen for this specific field today, but
                // matches this store's own established safety pattern) can never be lost. Extracted to a
                // pure companion helper (nextReunionCount) for the same reflection-testability reason as
                // isReunion above.
                reunionCount = nextReunionCount(it.reunionCount, isReunion)
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
        // lastApartSince must match the session's own estimated real-apart instant (the CLAMPED value
        // below), not the raw wall-clock `now` this tick happened to run at - otherwise reunion counting
        // drifts: the live celebration (ProximityForegroundService, keyed off lastApartSince) and the
        // historical Stats.reunionCount (StatsCalculator) can end up disagreeing about exactly when the
        // apart event happened for the same real-world gap, since `now` always lags the clamped instant by
        // however long the ticker took to notice the absence timeout. Mirrors restoreState()'s
        // estimatedApartSince for the same reason. This is computed regardless of the grace window below -
        // it's an ESTIMATE of when they actually went apart, independent of when the session row itself
        // gets torn down.
        val apartSince = if (openSession != null) {
            StatsCalculator.effectiveOpenSessionEnd(
                startedAt = openSession.startedAt,
                now = now,
                lastSeenAt = stateMachine.lastSeenAt,
                absenceTimeoutMillis = stateMachine.absenceTimeoutMillis
            ).coerceAtLeast(openSession.startedAt)
        } else {
            now
        }
        // Item 9 (UX-FIX-PLAN.md): do NOT end the session (or reset the continuous-together timer) right
        // away. The fast ~100s isTogether flip just above is intentionally immediate/honest for the
        // Apart/Together status card - but the underlying TogetherSession row now gets a grace window
        // (SESSION_GRACE_MILLIS, ~10 min) before it's actually torn down, tracked via pendingApartSince, so
        // a brief reconnect within that window doesn't reset the couple's continuous "together" streak to
        // zero. See tick()'s checkGraceExpiry for where the session actually gets closed for real once/if
        // the window elapses with no reconnect. continuousTogetherSinceMillis (and the persisted
        // continuousTogetherSince mirror) are deliberately left untouched here - a resume needs them still
        // holding the PRE-freeze reference (see handleBecameTogether's resumedContinuousTogetherSince,
        // which reads this exact value to compute the rebased display reference), not reset or advanced.
        // STORED-SESSION FIX (session-split): a resume no longer picks this exact row back up - see
        // handleBecameTogether's resumableSession branch doc - it closes THIS row for real at the true
        // apart instant and opens a brand new one, so the gap itself is never silently credited as
        // together-time once the couple's streak eventually ends. lastApartSince (set right below) is
        // exactly the true-apart instant that close will use.
        ServiceLocator.proximityStateStore.update {
            it.copy(
                isTogether = false,
                // currentSessionId deliberately left untouched when there IS an open session (it already
                // correctly points at it, and it's still "the" session pending the grace-window decision -
                // see checkGraceExpiry, which is what actually resets this to -1). Only forced back to -1
                // here in the (edge-case) event there was no open session to begin with, so this can never
                // drift to point at nothing.
                currentSessionId = if (openSession != null) it.currentSessionId else -1L,
                reminderFiredForSession = false,
                snoozeUntil = 0L,
                // A reunion celebration that was never displayed belongs to the together-stretch that just
                // ended. Clearing it here stops it from firing on a later app open taken while apart, or
                // from carrying over into an unrelated future together-stretch (HomeScreen's own guard
                // only checks isTogether, which is true again the next time they meet).
                pendingReunionCelebration = false,
                lastApartSince = apartSince,
                pendingApartSince = if (openSession != null) now else 0L,
                // Item 24 (UX-FIX-PLAN.md): same reset as reminderFiredForSession above - the
                // together-session that just ended is over, so its fired-reminders record is done too.
                remindersFiredForSession = emptySet()
            )
        }
        Notifications.cancelPhotoReminder(this)
        gattReadyForSession = false
        gattSync.stopServer()
        gattSync.disconnectClient()
        resetClientSyncGuard()
        updateNotification(now)
    }

    /** Item 9 (UX-FIX-PLAN.md): checked every tick while apart (see tick()'s call site) - once
     * SESSION_GRACE_MILLIS has elapsed since handleBecameApart recorded pendingApartSince, the
     * TogetherSession row it deliberately left open is now actually closed for real, and the
     * continuous-together timer resets. Until this fires, handleBecameTogether's resume path can still
     * pick the same session back up on a reconnect. No-ops (cheaply) on every tick before the window
     * elapses, and once there's no pendingApartSince to act on at all (already resolved one way or the
     * other, or never together to begin with). */
    private suspend fun checkGraceExpiry(now: Long) {
        val persisted = ServiceLocator.proximityStateStore.current()
        // Reunion-count non-retroactivity feature: live setting, not the old hardcoded SESSION_GRACE_MILLIS.
        val graceMillis = ServiceLocator.settingsStore.current().sessionGraceMinutes.coerceAtLeast(1) * 60_000L
        if (!graceWindowExpired(persisted.pendingApartSince, now, graceMillis)) return

        val openSession = ServiceLocator.sessionRepository.getOpenSession()
        if (openSession != null) {
            // Same clamp formula as handleBecameApart/selfHealOrphanedSession - stateMachine.lastSeenAt
            // hasn't moved since they went apart (no new sightings), so this resolves to the exact same
            // estimated-real-apart instant regardless of how much of the grace window has elapsed since:
            // effectiveOpenSessionEnd's minOf(now, lastSeenAt + absenceTimeout) picks the earlier,
            // already-fixed bound either way. The grace window's extra wait time is never itself credited
            // as together-time.
            val clampedEnd = StatsCalculator.effectiveOpenSessionEnd(
                startedAt = openSession.startedAt,
                now = now,
                lastSeenAt = stateMachine.lastSeenAt,
                absenceTimeoutMillis = stateMachine.absenceTimeoutMillis
            ).coerceAtLeast(openSession.startedAt)
            ServiceLocator.sessionRepository.endSession(openSession, clampedEnd)
        }
        continuousTogetherSinceMillis = 0L
        ServiceLocator.proximityStateStore.update {
            it.copy(continuousTogetherSince = 0L, currentSessionId = -1L, pendingApartSince = 0L)
        }
    }

    private suspend fun checkPhotoReminder(now: Long) {
        val settings = ServiceLocator.settingsStore.current()
        if (!settings.notificationsEnabled) return
        val persisted = ServiceLocator.proximityStateStore.current()
        if (!persisted.isTogether || persisted.continuousTogetherSince <= 0L) return

        // Item 24 (UX-FIX-PLAN.md): was a hardcoded FIFTEEN_MINUTES_MILLIS constant - now reads the
        // user-configured AppSettings.photoReminderMinutes (Settings' own "Photo reminder interval" row).
        // coerceAtLeast(1) is defensive against a stray 0/negative value (shouldn't normally happen, the
        // Settings dialog itself validates) ever making this fire immediately/every tick.
        val reminderThresholdMillis = settings.photoReminderMinutes.coerceAtLeast(1) * 60_000L
        if (!persisted.reminderFiredForSession) {
            if (now - persisted.continuousTogetherSince >= reminderThresholdMillis) {
                // Only consume the one-per-session reminder if it actually posted (e.g. not silently
                // skipped for lack of POST_NOTIFICATIONS) - otherwise a denied permission would burn
                // this session's only reminder for nothing, and the ticker naturally retries every 5s
                // until permission is granted or the session ends.
                if (Notifications.showPhotoReminder(this, settings.photoReminderMinutes)) {
                    ServiceLocator.proximityStateStore.update { it.copy(reminderFiredForSession = true) }
                }
            }
        } else if (persisted.snoozeUntil > 0L && now >= persisted.snoozeUntil) {
            if (Notifications.showPhotoReminder(this, settings.photoReminderMinutes)) {
                ServiceLocator.proximityStateStore.update { it.copy(snoozeUntil = 0L) }
            }
        }
    }

    /**
     * Item 24 (UX-FIX-PLAN.md): evaluates every active DateIdea's own
     * [com.ssbmedia.twogether.data.db.DateIdea.remindAfterTogetherMinutes] and every active
     * ListCategory's own [com.ssbmedia.twogether.data.db.ListCategory.defaultRemindAfterTogetherMinutes]
     * against how long the couple has been CONTINUOUSLY together this session (persisted.
     * continuousTogetherSince - the exact same clock checkPhotoReminder above uses), firing at most one
     * notification per idea/list per together-session (persisted.remindersFiredForSession - see its own
     * doc for the "idea:<id>"/"list:<id>" key shape), mirroring checkPhotoReminder's own
     * fired-once-per-session pattern. A per-item reminder and its owning list's default reminder are
     * fully independent of each other - both can fire (or neither, or just one) in the same session.
     */
    private suspend fun checkListReminders(now: Long) {
        val settings = ServiceLocator.settingsStore.current()
        if (!settings.notificationsEnabled) return
        val persisted = ServiceLocator.proximityStateStore.current()
        if (!persisted.isTogether || persisted.continuousTogetherSince <= 0L) return
        val elapsedMillis = now - persisted.continuousTogetherSince
        if (elapsedMillis < 0L) return

        val alreadyFired = persisted.remindersFiredForSession
        val newlyFired = mutableSetOf<String>()

        val lists = ServiceLocator.listCategoryRepository.getAll().filter { !it.deleted }
        val listsById = lists.associateBy { it.id }

        val ideas = ServiceLocator.dateIdeaRepository.getAll()
        for (idea in ideas) {
            // A completed idea (checked off, which moves it into the Completed section) no longer needs
            // reminding about - an alert for it firing after it was already done is exactly the bug this
            // guards against. Re-checked on every tick, so an idea checked off before its threshold is
            // simply never announced, and one un-checked later becomes eligible again.
            if (!isReminderEligibleIdea(idea)) continue
            val minutes = idea.remindAfterTogetherMinutes ?: continue
            val key = "idea:${idea.id}"
            if (key in alreadyFired) continue
            if (!listReminderThresholdCrossed(minutes, elapsedMillis)) continue
            val list = listsById[idea.listId]
            if (Notifications.showListItemReminder(this, idea.id, idea.listId, list?.name ?: "Our Lists", idea.text)) {
                newlyFired += key
            }
        }

        for (list in lists) {
            val minutes = list.defaultRemindAfterTogetherMinutes ?: continue
            val key = "list:${list.id}"
            if (key in alreadyFired) continue
            if (!listReminderThresholdCrossed(minutes, elapsedMillis)) continue
            if (Notifications.showListReminder(this, list.id, list.name)) {
                newlyFired += key
            }
        }

        if (newlyFired.isNotEmpty()) {
            ServiceLocator.proximityStateStore.update { it.copy(remindersFiredForSession = it.remindersFiredForSession + newlyFired) }
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
            // MINOR fix (test-code-allmodels round 3, Opus - unfixed twin of the STATE_ON receiver's own
            // fix this same round): this was the one lifecycleScope.launch in this file still missing a
            // try/catch, despite that fix's own comment claiming to have closed "the one" - setLastSyncAt
            // is a DataStore edit{}, which throws IOException on a real write failure (disk full,
            // transient FS error); with no CoroutineExceptionHandler on this scope, that would crash this
            // START_STICKY service on every completed sync attempt while the failure persists - the exact
            // crash-loop shape every other launch in this file already guards against.
            lifecycleScope.launch {
                try {
                    if (success) ServiceLocator.settingsStore.setLastSyncAt(System.currentTimeMillis())
                    // MINOR fix (H): always emit (not just for manual=true triggers) - Date Ideas screen's
                    // "Last synced Xm ago" should refresh after ANY sync that actually completed, including
                    // the 15-minute periodic catch-all, not just a manual "Sync now" tap. Harmless when
                    // nothing is collecting (MutableSharedFlow just buffers/drops).
                    // MAJOR fix (ultimate-app-review, Fable F-4, assertion 6): a successful merge can still
                    // have dropped rows as implausible - report the count so the UI doesn't say an
                    // unqualified "Synced!" over real, silent data loss.
                    // MAJOR fix (ultimate-app-review round 1, Opus+Sonnet): surface WHY a failed sync
                    // failed when it was specifically a pinned-partner mismatch, so the UI can point the
                    // user at the fix (unpair + fresh code) instead of the misleading generic message.
                    AppEvents.emitSyncCompleted(
                        success,
                        if (success) gattSync.lastSyncDroppedImplausibleCount else 0,
                        if (!success) gattSync.lastSyncFailedDueToPartnerMismatch else false
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist lastSyncAt / emit sync-completed event - continuing rather than crashing the service", e)
                }
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
            "💕 Together for ${RelativeTime.formatDuration(elapsedMillis)}"
        } else {
            if (stateMachine.lastSeenAt > 0L) {
                // BUG fix (Phase 1 item 2 of UX-FIX-PLAN.md): was `formatDuration(elapsed) + " ago"`,
                // which caps at hours and never rolls to days ("96h 12m ago" instead of "4d ago") -
                // relativeAgo already produces "ago"-style text with the correct day rollover.
                val elapsed = (now - stateMachine.lastSeenAt).coerceAtLeast(0L)
                "Apart · last saw them ${RelativeTime.relativeAgo(elapsed)}"
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

        /** Item 9 (UX-FIX-PLAN.md) / reunion-count non-retroactivity feature: how long a TogetherSession
         * row (and the continuous-together timer) is given after the fast ~100s isTogether apart-flip
         * before it's actually torn down for real - see handleBecameApart/checkGraceExpiry/
         * handleBecameTogether's resume path. Used to be a hardcoded constant here
         * (SESSION_GRACE_MILLIS, 10 min) - now a live, user-configurable setting
         * (AppSettings.sessionGraceMinutes, same 10-minute default), read fresh at each call site
         * (restoreState/handleBecameTogether/checkGraceExpiry) and passed into [withinGraceWindow]/
         * [graceWindowExpired] as [graceMillis] rather than baked into these functions. See
         * handleBecameTogether's own doc for why this is no longer structurally guaranteed to stay shorter
         * than the (also now-configurable) reunion threshold, unlike the old fixed pair. */

        /** Pure decision helper - unit-tested directly (via reflection, since it's private - see
         * GraceWindowAuditTest) rather than only indirectly through the full Service. True iff a reconnect
         * at [now] should resume the session left open at [pendingApartSince] (a
         * ProximityPersistedState.pendingApartSince value) rather than starting a brand new one, given a
         * grace window of [graceMillis] (the live AppSettings.sessionGraceMinutes value, converted to
         * millis by the caller). Also true for [checkGraceExpiry]'s own "should I close it yet" question
         * via [graceWindowExpired] below - the two are complements of each other for any
         * pendingApartSince > 0. */
        private fun withinGraceWindow(pendingApartSince: Long, now: Long, graceMillis: Long): Boolean =
            pendingApartSince > 0L && now - pendingApartSince < graceMillis

        /** Pure decision helper (private, unit-tested via reflection) - true iff the grace window of
         * [graceMillis] opened at [pendingApartSince] has now elapsed and a still-open session should
         * actually be closed. */
        private fun graceWindowExpired(pendingApartSince: Long, now: Long, graceMillis: Long): Boolean =
            pendingApartSince > 0L && now - pendingApartSince >= graceMillis

        /** DISPLAY-TIMER FIX (session-split feature): pure helper - unit-tested directly via reflection
         * (see SessionSplitDisplayTimerAuditTest), mirroring withinGraceWindow/graceWindowExpired's established
         * pattern. Computes the rebased "continuous together since" reference to use the instant a
         * grace-window RESUME happens (handleBecameTogether's resumableSession branch), such that `now -
         * result` evaluates to exactly [priorContinuousTogetherSince]..[trueApartInstant]'s span (i.e. the
         * genuine together-time accrued before the gap), FROZEN through the gap and then growing normally
         * in real time from this instant on - never crediting the gap itself.
         *
         * [priorContinuousTogetherSince] is the pre-freeze reference from BEFORE this resume - handleBecameApart
         * never touches continuousTogetherSince, so it's still whatever it was when the couple was last
         * genuinely, continuously together (which may itself already be a rebased value from an EARLIER
         * resume in the same streak - see this function's own chained-resume unit test for why that still
         * folds in correctly rather than double-counting or losing time). [trueApartInstant] is the same
         * clamped true-apart instant [handleBecameTogether] closes the old session row at.
         *
         * coerceAtLeast(0L) defends against a (should-be-impossible) negative accumulated span from a
         * corrupted/hand-edited persisted value - never lets a resume start the display timer count DOWN
         * or into negative territory. */
        private fun resumedContinuousTogetherSince(priorContinuousTogetherSince: Long, trueApartInstant: Long, now: Long): Long {
            val frozenAccumulatedMillis = (trueApartInstant - priorContinuousTogetherSince).coerceAtLeast(0L)
            return now - frozenAccumulatedMillis
        }

        /** Reunion-count non-retroactivity feature: pure decision helper - unit-tested directly via
         * reflection (see ReunionNonRetroactivityAuditTest), mirroring [withinGraceWindow]/
         * [graceWindowExpired]'s established pattern. True iff the apart-gap ending at [now] (measured
         * from [lastApartSince], a ProximityPersistedState.lastApartSince value) clears
         * [reunionThresholdMillis] - the LIVE, user-configurable AppSettings.reunionThresholdMinutes value
         * at the moment [handleBecameTogether] calls this, converted to millis by the caller. This is the
         * ENTIRE one-time, point-in-time reunion decision: called exactly once per apart->together
         * transition, its result is used immediately (to fire the celebration and increment
         * [nextReunionCount] below) and never revisited - a later change to reunionThresholdMinutes cannot
         * affect a decision this function already made, only a future call to it. */
        private fun isReunion(lastApartSince: Long, now: Long, reunionThresholdMillis: Long): Boolean =
            lastApartSince > 0L && now - lastApartSince >= reunionThresholdMillis

        /** Reunion-count non-retroactivity feature: pure decision helper - the ENTIRE "increment the
         * persisted counter by exactly 1" logic, unit-tested directly via reflection. [currentCount] is
         * the fresh in-transaction ProximityPersistedState.reunionCount value (see
         * ProximityStateStore.update's own doc for why it must be the in-transaction value, not one read
         * before the transaction started) - this function itself has no notion of "before/after", it's a
         * pure function of (current count, did a reunion just happen). */
        private fun nextReunionCount(currentCount: Int, isReunion: Boolean): Int =
            if (isReunion) currentCount + 1 else currentCount

        /** Reunion-count non-retroactivity feature: pure decision helper for
         * [backfillReunionCountIfNeeded]'s one-time migration - unit-tested directly via reflection. If
         * [current] (the fresh in-transaction ProximityPersistedState) has already been backfilled, returns
         * it UNCHANGED (never re-applies [legacyCount], even if called again) - otherwise seeds
         * reunionCount with [legacyCount] and sets the flag. This is what makes the backfill idempotent:
         * calling this function 100 times with the same [current]-already-backfilled input always produces
         * the same unchanged result, never a re-application. */
        private fun backfilledReunionState(current: ProximityPersistedState, legacyCount: Int): ProximityPersistedState =
            if (current.reunionCountBackfilled) current else current.copy(reunionCount = legacyCount, reunionCountBackfilled = true)

        /** Item 24 (UX-FIX-PLAN.md): pure decision helper for the list/list-item reminder feature -
         * unit-tested directly (via reflection, mirroring GraceWindowAuditTest's pattern for testing a
         * private companion function without standing up the full Android Service). True iff
         * [elapsedTogetherMillis] (time since the current continuous together-session began - see
         * checkListReminders) has crossed [thresholdMinutes] (the user-set "remind me X minutes after
         * we're together" value on a DateIdea or ListCategory). A non-positive threshold never fires -
         * defensive: the Settings/Our Lists UI dialogs already validate their own input to be positive,
         * but this is a second, structural gate against a corrupted/hand-crafted-backup value (e.g. 0,
         * which would otherwise fire on literally the first tick of every together-session). */
        private fun listReminderThresholdCrossed(thresholdMinutes: Int, elapsedTogetherMillis: Long): Boolean =
            thresholdMinutes > 0 && elapsedTogetherMillis >= thresholdMinutes * 60_000L

        /** Whether a DateIdea may still fire its own "remind me after we're together" alert. Deleted ideas and
         * completed (done) ideas never do: an idea that's already been checked off is finished, so reminding
         * about it is noise. Pure and internal so it can be unit-tested directly. */
        internal fun isReminderEligibleIdea(idea: com.ssbmedia.twogether.data.db.DateIdea): Boolean =
            !idea.deleted && !idea.done
    }
}
