package com.ssbmedia.twogether.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lightweight in-process event bus so the UI and the background service can nudge each other. */
object AppEvents {
    private val _reunionEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reunionEvents = _reunionEvents.asSharedFlow()

    fun emitReunion() {
        _reunionEvents.tryEmit(Unit)
    }

    /** UI -> service: "Sync now" was tapped on the Date Ideas screen. */
    private val _manualSyncRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val manualSyncRequests = _manualSyncRequests.asSharedFlow()

    fun requestManualSync() {
        _manualSyncRequests.tryEmit(Unit)
    }

    /** service -> UI: a sync just finished (successfully or not), so "last synced" can refresh. */
    private val _syncCompleted = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val syncCompleted = _syncCompleted.asSharedFlow()

    /** MAJOR fix (ultimate-app-review, Fable F-4, assertion 6): how many incoming rows THIS device's
     * own most recent completed sync rejected as implausible (see GattSyncManager.
     * lastSyncDroppedImplausibleCount's doc) - set by the service immediately before emitting the
     * [syncCompleted] event below, so a collector reading it at that moment sees the count for THIS
     * outcome, not a stale one. A plain property rather than folding into [syncCompleted]'s own emitted
     * value: keeps every existing `emitSyncCompleted(success)` call site (most of which report a plain
     * failure with nothing to count) unchanged. Deliberately not `@Volatile`-annotated to a lock - this
     * event bus is already the established "good enough" cross-thread signaling pattern for the rest of
     * this object (a plain var here would be inconsistent, so it keeps the same MutableStateFlow shape).
     */
    private val _lastSyncDroppedCount = MutableStateFlow(0)
    val lastSyncDroppedCount: StateFlow<Int> = _lastSyncDroppedCount.asStateFlow()

    /** MAJOR fix (ultimate-app-review round 1, Opus+Sonnet): true when the most recent completed sync
     * failed specifically because the sender's device id didn't match this pairing's pinned partner (see
     * GattSyncManager.lastSyncFailedDueToPartnerMismatch's own doc for the reinstall-lockout scenario this
     * exists to surface) - as opposed to any other failure (not together, timeout, malformed payload).
     * Same read-right-before-emit pattern as [lastSyncDroppedCount] above, so Settings/OurListsScreen can
     * show an actionable message instead of the generic "make sure you're together" text. */
    private val _lastSyncFailedDueToPartnerMismatch = MutableStateFlow(false)
    val lastSyncFailedDueToPartnerMismatch: StateFlow<Boolean> = _lastSyncFailedDueToPartnerMismatch.asStateFlow()

    fun emitSyncCompleted(success: Boolean, droppedCount: Int = 0, partnerMismatch: Boolean = false) {
        _lastSyncDroppedCount.value = droppedCount
        _lastSyncFailedDueToPartnerMismatch.value = partnerMismatch
        _syncCompleted.tryEmit(success)
    }

    /** service -> UI: a manual "Sync now" tap resolved to this device holding the passive GATT SERVER
     * role, which can never proactively pull a sync on demand - only the partner's client connecting
     * can complete one. Without this, OurListsScreen has no way to distinguish "genuinely broken" from
     * "working correctly but passive", and always falls through to its generic timeout/failure message
     * even when the couple is together and passive sync is in fact working. */
    private val _syncListening = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val syncListening = _syncListening.asSharedFlow()

    fun emitSyncListening() {
        _syncListening.tryEmit(Unit)
    }

    /** UI -> service: pairing was just torn down (Settings "Unpair"). Without this, the service only
     * notices on its next 5s ticker tick that pairing.isPaired flipped false, and even then it only
     * skipped calling start() again - it never actually stopped the advertiser/scanner that were
     * already running, so the service kept broadcasting/scanning with the now-deleted pairing's secret
     * for a window after unpairing. This lets the service react immediately instead of waiting on the
     * ticker (and ensureBleRunning now also stops BLE outright once it sees isPaired=false, as a
     * second safety net if this event is ever missed - e.g. service not running at unpair time). */
    private val _unpaired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unpaired = _unpaired.asSharedFlow()

