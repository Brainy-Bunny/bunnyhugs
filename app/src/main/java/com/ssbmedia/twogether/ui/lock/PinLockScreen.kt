package com.ssbmedia.twogether.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.lock.AppLockManager
import com.ssbmedia.twogether.lock.PinUtil
import com.ssbmedia.twogether.util.Hashing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen gate shown on cold start and whenever the app resumes from the background, if PIN lock is enabled. */
@Composable
fun PinLockScreen() {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showForgot by remember { mutableStateOf(false) }
    // MINOR fix: guards against a double-tap spawning a second concurrent 600k-round PBKDF2 job (and,
    // on a wrong PIN, recording two failed attempts for one user intent) - see the matching fix in
    // SettingsScreen's VerifyCurrentPinDialog for the same reasoning.
    var isVerifying by remember { mutableStateOf(false) }

    // Basic throttle feedback - see AppLockManager.recordFailedPinAttempt's doc. Ticks once a second
    // only while actually locked out, purely so the countdown text stays live; harmless/no-op otherwise.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val lockedOut = AppLockManager.isPinLockedOut(now)
    LaunchedEffect(lockedOut) {
        while (AppLockManager.isPinLockedOut(System.currentTimeMillis())) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        if (!showForgot) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("🔒", fontSize = 48.sp)
                Text("Enter your PIN", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6); error = null },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    isError = error != null,
                    enabled = !lockedOut
                )
                val displayError = if (lockedOut) {
                    "Too many wrong attempts - try again in ${((AppLockManager.pinLockedOutUntilMillis - now) / 1000L).coerceAtLeast(0L) + 1}s"
                } else {
                    error
                }
                displayError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                Button(
                    onClick = {
                        if (isVerifying) return@Button
                        isVerifying = true
                        scope.launch {
                            // MINOR fix: try/finally - without it, an exception from PinUtil.verify (none
                            // currently throws, but this is a coroutine calling into crypto APIs) would
                            // leave isVerifying stuck true forever, permanently disabling this button.
                            try {
                                val storedHash = ServiceLocator.settingsStore.current().pinHash
                                when (PinUtil.verify(pin, storedHash)) {
                                    Hashing.SaltedVerifyResult.MATCHED_OLDER -> {
                                        // Correct PIN, but verified via an older/weaker fallback (bare
                                        // SHA-256, or a salted hash at a since-bumped iteration count) -
                                        // self-migrate to the current strongest format now that it's proven
                                        // correct, so this device never needs to touch this path again.
                                        // Safe to do unconditionally (unlike pairSecretHash below): pinHash
                                        // is purely local and never compared against another device.
                                        ServiceLocator.settingsStore.setPin(PinUtil.hash(pin))
                                        AppLockManager.unlock()
                                    }
                                    Hashing.SaltedVerifyResult.MATCHED_CURRENT -> AppLockManager.unlock()
                                    Hashing.SaltedVerifyResult.NO_MATCH -> {
                                        AppLockManager.recordFailedPinAttempt()
                                        error = "Wrong PIN, try again"
                                        pin = ""
                                    }
                                }
                            } finally {
                                isVerifying = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    shape = MaterialTheme.shapes.large,
                    enabled = pin.length >= 4 && !lockedOut && !isVerifying
                ) { Text("Unlock") }
                TextButton(onClick = { showForgot = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Forgot PIN?")
                }
            }
        } else {
            ForgotPinContent(onCancel = { showForgot = false }, onReset = { AppLockManager.unlock(); showForgot = false })
        }
    }
}

@Composable
private fun ForgotPinContent(onCancel: () -> Unit, onReset: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // MINOR fix: this button also removes the PIN lock outright on a match, but unlike its two sibling
    // gates (PinLockScreen's own unlock button, SettingsScreen's VerifyCurrentPinDialog) it had neither
    // the shared AppLockManager throttle nor an in-flight guard. Not a regression (each guess still costs
    // one real 600k-round PBKDF2 derivation either way), but there's no reason this one PIN-removal path
    // should be the odd one out now that its siblings are hardened - see PinLockScreen's own matching code
    // for the same reasoning.
    var isVerifying by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val lockedOut = AppLockManager.isPinLockedOut(now)
    LaunchedEffect(lockedOut) {
        while (AppLockManager.isPinLockedOut(System.currentTimeMillis())) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Reset your PIN", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Enter your original 6-digit pairing code to remove the PIN lock.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter { c -> c.isDigit() }.take(6); error = null },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            isError = error != null,
            enabled = !lockedOut
        )
        val displayError = if (lockedOut) {
            "Too many wrong attempts - try again in ${((AppLockManager.pinLockedOutUntilMillis - now) / 1000L).coerceAtLeast(0L) + 1}s"
        } else error
        displayError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        Button(
            onClick = {
                if (isVerifying) return@Button
                isVerifying = true
                scope.launch {
                    try {
                        val pairing = ServiceLocator.pairingStore.current()
                        // BUG fix: PinLockScreen (and this reset path with it) only became reachable
                        // while UNPAIRED once the PIN-lock/pairing branch order in MainActivity was fixed
                        // to close a security gap (PIN used to be silently skippable by unpairing first).
                        // That surfaced a lockout this path never had to handle before: unpair() clears
                        // pairSecretHash outright (moving it to lastSecretHash instead - see PairingStore's
                        // own doc), so checking ONLY pairSecretHash here made a real, correct pairing code
                        // always fail to reset the PIN once the phone was unpaired - permanently locking
                        // the user out of their own app (short of clearing all app data). Falls back to the
                        // last connection's snapshot, which is exactly "the code I originally paired with"
                        // from the user's own perspective and survives an unpair for precisely this kind
                        // of recovery (see LastConnectionInfo's doc). A restore, unlike unpair, forces
                        // BOTH hashes null AND pinEnabled false together (see BackupManager's own SECURITY
                        // doc) - so this fallback is never even reachable in that case, no risk there.
                        val lastConnection = ServiceLocator.pairingStore.currentLastConnection()
                        val secretHashToCheck = pairing.pairSecretHash?.takeIf { it.isNotBlank() } ?: lastConnection.secretHash
                        // Accepts both the current strengthened hash and the old bare-SHA-256 hash a
                        // device paired before the PBKDF2 upgrade would still have stored - see
                        // Hashing.matchesPairingCode for why this is deliberately NOT auto-migrated the
                        // way the PIN hash is. BLOCKER fix: off Main - see PinUtil.hash's doc, same
                        // 600k-round PBKDF2 cost applies here.
                        val codeMatches = withContext(Dispatchers.Default) {
                            Hashing.matchesPairingCode(code, secretHashToCheck)
                        }
                        if (codeMatches) {
                            AppLockManager.resetFailedPinAttempts()
                            ServiceLocator.settingsStore.clearPin()
                            onReset()
                        } else {
                            AppLockManager.recordFailedPinAttempt()
                            error = "That code doesn't match"
                        }
                    } finally {
                        isVerifying = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            shape = MaterialTheme.shapes.large,
            enabled = code.length == 6 && !lockedOut && !isVerifying
        ) { Text("Reset PIN") }
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) { Text("Back") }
    }
}
