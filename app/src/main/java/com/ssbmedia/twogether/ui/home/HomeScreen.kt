package com.ssbmedia.twogether.ui.home

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ssbmedia.twogether.BuildConfig
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.badges.BadgeCatalog
import com.ssbmedia.twogether.badges.BadgeProgressRow
import com.ssbmedia.twogether.ble.BlePermissions
import com.ssbmedia.twogether.ble.ProximityStateMachine
import com.ssbmedia.twogether.data.datastore.AppSettings
import com.ssbmedia.twogether.data.datastore.PairingInfo
import com.ssbmedia.twogether.data.datastore.ProximityPersistedState
import com.ssbmedia.twogether.data.db.Milestone
import com.ssbmedia.twogether.data.db.Moment
import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.data.update.UpdateChecker
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.notif.InboxItem
import com.ssbmedia.twogether.notif.InboxItemKind
import com.ssbmedia.twogether.notif.InboxSignals
import com.ssbmedia.twogether.notif.NotificationInbox
import com.ssbmedia.twogether.service.ProximityForegroundService
import com.ssbmedia.twogether.stats.OnThisDayInfo
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.ui.badges.BadgeProgressBarRow
import com.ssbmedia.twogether.ui.components.PulsingHeart
import com.ssbmedia.twogether.ui.components.QuickLinkChip
import com.ssbmedia.twogether.ui.components.SectionHeader
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import com.ssbmedia.twogether.util.BatteryOptimization
import com.ssbmedia.twogether.util.DndAccess
import com.ssbmedia.twogether.util.PhotoEditor
import com.ssbmedia.twogether.util.RelativeTime
import com.ssbmedia.twogether.ui.update.UpdateInstallActivity
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Stable ids for each Home "Quick links" chip - persisted as the drag-and-drop reorder preference
 * (SettingsStore.setQuickLinksOrder) instead of the display label, which could change wording later
 * without silently losing everyone's saved custom order. */
private object QuickLinkIds {
    const val CALENDAR = "calendar"
    const val DATE_IDEAS = "dateIdeas"
    const val MOMENTS = "moments"
    const val STATS = "stats"
    const val TIME_CAPSULES = "timeCapsules"
    const val BADGES = "badges"
    const val MILESTONES = "milestones"
    const val CAMERA = "camera"
}

/** The original hardcoded order, also used as the fallback when no reorder preference is stored yet
 * (fresh install / never dragged) and as the tail-end insertion order for any id a stored preference
 * doesn't mention (e.g. a future quick link added after someone already customized their layout). */
private val DEFAULT_QUICK_LINK_ORDER = listOf(
    QuickLinkIds.CALENDAR, QuickLinkIds.DATE_IDEAS, QuickLinkIds.MOMENTS, QuickLinkIds.STATS,
    QuickLinkIds.TIME_CAPSULES, QuickLinkIds.BADGES, QuickLinkIds.MILESTONES, QuickLinkIds.CAMERA
)

private data class QuickLinkDef(val id: String, val emoji: String, val label: String, val onClick: () -> Unit)

/** Reconciles a raw stored comma-joined id list against [DEFAULT_QUICK_LINK_ORDER]: drops any unknown/
 * stale id (e.g. a quick link that got removed) and appends any known id missing from the stored value
 * (e.g. a quick link added after this person already customized their order) at the end, in its default
 * relative position - so this always returns exactly the current 8 ids, exactly once each, regardless
 * of what's actually in DataStore. */
private fun reconcileQuickLinkOrder(stored: String?): List<String> {
    val known = DEFAULT_QUICK_LINK_ORDER.toSet()
    val storedIds = stored?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
    val reconciled = LinkedHashSet<String>()
    storedIds.forEach { if (it in known) reconciled.add(it) }
    DEFAULT_QUICK_LINK_ORDER.forEach { reconciled.add(it) }
    return reconciled.toList()
}

class HomeViewModel : ViewModel() {
    val pairingInfo = ServiceLocator.pairingStore.info
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PairingInfo())

    val quickLinksOrder: StateFlow<List<String>> = ServiceLocator.settingsStore.settings
        .map { reconcileQuickLinkOrder(it.quickLinksOrder) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_QUICK_LINK_ORDER)

    /** SECURITY (user-designed follow-up): the user's explicit "yes, that's my partner" response to one
     * specific [PendingResyncRequest], identified by [deviceId] (see PairingStore.approvePendingResync's
     * doc for why this must always target a specific id, never "whatever's pending"). Per the user's own
     * explicit ask, a successful approval immediately nudges a real retry - the request that was rejected
     * moments ago should now succeed since the pin was just re-pointed at it - rather than the user having
     * to wait for the next natural proximity cycle or manually back out to Our Lists and tap "Sync now". */
    fun approvePendingResync(deviceId: String) {
        viewModelScope.launch {
            val approved = ServiceLocator.pairingStore.approvePendingResync(deviceId)
            if (approved) {
                repeat(15) {
                    AppEvents.requestManualSync()
                    delay(1_000)
                }
            }
        }
    }

    /** The user's explicit "no, that's not my partner" response (or simply dismissing that one entry) -
     * see PairingStore.dismissPendingResync's doc. Leaves any other still-pending requests untouched. */
    fun dismissPendingResync(deviceId: String) {
        viewModelScope.launch { ServiceLocator.pairingStore.dismissPendingResync(deviceId) }
    }

    /** Item 14 (update-nag reach fix): the whole AppSettings snapshot, not just the pending-update
     * fields alone - matches SettingsViewModel's own `val settings` pattern (SettingsScreen.kt) so both
     * screens read the exact same durable flag UpdateChecker sets, rather than Home growing its own
     * narrower duplicate of this store's mapping logic. */
    val settings: StateFlow<AppSettings> = ServiceLocator.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setQuickLinksOrder(order: List<String>) {
        viewModelScope.launch { ServiceLocator.settingsStore.setQuickLinksOrder(order) }
    }

    val sessions: StateFlow<List<TogetherSession>> = ServiceLocator.sessionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val openSession: StateFlow<TogetherSession?> = ServiceLocator.sessionRepository.observeOpenSession()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // The service's own live proximity read (updated every 5s tick, and self-healed on service
    // startup) - source of truth for "are we together right now", rather than blindly trusting that
    // any open Room session row means together. A leftover open row from a crash/reboot self-heals via
    // the service's restoreState() and shouldn't paint a permanently-false "together" status here.
    val proximityState: StateFlow<ProximityPersistedState> = ServiceLocator.proximityStateStore.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProximityPersistedState())

    val moments: StateFlow<List<Moment>> = ServiceLocator.momentRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Feature: link a Moment's photo to a milestone - lets [MemoryThrowbackCard] show the occasion's
     * name instead of a generic "a memory from X ago" caption when the randomly-picked photo happens to
     * be one someone chose to link. See Milestone.linkedMomentSyncId's own doc. */
    val milestones: StateFlow<List<Milestone>> = ServiceLocator.milestoneRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Picked once per Home visit (this ViewModel instance) — a simple "throwback" surface. User-
    // requested slideshow: when more than one anniversary photo exists (see pickRandomMomentIfNeeded's
    // own doc), this holds all of them (capped) so MemoryThrowbackCard can page through them; a plain
    // random pick (no anniversary match) is always a single-element list.
    val memoryMoments: MutableStateFlow<List<Moment>> = MutableStateFlow(emptyList())
    // MINOR fix (independent audit): MemoryThrowbackCard used to infer "is this an anniversary match"
    // from `moments.size > 1` - correct in the direction it reasoned (more than one photo can ONLY
    // happen for an anniversary match), but the reverse wasn't handled: an anniversary pool of EXACTLY
    // one photo (there's only ever been one other year with a photo on this exact date so far) is also
    // size 1, so it silently fell into the generic "A memory from Xh ago" framing - even though the app
    // genuinely knows it's an on-this-day match. Explicit flag instead of inferring from list size.
    val memoryMomentsIsAnniversary: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Only ever picks from moments this device actually HOLDS the photo bytes for - a remote-stub
     * moment (partner's photo metadata synced, but the bytes haven't transferred yet - see
     * Moment.photoDownloaded's doc) has nothing to show, and a card whose entire point is surfacing a
     * photo memory shouldn't ever land on one it can't display. MomentsScreen already guards this same
     * situation at render time (photoDownloaded + File(photoUri).isFile before treating a moment as
     * renderable); filtering it out of the candidate pool here is the stronger fix - the card either
     * shows a real memory or doesn't appear at all, never a broken-image placeholder where a photo was
     * expected. The isFile check (not just the DB flag) additionally covers the rare case of the flag
     * being true but the file having since vanished some other way. */
    // User-requested: "okay Random is good.. BUT it should show anniversary photo if available if
    // not then only random... If we can show all the photos of that anniversary... last 3-5 photos
    // from same day in last year or last to last year.. as slide show.. that would be the best." A
    // photo taken on this exact month+day in a PAST year is a genuine "on this day" memory, not just
    // any random photo - so the whole anniversary pool (every qualifying photo, across every matching
    // year) wins over the plain-random pool whenever at least one exists, capped at
    // MAX_MEMORY_SLIDESHOW_PHOTOS so a couple with many years of photos still gets a short slideshow,
    // not an unbounded one. Falls back to a single random photo only when there's no anniversary match
    // at all.
    suspend fun pickRandomMomentIfNeeded(all: List<Moment>) {
        if (memoryMoments.value.isNotEmpty()) return
        // MINOR fix: File.isFile is blocking disk I/O, and this used to run directly on whatever
        // dispatcher the caller's LaunchedEffect is on (Main) - moved off Main here so a large moment
        // list (or repeated re-runs while nothing is downloaded yet, e.g. right after a restore) can't
        // ever cause a stutter.
        val withPhoto = withContext(Dispatchers.IO) { all.filter { it.photoDownloaded && File(it.photoUri).isFile } }
        if (withPhoto.isEmpty()) return
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val anniversaryPool = withPhoto.filter { moment ->
            val takenDate = Instant.ofEpochMilli(moment.takenAt).atZone(zone).toLocalDate()
            takenDate.monthValue == today.monthValue && takenDate.dayOfMonth == today.dayOfMonth && takenDate.year != today.year
        }.sortedByDescending { it.takenAt }
        if (anniversaryPool.isNotEmpty()) {
            memoryMoments.value = anniversaryPool.take(MAX_MEMORY_SLIDESHOW_PHOTOS)
            memoryMomentsIsAnniversary.value = true
        } else {
            memoryMoments.value = listOf(withPhoto.random())
            memoryMomentsIsAnniversary.value = false
        }
    }

    companion object {
        private const val MAX_MEMORY_SLIDESHOW_PHOTOS = 5
    }
}

