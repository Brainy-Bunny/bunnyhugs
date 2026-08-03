package com.ssbmedia.twogether.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.ble.BlePermissions
import com.ssbmedia.twogether.data.datastore.LastConnectionInfo
import com.ssbmedia.twogether.data.datastore.PairingStore
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import com.ssbmedia.twogether.util.Hashing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class PairStep { LANDING, CREATE_SHOW_CODE, JOIN_ENTER_CODE, PARTNER_INFO, PERMISSIONS }

class PairingViewModel(private val pairingStore: PairingStore) : ViewModel() {
    var step by mutableStateOf(PairStep.LANDING)
        private set
    var generatedCode by mutableStateOf("")
        private set
    var enteredCode by mutableStateOf("")
    var partnerName by mutableStateOf("")
    var partnerEmoji by mutableStateOf("💕")
    var pendingCode by mutableStateOf("")
        private set

    val lastConnection = pairingStore.lastConnection
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LastConnectionInfo())

    /** Restores the previous pairing instantly (no code re-entry) - see PairingStore.reconnectToLast(). */
    fun reconnect(onDone: () -> Unit) {
        viewModelScope.launch {
            pairingStore.reconnectToLast()
            onDone()
        }
    }

    fun startCreate() {
        generatedCode = (100000..999999).random().toString()
        pendingCode = generatedCode
        step = PairStep.CREATE_SHOW_CODE
    }

    fun startJoin() {
        step = PairStep.JOIN_ENTER_CODE
    }

    fun confirmJoinCode() {
        if (enteredCode.length == 6) {
            pendingCode = enteredCode
            step = PairStep.PARTNER_INFO
        }
    }

    fun continueFromCode() {
        step = PairStep.PARTNER_INFO
    }

    fun continueFromPartnerInfo() {
        step = PairStep.PERMISSIONS
    }

    fun backToLanding() {
        step = PairStep.LANDING
    }

    fun finishPairing(onDone: () -> Unit) {
        viewModelScope.launch {
            val hash = Hashing.strengthenedPairingCodeHex(pendingCode)
            pairingStore.savePairing(hash, pendingCode, partnerName, partnerEmoji)
            onDone()
        }
    }
}

private val emojiOptions = listOf("💕", "😍", "🥰", "💛", "🐢", "🐰", "🌸", "✨")

@Composable
fun PairingScreen(onPaired: () -> Unit) {
    val vm: PairingViewModel = viewModel(factory = SimpleViewModelFactory { PairingViewModel(ServiceLocator.pairingStore) })
    val context = LocalContext.current
    val lastConnection by vm.lastConnection.collectAsState()

    // finishPairing() flips PairingStore.isPaired, which MainActivity uses to immediately start the
    // proximity foreground service - and that service needs to know whether BLE permission is
    // actually granted before it starts (a "connectedDevice" foreground service started without it
    // throws and crashes the app, see ProximityForegroundService.onCreate). So finishPairing() must
    // only run *after* the permission request has resolved (granted or denied), never in parallel
    // with it.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.finishPairing(onPaired)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            when (vm.step) {
                PairStep.LANDING -> LandingContent(
                    onCreate = vm::startCreate,
                    onJoin = vm::startJoin,
                    lastConnection = lastConnection,
                    onReconnect = { vm.reconnect(onPaired) }
                )
                PairStep.CREATE_SHOW_CODE -> ShowCodeContent(code = vm.generatedCode, onContinue = vm::continueFromCode, onBack = vm::backToLanding)
                PairStep.JOIN_ENTER_CODE -> JoinCodeContent(
                    value = vm.enteredCode,
                    onValueChange = { vm.enteredCode = it.filter { c -> c.isDigit() }.take(6) },
                    onConfirm = vm::confirmJoinCode,
                    onBack = vm::backToLanding
                )
                PairStep.PARTNER_INFO -> PartnerInfoContent(
                    name = vm.partnerName,
                    onNameChange = { vm.partnerName = it },
                    emoji = vm.partnerEmoji,
                    onEmojiChange = { vm.partnerEmoji = it },
                    onContinue = vm::continueFromPartnerInfo
                )
                PairStep.PERMISSIONS -> PermissionsContent(
                    onAllow = {
                        val toRequest = (BlePermissions.required().toList() +
                            listOfNotNull(BlePermissions.notificationPermission()))
                            .filter { ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                        if (toRequest.isNotEmpty()) {
                            permissionLauncher.launch(toRequest.toTypedArray())
                        } else {
                            vm.finishPairing(onPaired)
                        }
                    },
                    onSkip = { vm.finishPairing(onPaired) }
                )
            }
        }
    }
}