    fun emitUnpaired() {
        _unpaired.tryEmit(Unit)
    }

    /** onboarding -> Home: a pairing action that could produce a stale/mismatched device-ID pin was
     * just completed - either "Reconnect to X" (PairingViewModel.reconnect()) or finishing a fresh
     * Create-Pair/Join-Pair flow (PairingViewModel.finishPairing()). Both share the same underlying
     * risk: re-pairing (by any route) resets THIS device's own pin, but if the other phone in the
     * pairing never went through its own reset, it keeps trusting whatever device ID it saw last -
     * live-confirmed by the user hitting exactly this via Unpair -> Join Pair -> re-entering the
     * partner's still-displayed code, not just via "Reconnect". A genuinely first-time pairing between
     * two devices that have never met is harmless to check too - neither side has a pin yet, so the
     * verification below simply can't find a mismatch and stays silent.
     * See PairingViewModel.reconnect()'s own doc for why the verification this triggers can't happen ON
     * the pairing screen itself: MainActivity's top-level routing reactively swaps away from
     * PairingScreen the INSTANT the pairing flip lands, before any check running there could ever
     * complete or show anything (live-confirmed empirically). Home is the first screen guaranteed to
     * actually stick around afterward, so it's the one that verifies the pairing actually works and
     * tells the user if it didn't. A `StateFlow`, not a `SharedFlow`, deliberately - Home's own
     * collector starts only once IT mounts, which happens strictly after this is set (Home doesn't
     * exist until MainActivity's routing has already left PairingScreen); a replay=0 SharedFlow would
     * drop the event exactly the same way the original bug did, which is the whole thing this design
     * has to avoid. consumeJustPaired() resets it back to false so it doesn't refire on every later
     * Home recomposition (rotation, backgrounding, etc.) - same one-shot latch shape as NavGraph's own
     * latchedMilestoneId. */
    private val _justPaired = MutableStateFlow(false)
    val justPaired: StateFlow<Boolean> = _justPaired.asStateFlow()

    fun emitJustPaired() {
        _justPaired.value = true
    }

    fun consumeJustPaired() {
        _justPaired.value = false
    }

    /** service -> UI: syncIds of Moments this device is CURRENTLY in the middle of requesting/receiving
     * photo bytes for over GATT (Feature 2), so MomentsScreen can show a "Receiving photo…" progress
     * indicator instead of the old static "photo is on your partner's phone" placeholder while a
     * transfer is genuinely in flight, and fall back to that same placeholder for anything not yet
     * requested this session. Always replaced wholesale (not additive) - see GattSyncManager's photo
     * phase for where this is set/cleared. */
    private val _momentsTransferring = MutableStateFlow<Set<String>>(emptySet())
    val momentsTransferring: StateFlow<Set<String>> = _momentsTransferring.asStateFlow()

    fun setMomentsTransferring(syncIds: Set<String>) {
        _momentsTransferring.value = syncIds
    }

    /** MainActivity (dispatchKeyEvent) -> CameraScreen: a volume button was pressed while the Camera
     * screen is the active destination (see [com.ssbmedia.twogether.util.VolumeShutterKeyHandler] for
     * the pure key-event decision logic that decides when this fires). MainActivity is a plain Activity
     * method, not a composable, so it has no direct reference to CameraScreen's own capture function -
     * this event bus is the existing established pattern in this file for exactly that kind of
     * Activity/service -> UI nudge (see [reunionEvents], [unpaired], etc above), reused here rather than
     * inventing a second mechanism. */
    private val _cameraShutterRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cameraShutterRequests = _cameraShutterRequests.asSharedFlow()

    fun requestCameraShutter() {
        _cameraShutterRequests.tryEmit(Unit)
    }
}