@Composable
fun HomeScreen(
    onNavigateSettings: () -> Unit,
    onNavigateCalendar: () -> Unit,
    onNavigateDateIdeas: () -> Unit,
    onNavigateMoments: () -> Unit,
    onNavigateStats: () -> Unit,
    onNavigateCapsules: () -> Unit,
    onNavigateBadges: () -> Unit,
    onNavigateCamera: () -> Unit,
    onNavigateMilestones: () -> Unit = {},
    // UX-FIX-PLAN.md Phase 3 item 20: MemoryThrowbackCard -> the Moment's own day on Moments (via
    // Screen.Moments' new jumpToEpochDay arg), OnThisDayCard -> that same calendar date on Calendar.
    onOpenCalendar: (jumpToEpochDay: Long) -> Unit = {},
    onOpenMoments: (jumpToEpochDay: Long) -> Unit = {}
) {
    val vm: HomeViewModel = viewModel(factory = SimpleViewModelFactory { HomeViewModel() })
    val pairingInfo by vm.pairingInfo.collectAsState()
    val sessions by vm.sessions.collectAsState()
    // Session-split fix: UsStatusCard no longer reads the open session's startedAt directly (see its own
    // call site comment below for why - after a grace-window resume, startedAt is only the current
    // segment's start, not the true streak start) - proximityState.continuousTogetherSince (the service's
    // rebased display accumulator) is what it reads instead, so `vm.openSession` itself is no longer
    // subscribed to here.
    val proximityState by vm.proximityState.collectAsState()
    val moments by vm.moments.collectAsState()
    val memoryMoments by vm.memoryMoments.collectAsState()
    val memoryMomentsIsAnniversary by vm.memoryMomentsIsAnniversary.collectAsState()
    val milestones by vm.milestones.collectAsState()
    val quickLinksOrder by vm.quickLinksOrder.collectAsState()
    val settings by vm.settings.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Without this permission the proximity service can't scan/advertise at all and just sits idle
    // forever with no explanation (see BlePermissions.hasBlePermissions). Re-check whenever the
    // screen resumes (e.g. coming back from the system Settings permission page) so the banner
    // clears itself once the user actually grants it.
    var hasBlePermission by remember { mutableStateOf(BlePermissions.hasBlePermissions(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasBlePermission = BlePermissions.hasBlePermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val blePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        hasBlePermission = BlePermissions.hasBlePermissions(context)
        if (hasBlePermission) {
            ContextCompat.startForegroundService(context, Intent(context, ProximityForegroundService::class.java))
        }
    }

    // Notifications are a SEPARATE runtime prompt from BLE on API 33+ (RequestMultiplePermissions above
    // bundles both into one request, but a user can still grant BLE and deny just this one - or the OS
    // pre-splits them into two dialogs on some devices). Without this independent check, that specific
    // outcome was previously invisible: hasBlePermission alone would be true, no banner would ever show
    // again, and the user would never learn why the ongoing "Together for Xh" notification (and the
    // photo-reminder/reunion/milestone notifications - see Notifications.kt's hasNotificationPermission
    // gate on every post() call) silently never appears. Re-checked on resume for the same reason as the
    // BLE banner above (coming back from the system Settings permission page).
    var hasNotificationPermission by remember { mutableStateOf(BlePermissions.hasNotificationPermission(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotificationPermission = BlePermissions.hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasNotificationPermission = BlePermissions.hasNotificationPermission(context)
    }

    // On API < 31, having ACCESS_FINE_LOCATION granted isn't enough on its own for BLE scanning to
    // actually deliver results - the system Location Services toggle also has to be on. Without this
    // check, that combination silently makes startScan() "succeed" but never see anything, with no
    // explanation anywhere in the app (indistinguishable from a hardware fault). Re-checked on resume
    // for the same reason as the permission banner above (coming back from the system Settings page).
    var needsLocationServices by remember { mutableStateOf(BlePermissions.needsLocationServicesEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                needsLocationServices = BlePermissions.needsLocationServicesEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Item 22/23 (UX-FIX-PLAN.md Phase 3): battery-exemption and DND-access state, checked live here for
    // the FIRST time on Home (previously only the one-shot BatteryOptimizationGate dialog and the
    // persistent OS notification tracked battery state, and DND access wasn't tracked anywhere at all) -
    // both feed the notification-inbox panel below via InboxSignals. Same ON_RESUME re-check convention as
    // the 3 permission banners above (coming back from the respective system Settings screen).
    var batteryExempt by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = BatteryOptimization.isIgnoring(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var hasDndAccess by remember { mutableStateOf(DndAccess.isGranted(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasDndAccess = DndAccess.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(moments) { vm.pickRandomMomentIfNeeded(moments) }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    // Item 22 (UX-FIX-PLAN.md Phase 3): whether the notification-bell panel is currently open.
    var showInboxPanel by remember { mutableStateOf(false) }

    var showReunion by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val persisted = ServiceLocator.proximityStateStore.current()
        if (persisted.pendingReunionCelebration) {
            showReunion = true
            ServiceLocator.proximityStateStore.update { it.copy(pendingReunionCelebration = false) }
        }
    }
    LaunchedEffect(Unit) {
        AppEvents.reunionEvents.collect {
            showReunion = true
            ServiceLocator.proximityStateStore.update { it.copy(pendingReunionCelebration = false) }
        }
    }

    // MINOR fix (ultimate-app-review, user-requested follow-up): completing a pairing action - either
    // "Reconnect to X" OR finishing Create/Join Pair (PairingViewModel.finishPairing()) - can leave
    // this device holding a stale device-ID pin with no feedback: the user just lands in the app and
    // only finds out later, if at all, from a subtle Settings-screen subtitle change. Live-caught via
    // BOTH routes - "Reconnect" during this fix's own testing, and separately Unpair -> Join Pair ->
    // re-entering the partner's still-displayed code during the user's own manual testing (same
    // underlying one-sided-stale-pin risk, different button). See AppEvents.justPaired's own doc for
    // why this check has to live HERE (Home), not on the pairing screen where the action happened.
    var reconnectVerifying by remember { mutableStateOf(false) }
    var reconnectMismatch by remember { mutableStateOf(false) }
    // UX (user-requested follow-up): the 30s check below used to just go silent when neither a genuine
    // success nor a locally-detected mismatch showed up in time - coded identically to "partner's
    // simply not in range right now" even though this specific case (rejoining with a code someone
    // else is already pinned against) can ALSO produce exactly this same silent-timeout signature from
    // the joining device's own point of view (the rejecting side stops the exchange before ever
    // sending anything back, so the joiner never gets a chance to observe its own mismatch flag - see
    // GattSyncManager's onCharacteristicWriteRequest rejection branch). Silently closing the dialog in
    // that case reads as "you're all set" when nothing has actually been confirmed - this banner is the
    // honest alternative: say plainly that it's still unconfirmed instead of saying nothing.
    var pairingUnconfirmed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        AppEvents.justPaired.collect { justPaired ->
            if (!justPaired) return@collect
            AppEvents.consumeJustPaired()
            pairingUnconfirmed = false
            reconnectVerifying = true
            // Same "retry every second, cancel on the first real result" shape as OurListsScreen's
            // manual sync button, but a much longer window (live-measured, not guessed): unlike an
            // ordinary "Sync now" tap during an ALREADY-established together session (what the 8s
            // window elsewhere is tuned for), this fires right after the proximity service has just
            // cold-restarted (unpair -> reconnect) and has to rediscover the partner's phone over BLE
            // from scratch - live-measured on this project's own test setup at up to ~2 minutes from
            // service restart to the two phones showing as "together" at all, let alone completing a
            // sync. 30s is a compromise: long enough to have a real chance of catching a cold discovery
            // without feeling hung, short of the multi-minute worst case - a genuinely slow
            // reconnect still correctly falls through to silence (see the null-check below) rather
            // than a false "couldn't reconnect".
            // BUG fix (live-caught with debug logging during manual verification): plain .first() took
            // whatever syncCompleted(false) arrived FIRST as "the" result - but startGattSyncIfNeeded
            // has several early-exit paths (a tie-break byte collision, the service not fully up yet
            // right after a cold restart) that emit syncCompleted(false) almost instantly, structurally
            // unrelated to whether the partner's identity actually matches. That spurious early false
            // was resolving this check in single-digit milliseconds, long before a real BLE connection
            // could possibly complete, and (since lastSyncFailedDueToPartnerMismatch was correctly still
            // false for it) never triggered a false dialog - but it also meant the 30s retry window was
            // being thrown away on the very first structural hiccup instead of ever getting a chance to
            // observe a REAL attempt. first { predicate } instead of first() skips exactly those - it
            // only resolves on a genuine success, or a failure specifically flagged as a partner
            // mismatch, and keeps waiting (letting the retry loop keep nudging) through anything else.
            val success = withTimeoutOrNull(30_000) {
                coroutineScope {
                    val retryJob = launch {
                        while (true) {
                            AppEvents.requestManualSync()
                            delay(1_000)
                        }
                    }
                    val result = AppEvents.syncCompleted.first { s -> s || AppEvents.lastSyncFailedDueToPartnerMismatch.value }
                    retryJob.cancel()
                    result
                }
            }
            reconnectVerifying = false
            if (success == false && AppEvents.lastSyncFailedDueToPartnerMismatch.value) {
                reconnectMismatch = true
            } else if (success == null) {
                // success == null means the window elapsed with no qualifying result at all - genuinely
                // ambiguous (could be "couple not together right now to test it", could be a rejection
                // this device never got direct feedback on, see this var's own doc above). No longer
                // silent: shows the honest "not confirmed yet" banner below instead of pretending
                // everything's settled.
                pairingUnconfirmed = true
            }
        }
    }

    // Keeps watching in the background, even after the 30s active-retry window above has ended, so the
    // banner doesn't just sit there stale once the real answer eventually becomes known (the couple
    // comes back in range, or a later attempt gets definitively rejected) - passive only (no retry loop
    // of its own), since the app's normal periodic/manual sync paths already keep trying on their own
    // cadence regardless of whether this banner is showing.
    LaunchedEffect(pairingUnconfirmed) {
        if (!pairingUnconfirmed) return@LaunchedEffect
        val result = AppEvents.syncCompleted.first { s -> s || AppEvents.lastSyncFailedDueToPartnerMismatch.value }
        pairingUnconfirmed = false
        if (!result && AppEvents.lastSyncFailedDueToPartnerMismatch.value) {
            reconnectMismatch = true
        }
    }

    // lastSeenAt clamps an open session's live duration to the last confirmed sighting + absence
    // timeout, matching Home's own effectivelyTogether staleness check below - see
    // StatsCalculator.effectiveOpenSessionEnd's doc for why (a stale/orphaned open session must
    // never silently "grow" forever just because something read it).
    val stats = remember(sessions, now, proximityState.lastSeenAt, proximityState.reunionCount) {
        StatsCalculator.compute(sessions, now, lastSeenAt = proximityState.lastSeenAt, reunionCount = proximityState.reunionCount)
    }

    // Same "on this exact calendar date, in a past year" lookup used by the Calendar screen's per-day
    // data - a NEW time-based callback, distinct from MemoryThrowbackCard's photo-based one below.
    // Keyed the same way `stats` above already is (sessions/now/lastSeenAt), so it only recomputes on
    // this screen's existing 30s ticker cadence rather than every recomposition.
    val onThisDayInfo: OnThisDayInfo? = remember(sessions, now, proximityState.lastSeenAt) {
        StatsCalculator.onThisDayPreviousYear(sessions, now, lastSeenAt = proximityState.lastSeenAt)
    }

    // Defensive UI-level staleness check, independent of whether the foreground service is even
    // running right now: proximityState.isTogether only flips back to false when the service's own
    // ticker calls checkAbsence() (see ProximityForegroundService.tick/handleBecameApart) - if the
    // service isn't restarting (e.g. BLE permission got revoked so it never relaunches to run its
    // self-heal check), a stale "together" flag can otherwise sit there and show "Together" forever
    // even though lastSeenAt is long past the absence timeout. Recompute the same staleness rule
    // locally here so Home always reflects reality regardless of service state.
    //
    // Deliberately compared using lastSeenElapsedRealtime (SystemClock.elapsedRealtime(), boot-time
    // monotonic) rather than the wall-clock lastSeenAt/now pair used elsewhere on this screen for
    // display text: a wall-clock comparison here would never catch up (and this card would show
    // "Together" forever) if the device's system clock got changed backwards while apart. This mirrors
    // ProximityStateMachine.checkAbsence()'s own fix - see its doc for the full reasoning.
    val effectivelyTogether = remember(proximityState.isTogether, proximityState.lastSeenElapsedRealtime, now) {
        val nowElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        // elapsedRealtime() resets to near-zero on every reboot. If the persisted
        // lastSeenElapsedRealtime is from a PREVIOUS boot (i.e. larger than the current elapsed-realtime
        // clock), nowElapsedRealtime - lastSeenElapsedRealtime goes NEGATIVE - which is < the timeout, so
        // without this check the staleness rule below would wrongly read as "fresh" and could show a
        // false "Together" forever after a reboot (combined with a service that can't restart to
        // self-heal, e.g. BLE permission revoked). A negative delta is an unambiguous signal that a
        // reboot happened since the value was persisted - treat it as stale/apart.
        val elapsedSinceLastSeen = nowElapsedRealtime - proximityState.lastSeenElapsedRealtime
        proximityState.isTogether && !(
            proximityState.lastSeenElapsedRealtime != 0L &&
                (elapsedSinceLastSeen < 0L || elapsedSinceLastSeen >= ProximityStateMachine.DEFAULT_ABSENCE_TIMEOUT_MILLIS)
            )
    }

    // Item 14 (update-nag reach fix): extracted to a val so both the "Update ready" banner below AND
    // the notification-inbox panel/badge-dot (item 22) read the exact same computed result, instead of
    // each calling UpdateChecker.isPendingUpdateActionable independently.
    val pendingUpdateActionable = UpdateChecker.isPendingUpdateActionable(settings.pendingUpdateVersionCode, BuildConfig.VERSION_CODE)

    // Item 21 (UX-FIX-PLAN.md Phase 3): the single category closest to unlocking its next badge, for the
    // compact card below - reuses the EXACT SAME BadgeCatalog.progressRows/closestToNextBadge logic the
    // Badges screen's own 4-bar section is now built on (see BadgesScreen.kt's BadgeProgressBarsSection),
    // so the two surfaces can never silently drift apart. Null (nothing to show) only once every category
    // is maxed out.
    val nextBadgeRow: BadgeProgressRow? = remember(stats) {
        BadgeCatalog.closestToNextBadge(BadgeCatalog.progressRows(stats))
    }

    // Item 22 (UX-FIX-PLAN.md Phase 3): one flat snapshot of every "is X currently unresolved" signal
    // Home already independently computes for its own banners above - see NotificationInbox's own doc for
    // why this is the ONE place both the bell panel/badge-dot AND (via
    // NotificationInbox.blockingPermissionKind, reused directly by BatteryOptimizationGate) the
    // dialog-stacking fix read from, rather than each growing its own parallel copy of "is X true".
    val inboxSignals = remember(
        hasBlePermission, needsLocationServices, hasNotificationPermission, batteryExempt, hasDndAccess,
        pairingUnconfirmed, pairingInfo.pendingResyncRequests, pendingUpdateActionable, settings.pendingUpdateVersionName
    ) {
        InboxSignals(
            hasBlePermission = hasBlePermission,
            needsLocationServices = needsLocationServices,
            hasNotificationPermission = hasNotificationPermission,
            batteryExempt = batteryExempt,
            hasDndAccess = hasDndAccess,
            pairingUnconfirmed = pairingUnconfirmed,
            pendingResyncCount = pairingInfo.pendingResyncRequests.size,
            pendingUpdateActionable = pendingUpdateActionable,
            pendingUpdateVersionName = settings.pendingUpdateVersionName
        )
    }
    val inboxItems = remember(inboxSignals) { NotificationInbox.buildItems(inboxSignals) }

    // Item 22 (UX-FIX-PLAN.md Phase 3): each of these does exactly what tapping its own Home banner does -
    // pulled out as named lambdas (rather than left inline on each banner's Button) so the notification-
    // inbox panel below can wire the SAME action to its own tap target, instead of a second hand-copied
    // "what does resolving this item actually do" implementation that could drift from the banner's.
    val grantBlePermissions: () -> Unit = {
        val toRequest = (BlePermissions.required().toList() +
            listOfNotNull(BlePermissions.notificationPermission()))
            .filter { ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) blePermissionLauncher.launch(toRequest.toTypedArray())
    }
    val openLocationSettings: () -> Unit = {
        context.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }
    val grantNotificationPermission: () -> Unit = {
        val perm = BlePermissions.notificationPermission()
        if (perm != null) notificationPermissionLauncher.launch(perm)
    }
    val openBatteryOptimizationSettings: () -> Unit = {
        try {
            context.startActivity(BatteryOptimization.requestIgnoreIntent(context))
        } catch (e: Exception) {
            // See BatteryOptimizationGate's own identical try/catch for why - a standard AOSP intent that
            // some heavily-modified OEM builds or emulator images can nonetheless lack a handler for.
        }
    }
    val openDndAccessSettings: () -> Unit = {
        try {
            context.startActivity(DndAccess.requestIntent())
        } catch (e: Exception) {
            // Same defensive reasoning as openBatteryOptimizationSettings above.
        }
    }
    val installOrOpenSettingsForUpdate: () -> Unit = {
        // Mirrors the "Update ready" banner's own onInstallClick exactly - see that card's call site
        // below for the full reasoning on why a missing cached APK falls back to Settings instead of
        // silently doing nothing.
        val apkFile = settings.pendingUpdateApkPath?.let { File(it) }
        if (apkFile != null && apkFile.isFile) {
            context.startActivity(
                Intent(context, UpdateInstallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(UpdateInstallActivity.EXTRA_APK_PATH, apkFile.absolutePath)
                }
            )
        } else {
            onNavigateSettings()
        }
    }

    // BUG fix: ReunionOverlay used to be a sibling of the LazyColumn INSIDE Scaffold's own trailing
    // content lambda - which only fills the area below topBar, since topBar is a separate Scaffold slot
    // that always composes ON TOP of the content slot regardless of what that slot renders. That let the
    // top app bar (with its Settings icon) visually bleed through and intercept taps meant for the
    // "full-screen" celebration overlay - the exact same bug class already fixed once in
    // MomentsScreen.kt's full-screen photo viewer. Wrapping the whole Scaffold in an outer Box and
    // moving ReunionOverlay to be a SIBLING of Scaffold (not nested inside its content lambda) makes it
    // genuinely cover the top bar too.
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Twogether", fontWeight = FontWeight.Bold) },
                actions = {
                    // Item 22 (UX-FIX-PLAN.md Phase 3): the bell aggregates everything the app currently
                    // surfaces piecemeal across banners/dialogs/one-shot notifications (see inboxItems'
                    // own doc above) into one panel - a small dot persists on the icon for as long as
                    // inboxItems is non-empty, and clears the moment it would be empty (fully reactive,
                    // no separate "seen/unseen" latch to keep in sync).
                    IconButton(onClick = { showInboxPanel = true }) {
                        if (inboxItems.isNotEmpty()) {
                            BadgedBox(badge = { Badge() }) {
                                Icon(Icons.Filled.Notifications, contentDescription = "Notifications (${inboxItems.size} unresolved)")
                            }
                        } else {
                            Icon(Icons.Filled.Notifications, contentDescription = "Notifications")
                        }
                    }
                    IconButton(onClick = onNavigateSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!hasBlePermission) {
                item { BlePermissionWarningCard(onGrantClick = grantBlePermissions) }
            } else if (needsLocationServices) {
                // Only shown once BLE permission itself is granted - a redundant "also turn on Location"
                // banner on top of the permission banner would be confusing since granting BLE permission
                // is the more fundamental blocker.
                item { LocationServicesWarningCard(onOpenSettingsClick = openLocationSettings) }
            } else if (!hasNotificationPermission) {
                // Same "only once the more fundamental blocker is clear" reasoning as the Location
                // Services banner above - shown only once BLE is granted and Location Services (if
                // relevant) is on, since a missing notification permission is real but secondary: the
                // service still tracks together-time correctly without it, it just can't SHOW you that
                // it's doing so (no ongoing notification, no photo-reminder/reunion/milestone alerts).
                item { NotificationPermissionWarningCard(onGrantClick = grantNotificationPermission) }
            }
            if (pairingUnconfirmed) {
                item {
                    PairingUnconfirmedWarningCard(
                        partnerName = pairingInfo.partnerName,
                        onDismiss = { pairingUnconfirmed = false }
                    )
                }
            }
            // Item 14 (update-nag reach fix): reads the SAME durable pendingUpdateVersionCode flag
            // Settings' own "Update ready" card already reads (see SettingsScreen.kt) - a real user
            // shipped v2.7 (a genuine data-loss bug fix) and went days without ever seeing it, because
            // nothing on Home hinted an update was ready and the flag was previously Settings-only. This
            // banner has NO permission dependency at all (unlike the three banners above it) - it works
            // correctly even when POST_NOTIFICATIONS is denied entirely, which is the whole point; the
            // durable flag is written the moment UpdateChecker finishes downloading, independent of
            // whether the OS notification itself managed to post. Placed after the permission banners
            // (a missing BLE/notification permission is the more fundamental blocker to resolve first)
            // but before the status card, matching this screen's existing "important banners float to
            // the top" ordering.
            if (pendingUpdateActionable) {
                item {
                    UpdateAvailableWarningCard(
                        versionName = settings.pendingUpdateVersionName ?: "update",
                        onInstallClick = installOrOpenSettingsForUpdate
                    )
                }
            }
            item {
                UsStatusCard(
                    isTogether = effectivelyTogether,
                    // Session-split fix: reads proximityState.continuousTogetherSince (the service's
                    // display-purpose accumulator - see ProximityForegroundService.
                    // resumedContinuousTogetherSince) instead of openSession.startedAt. After a
                    // grace-window resume, the open session's startedAt is only the CURRENT segment's
                    // start (the reconnect instant) - it no longer represents when this continuous
                    // together-streak truly began, since a resume now closes the old row and opens a
                    // genuinely new one rather than reusing it (see that file's class doc). continuousTogetherSince
                    // is rebased on every resume to keep `now - continuousTogetherSince` correct: frozen
                    // through an apart gap, then resuming from that exact frozen value - never jumping to
                    // include the gap the way reading openSession.startedAt directly would.
                    continuousTogetherSinceMillis = proximityState.continuousTogetherSince,
                    now = now,
                    partnerName = pairingInfo.partnerName,
                    partnerEmoji = pairingInfo.partnerEmoji
                )
            }
            item {
                // BUG fix (user-reported formatting pass): height(IntrinsicSize.Max) + fillMaxHeight() on
                // each card - same row-level equalization technique as StatCard (see its own doc in
                // Components.kt) - so these 3 cards stay the same height as each other if any one label
                // ever wraps, without every card unconditionally reserving space it doesn't need.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                    Card(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        // BUG fix (user-reported): same root cause as BadgesScreen's own take-3 fix (see
                        // its doc in BadgesScreen.kt) - a Column with no fillMaxHeight/verticalArrangement
                        // just wraps its own content and sits top-anchored inside whatever taller height a
                        // row-mate's wrapped label ("🗓️ Days together" -> 2 lines) forces onto this equal-
                        // height Card, leaving dead space below this card's own (1-line-label) content
                        // instead of centering it.
                        Column(Modifier.padding(16.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                            Text("This week", style = MaterialTheme.typography.bodySmall)
                            // MINOR fix (final Opus re-audit): pinned to Locale.US, matching the
                            // ui/stats/ convention (see GapsDetailScreen's own doc).
                            Text("${"%.1f".format(Locale.US, stats.totalHoursThisWeek)}h", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    Card(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Column(Modifier.padding(16.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                            Text("🔥 Daily streak", style = MaterialTheme.typography.bodySmall)
                            // BUG fix: a 1-day streak (very common right after the couple's first day
                            // together) read as "1 days".
                            Text("${stats.currentDailyStreak} day" + (if (stats.currentDailyStreak == 1) "" else "s"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    Card(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(Modifier.padding(16.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                            Text("🗓️ Days together", style = MaterialTheme.typography.bodySmall)
                            Text("${stats.totalDaysTogether}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            // Item 21 (UX-FIX-PLAN.md Phase 3): the single category closest to its next badge - see
            // nextBadgeRow's own doc above. Placed right after the "quick facts" stat tiles and before the
            // throwback/on-this-day cards - another quick, forward-looking fact about the couple's
            // progress, grouped with its stat-tile siblings rather than buried below the more nostalgic
            // cards.
            if (nextBadgeRow != null) {
                item {
                    NextBadgeProgressCard(row = nextBadgeRow, onClick = onNavigateBadges)
                }
            }
            if (memoryMoments.isNotEmpty()) {
                item {
                    // User-requested slideshow: MemoryThrowbackCard now owns its own pager over
                    // [memoryMoments] (1 element for a plain random pick, up to 5 for an anniversary
                    // match) - see HomeViewModel.pickRandomMomentIfNeeded's own doc.
                    MemoryThrowbackCard(
                        moments = memoryMoments,
                        isAnniversary = memoryMomentsIsAnniversary,
                        milestones = milestones,
                        now = now,
                        onOpenMoments = onOpenMoments
                    )
                }
            }
            if (onThisDayInfo != null) {
                item {
                    // "On this day in {info.year}" always shares today's real month/day (see
                    // StatsCalculator.onThisDayPreviousYear's own doc - it only ever matches entries whose
                    // month/day equal TODAY's), so LocalDate.of(info.year, today.month, today.day) can
                    // never throw even for a Feb 29 "today": that combination could only exist in the
                    // qualifying-days map above in the first place if info.year genuinely supports it too.
                    val onThisDayEpochDay = remember(onThisDayInfo, now) {
                        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
                        LocalDate.of(onThisDayInfo.year, today.monthValue, today.dayOfMonth).toEpochDay()
                    }
                    OnThisDayCard(info = onThisDayInfo, onClick = { onOpenCalendar(onThisDayEpochDay) })
                }
            }
            item {
                SectionHeader("Quick links")
            }
            item {
                // Was a LazyRow (horizontally scrolling) with no visual hint that it scrolled - once
                // there were more than 3 chips (Time Capsules/Badges/Milestones added later), those last
                // ones became invisible unless you happened to swipe left with no affordance telling you
                // to. A wrapping, even-width grid instead: every quick link is always visible, and each
                // chip takes an equal half-width share of its row (via weight(1f)) rather than
                // auto-sizing to its own label, so short ("Stats") and long ("Time Capsules") labels
                // still line up into a tidy, evenly-sized grid instead of a ragged one.
                //
                // Each chip's emoji/label/onClick is looked up by its stable QuickLinkIds id (not by
                // position) - the id -> action mapping below never changes, only the *order* the ids are
                // rendered in does (per-device drag-and-drop reorder, see quickLinksOrder/
                // ReorderableQuickLinksGrid) - so a chip dragged to a new slot always keeps navigating to
                // wherever its own label/icon says it goes, never a stale position-based destination.
                val quickLinkDefs = remember(
                    onNavigateCalendar, onNavigateDateIdeas, onNavigateMoments, onNavigateStats,
                    onNavigateCapsules, onNavigateBadges, onNavigateMilestones, onNavigateCamera
                ) {
                    listOf(
                        QuickLinkDef(QuickLinkIds.CALENDAR, "📅", "Calendar", onNavigateCalendar),
                        QuickLinkDef(QuickLinkIds.DATE_IDEAS, "💌", "Our Lists", onNavigateDateIdeas),
                        QuickLinkDef(QuickLinkIds.MOMENTS, "📸", "Moments", onNavigateMoments),
                        QuickLinkDef(QuickLinkIds.STATS, "📊", "Stats", onNavigateStats),
                        QuickLinkDef(QuickLinkIds.TIME_CAPSULES, "⏳", "Time Capsules", onNavigateCapsules),
                        QuickLinkDef(QuickLinkIds.BADGES, "🏅", "Badges", onNavigateBadges),
                        QuickLinkDef(QuickLinkIds.MILESTONES, "🎉", "Milestones", onNavigateMilestones),
                        QuickLinkDef(QuickLinkIds.CAMERA, "📷", "Take a photo", onNavigateCamera)
                    ).associateBy { it.id }
                }
                ReorderableQuickLinksGrid(
                    order = quickLinksOrder,
                    definitions = quickLinkDefs,
                    onReorder = { vm.setQuickLinksOrder(it) }
                )
            }
        }
    }

        if (showReunion) {
            ReunionOverlay(onDismiss = { showReunion = false })
        }
    }

    if (reconnectVerifying) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Checking connection…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Making sure this pairing is set up correctly.")
                }
            },
            confirmButton = {}
        )
    }

    if (reconnectMismatch) {
        AlertDialog(
            onDismissRequest = { reconnectMismatch = false },
            title = { Text("Pairing didn't work") },
            text = {
                Text(
                    "This doesn't look like ${pairingInfo?.partnerName?.ifBlank { "your partner" } ?: "your partner"}'s " +
                        "phone anymore — it may have been reset or reinstalled. Ask them for a fresh pairing code, " +
                        "then go to Settings to unpair and create a new one."
                )
            },
            confirmButton = { TextButton(onClick = { reconnectMismatch = false }) { Text("Okay") } }
        )
    }

    // SECURITY (user-designed follow-up): pairingInfo.pendingResyncRequests is the reactive source of
    // truth (a StateFlow read straight from DataStore, see HomeViewModel.pairingInfo) - no separate local
    // "is this dialog open" state needed, since approving/dismissing the last entry naturally shrinks the
    // list back to empty and this dialog just stops rendering on its own. Deliberately a single dialog
    // listing every pending entry (not one dialog per request) so a user who gets multiple simultaneous
    // requests can compare them side by side, per the user's own "so I can filter my partner by name"
    // request that shaped this list-based design in the first place.
    if (pairingInfo.pendingResyncRequests.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Is this your partner?") },
            text = {
                Column {
                    Text(
                        "A device tried to use your pairing code but doesn't match who ${
                            pairingInfo.partnerName.ifBlank { "your partner" }
                        } was last known as. Only approve one of these if you're sure it's really them " +
                            "(e.g. they reinstalled the app or reset their phone)."
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    pairingInfo.pendingResyncRequests.forEach { req ->
                        // Now the shared RelativeTime.relativeAgo utility (Phase 1 item 2) - this was the
                        // exact hand-written "just now/Xm/Xh/Xd ago" pattern that utility was modeled on.
                        val ageText = RelativeTime.relativeAgo((now - req.requestedAt).coerceAtLeast(0L))
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    req.bluetoothName?.ifBlank { null } ?: "Unknown Bluetooth device",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Calls their partner: ${req.theirPartnerName?.ifBlank { null } ?: "unknown"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text("Requested $ageText", style = MaterialTheme.typography.bodySmall)
                                Row(modifier = Modifier.padding(top = 8.dp)) {
                                    TextButton(onClick = { vm.dismissPendingResync(req.deviceId) }) { Text("Not my partner") }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TextButton(onClick = { vm.approvePendingResync(req.deviceId) }) { Text("Yes, resync") }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // Item 22 (UX-FIX-PLAN.md Phase 3): the notification-bell panel. A bottom sheet (not another Screen
    // reached via NavGraph) so it can directly share every launcher/callback Home already built for its
    // own banners above (grantBlePermissions, notificationPermissionLauncher, etc) instead of duplicating
    // that whole permission-request wiring a second time in a standalone destination far away from where
    // it's defined.
    if (showInboxPanel) {
        NotificationInboxSheet(
            items = inboxItems,
            onDismiss = { showInboxPanel = false },
            onItemClick = { kind ->
                when (kind) {
                    InboxItemKind.BLE_PERMISSION -> grantBlePermissions()
                    InboxItemKind.LOCATION_SERVICES -> openLocationSettings()
                    InboxItemKind.NOTIFICATION_PERMISSION -> grantNotificationPermission()
                    InboxItemKind.BATTERY_OPTIMIZATION -> openBatteryOptimizationSettings()
                    InboxItemKind.DND_ACCESS -> openDndAccessSettings()
                    // These 3 have no separate action of their own to trigger from here - dismissing the
                    // panel is enough to reveal whichever of Home's own always-shown surfaces already
                    // handles them (the pairingUnconfirmed banner right above the status card, or the
                    // blocking "Is this your partner?" dialog above), or for the update card, tapping it
                    // installs directly.
                    InboxItemKind.PAIRING_UNCONFIRMED -> {}
                    InboxItemKind.PENDING_RESYNC -> {}
                    InboxItemKind.UPDATE_AVAILABLE -> installOrOpenSettingsForUpdate()
                }
                showInboxPanel = false
            }
        )
    }
}

/**
 * Renders [order] as the existing even 2-column Quick Links grid, with drag-and-drop reordering: a
 * long-press on a chip picks it up (scale-up + shadow lift, follows the finger 1:1), other chips
 * smoothly slide out of the way as the dragged one crosses into their slot, and releasing commits the
 * new order via [onReorder] (persisted immediately by the caller). A plain (non-long-press) tap is left
 * completely alone - [detectDragGesturesAfterLongPress] never consumes a short tap's down/up events, so
 * [QuickLinkChip]'s own `Card(onClick = ...)` still sees and handles it normally.
 *
 * Deliberately NOT a `LazyVerticalGrid`: nesting a lazy grid inside this screen's outer `LazyColumn`
 * item would need an explicit height + `userScrollEnabled = false` for only 8 fixed items, adding
 * complexity for no benefit here. Instead this keeps the original Column-of-Rows structure (matching
 * the recent even-grid fix) and implements swap/animate directly against the flat [order] list, using
 * each chip's own measured position (via `onGloballyPositioned`) rather than hand-computed grid math -
 * that keeps it correct regardless of font scaling / accessibility text size affecting chip height.
 *
 * The structural row/column order is frozen the instant a drag starts ([dragStartOrder]) and never
 * changes again until the drag ends - only each chip's *visual* translation (graphicsLayer) moves during
 * the drag. This avoids the classic "item jumps" bug where reflowing the actual layout mid-drag fights
 * with the manually-tracked finger offset.
 */
@Composable
private fun ReorderableQuickLinksGrid(
    order: List<String>,
    definitions: Map<String, QuickLinkDef>,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var draggingId by remember { mutableStateOf<String?>(null) }

    // What's actually rendered while idle. Reconciled from [order] (the ViewModel/DataStore value)
    // whenever it changes AND no drag is active - see the LaunchedEffect below. Updated instantly (not
    // waiting for the DataStore round-trip) the moment a drag ends, so the grid never flickers back to
    // a stale arrangement while that write is still in flight. Reads `draggingId` fresh each time this
    // effect (re)starts (keyed on `order`), so it never clobbers an in-progress drag's own localOrder
    // mutations with a late/duplicate Flow emission.
    var localOrder by remember { mutableStateOf(order) }
    LaunchedEffect(order) {
        if (draggingId == null) localOrder = order
    }

    // Frozen the instant a drag starts: the structural order used to lay out the grid for the rest of
    // that drag. Null while idle.
    var dragStartOrder by remember { mutableStateOf<List<String>?>(null) }
    // Which id currently occupies each structural slot if the drag were dropped right now - starts
    // equal to dragStartOrder and gets a "move" applied every time the dragged chip's center crosses
    // into a different slot's bounds. Null while idle.
    var previewOrder by remember { mutableStateOf<List<String>?>(null) }
    // Raw cumulative finger delta (container-local px) since the long-press fired - drives the dragged
    // chip's own translation 1:1 with the finger and, combined with its frozen start slot, is how we
    // hit-test which slot it's currently over.
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // Frozen at drag start: index-aligned with dragStartOrder, each slot's measured (position, size) in
    // container-local px.
    var slotRectsByIndex by remember { mutableStateOf<List<Rect>>(emptyList()) }

    var containerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val liveRects = remember { mutableStateMapOf<String, Rect>() }

    val layoutOrder = dragStartOrder ?: localOrder

    Column(
        modifier = modifier.onGloballyPositioned { containerCoordinates = it },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        layoutOrder.chunked(2).forEach { rowIds ->
            val rowIsDragging = draggingId != null && rowIds.contains(draggingId)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().zIndex(if (rowIsDragging) 1f else 0f)
            ) {
                rowIds.forEach { id ->
                    val def = definitions[id]
                    if (def == null) {
                        Spacer(modifier = Modifier.weight(1f))
                        return@forEach
                    }
                    val isDragging = draggingId == id
                    val baseIndex = layoutOrder.indexOf(id)
                    val previewIndex = previewOrder?.indexOf(id)
                    val shuffleTarget = if (!isDragging && previewIndex != null && previewIndex >= 0 &&
                        previewIndex < slotRectsByIndex.size && baseIndex >= 0 && baseIndex < slotRectsByIndex.size
                    ) {
                        slotRectsByIndex[previewIndex].topLeft - slotRectsByIndex[baseIndex].topLeft
                    } else {
                        Offset.Zero
                    }
                    val animatedShuffle by animateOffsetAsState(shuffleTarget, label = "quicklink-shuffle-$id")
                    // graphicsLayer's block below runs at draw time, not composition, so it can't read
                    // composable-only APIs like MaterialTheme directly - capture the shape here instead.
                    val chipShape = MaterialTheme.shapes.large

                    QuickLinkChip(
                        emoji = def.emoji,
                        label = def.label,
                        onClick = def.onClick,
                        modifier = Modifier
                            .weight(1f)
                            .onGloballyPositioned { coords ->
                                val container = containerCoordinates ?: return@onGloballyPositioned
                                val topLeft = container.localPositionOf(coords, Offset.Zero)
                                liveRects[id] = Rect(topLeft, coords.size.toSize())
                            }
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                if (isDragging) {
                                    translationX = dragOffset.x
                                    translationY = dragOffset.y
                                    scaleX = 1.06f
                                    scaleY = 1.06f
                                    shadowElevation = 16f
                                    shape = chipShape
                                } else {
                                    translationX = animatedShuffle.x
                                    translationY = animatedShuffle.y
                                }
                            }
                            .pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = start@{
                                        val order0 = localOrder
                                        val rects = order0.map { linkId -> liveRects[linkId] }
                                        if (rects.any { it == null }) return@start
                                        dragStartOrder = order0
                                        previewOrder = order0
                                        slotRectsByIndex = rects.filterNotNull()
                                        dragOffset = Offset.Zero
                                        draggingId = id
                                    },
                                    onDrag = drag@{ change, amount ->
                                        if (draggingId != id) return@drag
                                        change.consume()
                                        dragOffset += amount
                                        val startOrder = dragStartOrder ?: return@drag
                                        val rects = slotRectsByIndex
                                        val startIndex = startOrder.indexOf(id)
                                        if (startIndex < 0 || startIndex >= rects.size) return@drag
                                        val startRect = rects[startIndex]
                                        val draggedCenter = Offset(
                                            startRect.left + startRect.width / 2f,
                                            startRect.top + startRect.height / 2f
                                        ) + dragOffset
                                        val targetIndex = rects.indexOfFirst { it.contains(draggedCenter) }
                                        if (targetIndex < 0) return@drag
                                        val current = previewOrder ?: startOrder
                                        if (current.indexOf(id) == targetIndex) return@drag
                                        val mutable = current.toMutableList()
                                        mutable.remove(id)
                                        mutable.add(targetIndex.coerceIn(0, mutable.size), id)
                                        previewOrder = mutable
                                    },
                                    onDragEnd = {
                                        val finalOrder = previewOrder ?: localOrder
                                        localOrder = finalOrder
                                        onReorder(finalOrder)
                                        draggingId = null
                                        dragStartOrder = null
                                        previewOrder = null
                                        dragOffset = Offset.Zero
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        dragStartOrder = null
                                        previewOrder = null
                                        dragOffset = Offset.Zero
                                    }
                                )
                            }
                    )
                }
                if (rowIds.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BlePermissionWarningCard(onGrantClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Bluetooth permission needed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                "Without it Twogether can't detect when you two are nearby each other.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            Button(onClick = onGrantClick) { Text("Grant access") }
        }
    }
}

@Composable
private fun NotificationPermissionWarningCard(onGrantClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Notification permission needed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                "You're still being tracked together, but without this you won't see the ongoing status, photo reminders, or reunion/milestone alerts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            Button(onClick = onGrantClick) { Text("Grant access") }
        }
    }
}

/** UX (user-requested follow-up): shown instead of silently doing nothing when the post-pairing check
 * (see pairingUnconfirmed's own doc above) can't tell within 30s whether this pairing is genuinely
 * working - deliberately a dismissible, non-blocking banner rather than another modal dialog: the user
 * can still use the rest of the app while this resolves itself in the background (see the
 * LaunchedEffect(pairingUnconfirmed) watcher), unlike the stronger "block until confirmed" alternative
 * that was considered and deliberately not built for this pass. */
@Composable
private fun PairingUnconfirmedWarningCard(partnerName: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Still confirming this pairing",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                "We couldn't confirm this pairing with ${partnerName.ifBlank { "your partner" }}'s phone yet. " +
                    "Make sure you're both nearby with Bluetooth on - we'll update this automatically once it's confirmed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun LocationServicesWarningCard(onOpenSettingsClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Location Services is off",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                "On this device, Bluetooth detection also needs Location Services turned on in system settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            Button(onClick = onOpenSettingsClick) { Text("Open Settings") }
        }
    }
}

/** Item 14 (update-nag reach fix): Home's own copy of Settings' "Update ready" card (see
 * SettingsScreen.kt's Updates section) - deliberately the SAME primaryContainer color Settings already
 * uses for this card (a ready update is good news, not a warning - unlike the errorContainer permission
 * banners above it on this same screen) rather than reusing errorContainer just for visual consistency
 * with its siblings. [onInstallClick] carries the exact same "launch UpdateInstallActivity, or fall back
 * to Settings if the cached APK has gone missing" logic as that card's own onClick - see this card's call
 * site for why the fallback goes to Settings rather than duplicating the stale-flag recovery flow here. */
@Composable
private fun UpdateAvailableWarningCard(versionName: String, onInstallClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Update $versionName ready",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                "A newer version of Twogether has already been downloaded and is ready to install.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            Button(onClick = onInstallClick) { Text("Install") }
        }
    }
}

@Composable
private fun UsStatusCard(isTogether: Boolean, continuousTogetherSinceMillis: Long, now: Long, partnerName: String, partnerEmoji: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (isTogether) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isTogether) {
                PulsingHeart()
                val elapsed = if (continuousTogetherSinceMillis > 0L) (now - continuousTogetherSinceMillis).coerceAtLeast(0L) else 0L
                Text(
                    text = "Together for ${RelativeTime.formatDuration(elapsed)}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text("with $partnerEmoji $partnerName", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(partnerEmoji, style = MaterialTheme.typography.displayMedium)
                Text(
                    text = "Apart right now",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text("Hoping to see $partnerName again soon 💭", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** UX-FIX-PLAN.md Phase 3 item 21: Home's compact "closest to your next badge" card - just [row] (the
 * single category [BadgeCatalog.closestToNextBadge] picked, see HomeScreen's own `nextBadgeRow` doc)
 * rendered through the EXACT SAME [BadgeProgressBarRow] composable the Badges screen's own 4-bar section
 * uses (see ui/badges/BadgesScreen.kt), just wrapped in its own tappable Card instead of BadgesScreen's
 * bigger 4-row Column - so this card can never visually drift from what BadgesScreen itself shows for
 * this exact category. Tapping through opens the full Badges screen. */
@Composable
private fun NextBadgeProgressCard(row: BadgeProgressRow, onClick: () -> Unit) {
    // BUG fix (user-reported "faded text" pattern, same root cause as SettingsSection's confirmed bug -
    // see its own doc in SettingsScreen.kt): containerColor = surfaceVariant with no explicit
    // contentColor defaults content color to onSurfaceVariant (muted), which BadgeProgressBarRow/
    // BadgeMaxedRow's own title Text below has no explicit color to override.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            BadgeProgressBarRow(
                emoji = row.emoji,
                label = row.label,
                fraction = row.fraction,
                nextThreshold = row.nextThreshold,
                caption = row.caption
            )
        }
    }
}

/** UX-FIX-PLAN.md Phase 3 item 22: the notification-bell's panel - a plain scrollable list of every
 * [InboxItem] in [items] (already in priority order, see [NotificationInbox.buildItems]'s own doc), each
 * one tappable straight through to [onItemClick] which does exactly what tapping its equivalent Home
 * banner would do (see HomeScreen's own grantBlePermissions/openLocationSettings/etc lambdas). A
 * ModalBottomSheet rather than a full Screen - see this card's own call site in HomeScreen for why. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationInboxSheet(
    items: List<InboxItem>,
    onDismiss: () -> Unit,
    onItemClick: (InboxItemKind) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Notifications", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (items.isEmpty()) {
                Text(
                    "You're all caught up 💛",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                items.forEach { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        onClick = { onItemClick(item.kind) }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                item.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MemoryThrowbackCard(
    moments: List<Moment>,
    // MINOR fix (independent audit): was inferred from `moments.size > 1`, which only catches an
    // anniversary match with MULTIPLE qualifying years - a match with exactly one other year (list
    // size 1, identical to a plain random pick) silently fell into the generic "A memory from…"
    // framing even though the app genuinely knows it's an on-this-day photo. Explicit flag instead.
    isAnniversary: Boolean,
    milestones: List<Milestone>,
    now: Long,
    onOpenMoments: (jumpToEpochDay: Long) -> Unit = {}
) {
    // User-requested slideshow: a single-element list renders exactly like the old one-photo card
    // (no pager chrome for the common case); more than one (an anniversary match across several years -
    // see HomeViewModel.pickRandomMomentIfNeeded's own doc) becomes swipeable, with dot indicators.
    val pagerState = rememberPagerState(pageCount = { moments.size })
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                val moment = moments[page]
                // UX-FIX-PLAN.md Phase 3 item 20: opens Moments scrolled to THIS page's own photo day -
                // the closest this app can get to "open the exact Moment" without a dedicated
                // single-photo deep link, consistent with Calendar's own day -> Moments link. Recomputed
                // per page (not hoisted once outside the pager) since each page is a different photo/day.
                val momentEpochDay = remember(moment) {
                    Instant.ofEpochMilli(moment.takenAt).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
                }
                val milestoneLabel = remember(moment, milestones) {
                    milestones.firstOrNull { it.linkedMomentSyncId == moment.syncId }?.label
                }
                // BUG fix (user-reported "rotated images are not saved"): see PhotoEditor.cacheBustKey's
                // own doc - without this, a photo rotated from the Moments viewer kept showing its
                // pre-rotate orientation here since Coil caches by photoUri alone.
                val cacheBustKey = remember(moment.photoUri) { PhotoEditor.cacheBustKey(moment.photoUri) }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = { onOpenMoments(momentEpochDay) }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(moment.photoUri)
                            .memoryCacheKey("${moment.photoUri}:$cacheBustKey")
                            .diskCacheKey("${moment.photoUri}:$cacheBustKey")
                            .build(),
                        contentDescription = "Memory",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth(0.28f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        // Feature: when this photo happens to be one linked to a milestone (see
                        // Milestone.linkedMomentSyncId's doc), name the actual occasion instead of the
                        // generic caption - "📸 Anniversary" says more than "📸 A memory from 40 weeks
                        // ago" when we genuinely know what the photo is of.
                        if (milestoneLabel != null) {
                            Text("📸 $milestoneLabel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("From ${timeAgo(moment.takenAt, now)} 💛", style = MaterialTheme.typography.bodySmall)
                        } else if (isAnniversary) {
                            Text("📸 On this day, ${timeAgo(moment.takenAt, now)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            // "Swipe for more" only makes sense when there's actually a second page to
                            // swipe to - an anniversary match with exactly one qualifying year is still
                            // "on this day" framing, just without that line.
                            Text(
                                if (moments.size > 1) "Swipe for more from this day 💛" else "A memory from this day, years ago 💛",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Text("📸 A memory from ${timeAgo(moment.takenAt, now)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("A little throwback for you two 💛", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (moments.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(moments.size) { i ->
                        val active = i == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (active) 7.dp else 6.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.3f)
                                )
                        )
                    }
                }
            }
        }
    }
}

/** "On this same calendar date in a previous year, you two spent N hours together" - a time-stats
 * callback, distinct from [MemoryThrowbackCard]'s random-photo throwback above it (this one's purely
 * about together-TIME, not photos, and can show up even for a couple with zero saved Moments). Copies
 * [MemoryThrowbackCard]'s Card shape/color for visual consistency; no image slot since there's no photo
 * involved here. */
@Composable
private fun OnThisDayCard(info: OnThisDayInfo, onClick: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "🕰️ On this day in ${info.year}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                // MINOR fix (final Opus re-audit): pinned to Locale.US, matching the ui/stats/ convention.
                "You spent ${"%.1f".format(Locale.US, info.hours)}h together 💛",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun timeAgo(pastMillis: Long, now: Long): String {
    val diffDays = (now - pastMillis) / (24 * 3600 * 1000L)
    return when {
        diffDays <= 0 -> "today"
        diffDays == 1L -> "yesterday"
        diffDays < 30 -> "$diffDays days ago"
        diffDays < 365 -> "${diffDays / 30} months ago"
        else -> "${diffDays / 365} years ago"
    }
}
