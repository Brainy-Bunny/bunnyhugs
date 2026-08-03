package com.ssbmedia.twogether.ui.home

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
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
import com.ssbmedia.twogether.data.db.Moment
import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.service.ProximityForegroundService
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.ui.components.PulsingHeart
import com.ssbmedia.twogether.ui.components.QuickLinkChip
import com.ssbmedia.twogether.ui.components.SectionHeader
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HomeViewModel : ViewModel() {
    val pairingInfo = ServiceLocator.pairingStore.info
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PairingInfo())

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

    // Picked once per Home visit (this ViewModel instance) — a simple "throwback" surface.
    val randomMoment: MutableStateFlow<Moment?> = MutableStateFlow(null)

    fun pickRandomMomentIfNeeded(all: List<Moment>) {
        if (randomMoment.value == null && all.isNotEmpty()) {
            randomMoment.value = all.random()
        }
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
    onNavigateCamera: () -> Unit
) {
    val vm: HomeViewModel = viewModel(factory = SimpleViewModelFactory { HomeViewModel() })
    val pairingInfo by vm.pairingInfo.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val openSession by vm.openSession.collectAsState()
    val proximityState by vm.proximityState.collectAsState()
    val moments by vm.moments.collectAsState()
    val randomMoment by vm.randomMoment.collectAsState()

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
    // StatsCalculator.effectiveOpenSessionCutoff's doc for why (a stale/orphaned open session must
    // never silently "grow" forever just because something read it).
    val stats = remember(sessions, now, proximityState.lastSeenAt) {
        StatsCalculator.compute(sessions, now, lastSeenAt = proximityState.lastSeenAt)
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
                            Text("${stats.currentDailyStreak} days", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                    MemoryThrowbackCard(moment = randomMoment!!, now = now)
                }
            }
            item {
                SectionHeader("Quick links")
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(
                        listOf(
                            Triple("📅", "Calendar", onNavigateCalendar),
                            Triple("💌", "Date Ideas", onNavigateDateIdeas),
                            Triple("📸", "Moments", onNavigateMoments),
                            Triple("📊", "Stats", onNavigateStats),
                            Triple("⏳", "Time Capsules", onNavigateCapsules),
                            Triple("🏅", "Badges", onNavigateBadges),
                            Triple("📷", "Take a photo", onNavigateCamera)
                        )
                    ) { (emoji, label, action) ->
                        QuickLinkChip(emoji = emoji, label = label, onClick = action)
                    }
                }
            }
        }

        if (showReunion) {
            ReunionOverlay(onDismiss = { showReunion = false })
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
private fun MemoryThrowbackCard(moment: Moment, now: Long) {
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
                Text("📸 A memory from ${timeAgo(moment.takenAt, now)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("A little throwback for you two 💛", style = MaterialTheme.typography.bodySmall)
            }
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
