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

    fun emitSyncCompleted(success: Boolean) {
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
}