@Composable
private fun LandingContent(
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    lastConnection: com.ssbmedia.twogether.data.datastore.LastConnectionInfo,
    onReconnect: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(text = "💕", fontSize = 64.sp)
        Text(
            text = "Twogether",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = "A cozy little app just for the two of you. Let's get your phones paired up.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 32.dp)
        )
        // Feature 3: a locally-remembered snapshot of the pairing that was just unpaired lets this same
        // phone rejoin it in one tap, with no code re-entry - only useful as long as THIS phone's own
        // local data survives (not after a factory reset / reinstall of this phone; that needs a Feature
        // 4 backup restore instead).
        if (lastConnection.exists) {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Were you paired with ${lastConnection.partnerName.ifBlank { "your person" }} before?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onReconnect, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                        Text("Reconnect to ${lastConnection.partnerName.ifBlank { "them" }} ${lastConnection.partnerEmoji}")
                    }
                }
            }
        }
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Text("Create Pair")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Text("Join Pair")
        }
        Spacer(modifier = Modifier.height(20.dp))
        // Feature 4's disaster-recovery entry point: if THIS phone's own local data was wiped (factory
        // reset, reinstall, `pm clear`) rather than just unpaired, there's no "last connection" snapshot
        // left to reconnect to (Feature 3 needs this phone's local DataStore to have survived) - a full
        // backup restore is the only way back, and this is the only screen a wiped phone ever shows.
        com.ssbmedia.twogether.ui.backup.RestoreBackupButton { onClick ->
            TextButton(onClick = onClick) { Text("Restore from a backup") }
        }
    }
}

@Composable
private fun ShowCodeContent(code: String, onContinue: () -> Unit, onBack: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Your pairing code", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Share this with your partner — they'll type it in on their phone. This code works on your phone right away, no need to wait.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Text(
                text = code.chunked(3).joinToString("  "),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp)
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Text("Continue")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Text("Back")
        }
    }
}

@Composable
private fun JoinCodeContent(value: String, onValueChange: (String) -> Unit, onConfirm: () -> Unit, onBack: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Enter your partner's code", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Type in the 6-digit code shown on their phone.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            placeholder = { Text("123456", textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onConfirm,
            enabled = value.length == 6,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large
        ) { Text("Confirm") }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Text("Back")
        }
    }
}

@Composable
private fun PartnerInfoContent(name: String, onNameChange: (String) -> Unit, emoji: String, onEmojiChange: (String) -> Unit, onContinue: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Who are you pairing with?", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "This is just for your phone — pick a name and a little emoji for them.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            placeholder = { Text("Their name") },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            emojiOptions.forEach { option ->
                val selected = option == emoji
                Card(
                    onClick = { onEmojiChange(option) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(option, fontSize = 22.sp, modifier = Modifier.padding(10.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Text("Continue")
        }
    }
}

@Composable
private fun PermissionsContent(onAllow: () -> Unit, onSkip: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("🔒", fontSize = 48.sp)
        Text(
            text = "One last thing",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )
        val locationNote = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            " Android also requires the location permission for Bluetooth scanning on your phone's version — Twogether never actually looks at your location, it's only used to detect your partner's phone nearby."
        } else ""
        Text(
            text = "Twogether uses Bluetooth to gently notice when you two are nearby each other — completely offline, nothing ever leaves your phones. We'll also ask for notification permission so we can tell you sweet little updates.$locationNote",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Text("Allow & Finish")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Text("Skip for now")
        }
    }
}
