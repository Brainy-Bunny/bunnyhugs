package com.ssbmedia.twogether.ui.home

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.ble.BlePermissions
import com.ssbmedia.twogether.ble.ProximityStateMachine
import com.ssbmedia.twogether.data.datastore.PairingInfo
import com.ssbmedia.twogether.data.datastore.ProximityPersistedState
import com.ssbmedia.twogether.data.db.Milestone
import com.ssbmedia.twogether.data.db.Moment
import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.service.ProximityForegroundService
import com.ssbmedia.twogether.stats.OnThisDayInfo
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.ui.components.PulsingHeart
import com.ssbmedia.twogether.ui.components.QuickLinkChip
import com.ssbmedia.twogether.ui.components.SectionHeader
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // Picked once per Home visit (this ViewModel instance) — a simple "throwback" surface.
    val randomMoment: MutableStateFlow<Moment?> = MutableStateFlow(null)

    /** Only ever picks from moments this device actually HOLDS the photo bytes for - a remote-stub
     * moment (partner's photo metadata synced, but the bytes haven't transferred yet - see
     * Moment.photoDownloaded's doc) has nothing to show, and a card whose entire point is surfacing a
     * photo memory shouldn't ever land on one it can't display. MomentsScreen already guards this same
     * situation at render time (photoDownloaded + File(photoUri).isFile before treating a moment as
     * renderable); filtering it out of the candidate pool here is the stronger fix - the card either
     * shows a real memory or doesn't appear at all, never a broken-image placeholder where a photo was
     * expected. The isFile check (not just the DB flag) additionally covers the rare case of the flag
     * being true but the file having since vanished some other way. */
    suspend fun pickRandomMomentIfNeeded(all: List<Moment>) {
        if (randomMoment.value != null) return
        // MINOR fix: File.isFile is blocking disk I/O, and this used to run directly on whatever
        // dispatcher the caller's LaunchedEffect is on (Main) - moved off Main here so a large moment
        // list (or repeated re-runs while nothing is downloaded yet, e.g. right after a restore) can't
        // ever cause a stutter.
        val withPhoto = withContext(Dispatchers.IO) { all.filter { it.photoDownloaded && File(it.photoUri).isFile } }
        if (withPhoto.isNotEmpty()) randomMoment.value = withPhoto.random()
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
    onNavigateMilestones: () -> Unit = {}
) {
    val vm: HomeViewModel = viewModel(factory = SimpleViewModelFactory { HomeViewModel() })
    val pairingInfo by vm.pairingInfo.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val openSession by vm.openSession.collectAsState()
    val proximityState by vm.proximityState.collectAsState()
    val moments by vm.moments.collectAsState()
    val randomMoment by vm.randomMoment.collectAsState()
    val milestones by vm.milestones.collectAsState()
    val quickLinksOrder by vm.quickLinksOrder.collectAsState()

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

    LaunchedEffect(moments) { vm.pickRandomMomentIfNeeded(moments) }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

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

    // lastSeenAt clamps an open session's live duration to the last confirmed sighting + absence
    // timeout, matching Home's own effectivelyTogether staleness check below - see
    // StatsCalculator.effectiveOpenSessionEnd's doc for why (a stale/orphaned open session must
    // never silently "grow" forever just because something read it).
    val stats = remember(sessions, now, proximityState.lastSeenAt) {
        StatsCalculator.compute(sessions, now, lastSeenAt = proximityState.lastSeenAt)
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
                item {
                    BlePermissionWarningCard(
                        onGrantClick = {
                            val toRequest = (BlePermissions.required().toList() +
                                listOfNotNull(BlePermissions.notificationPermission()))
                                .filter { ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                            if (toRequest.isNotEmpty()) blePermissionLauncher.launch(toRequest.toTypedArray())
                        }
                    )
                }
            } else if (needsLocationServices) {
                // Only shown once BLE permission itself is granted - a redundant "also turn on Location"
                // banner on top of the permission banner would be confusing since granting BLE permission
                // is the more fundamental blocker.
                item {
                    LocationServicesWarningCard(
                        onOpenSettingsClick = {
                            context.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }
                    )
                }
            } else if (!hasNotificationPermission) {
                // Same "only once the more fundamental blocker is clear" reasoning as the Location
                // Services banner above - shown only once BLE is granted and Location Services (if
                // relevant) is on, since a missing notification permission is real but secondary: the
                // service still tracks together-time correctly without it, it just can't SHOW you that
                // it's doing so (no ongoing notification, no photo-reminder/reunion/milestone alerts).
                item {
                    NotificationPermissionWarningCard(
                        onGrantClick = {
                            val perm = BlePermissions.notificationPermission()
                            if (perm != null) notificationPermissionLauncher.launch(perm)
                        }
                    )
                }
            }
            item {
                UsStatusCard(isTogether = effectivelyTogether, openSession = openSession, now = now, partnerName = pairingInfo.partnerName, partnerEmoji = pairingInfo.partnerEmoji)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("This week", style = MaterialTheme.typography.bodySmall)
                            Text("${"%.1f".format(stats.totalHoursThisWeek)}h", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("🔥 Daily streak", style = MaterialTheme.typography.bodySmall)
                            // BUG fix: a 1-day streak (very common right after the couple's first day
                            // together) read as "1 days".
                            Text("${stats.currentDailyStreak} day" + (if (stats.currentDailyStreak == 1) "" else "s"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("🗓️ Days together", style = MaterialTheme.typography.bodySmall)
                            Text("${stats.totalDaysTogether}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (randomMoment != null) {
                item {
                    val linkedMilestoneLabel = remember(randomMoment, milestones) {
                        milestones.firstOrNull { it.linkedMomentSyncId == randomMoment!!.syncId }?.label
                    }
                    MemoryThrowbackCard(moment = randomMoment!!, now = now, milestoneLabel = linkedMilestoneLabel)
                }
            }
            if (onThisDayInfo != null) {
                item {
                    OnThisDayCard(info = onThisDayInfo)
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

@Composable
private fun UsStatusCard(isTogether: Boolean, openSession: TogetherSession?, now: Long, partnerName: String, partnerEmoji: String) {
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
                val elapsed = openSession?.let { now - it.startedAt } ?: 0L
                Text(
                    text = "Together for ${formatDuration(elapsed)}",
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

@Composable
private fun MemoryThrowbackCard(moment: Moment, now: Long, milestoneLabel: String? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = moment.photoUri,
                contentDescription = "Memory",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth(0.28f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .padding(end = 0.dp)
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                // Feature: when this randomly-picked photo happens to be one linked to a milestone (see
                // Milestone.linkedMomentSyncId's doc), name the actual occasion instead of the generic
                // caption - "📸 Anniversary" says more than "📸 A memory from 40 weeks ago" when we
                // genuinely know what the photo is of.
                if (milestoneLabel != null) {
                    Text("📸 $milestoneLabel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("From ${timeAgo(moment.takenAt, now)} 💛", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("📸 A memory from ${timeAgo(moment.takenAt, now)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("A little throwback for you two 💛", style = MaterialTheme.typography.bodySmall)
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
private fun OnThisDayCard(info: OnThisDayInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "🕰️ On this day in ${info.year}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "You spent ${"%.1f".format(info.hours)}h together 💛",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalMinutes = millis / 60000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
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
