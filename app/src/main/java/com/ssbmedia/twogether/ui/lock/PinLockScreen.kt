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
import kotlinx.coroutines.launch

/** Full-screen gate shown on cold start and whenever the app resumes from the background, if PIN lock is enabled. */
@Composable
fun PinLockScreen() {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showForgot by remember { mutableStateOf(false) }

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
                        scope.launch {
                            val storedHash = ServiceLocator.settingsStore.current().pinHash
                            if (PinUtil.matches(pin, storedHash)) {
                                if (PinUtil.isLegacyFormat(storedHash)) {
                                    // Correct PIN, but verified via the old bare-SHA-256 fallback -
                                    // self-migrate to the strengthened format now that it's proven
                                    // correct, so this device never needs to touch this path again.
                                    // Safe to do unconditionally (unlike pairSecretHash below): pinHash
                                    // is purely local and never compared against another device.
                                    ServiceLocator.settingsStore.setPin(PinUtil.hash(pin))
                                }
                                AppLockManager.unlock()
                            } else {
                                AppLockManager.recordFailedPinAttempt()
                                error = "Wrong PIN, try again"
                                pin = ""
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    shape = MaterialTheme.shapes.large,
                    enabled = pin.length >= 4 && !lockedOut
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
            isError = error != null
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        Button(
            onClick = {
                scope.launch {
                    val pairing = ServiceLocator.pairingStore.current()
                    // Accepts both the current strengthened hash and the old bare-SHA-256 hash a
                    // device paired before the PBKDF2 upgrade would still have stored - see
                    // Hashing.matchesPairingCode for why this is deliberately NOT auto-migrated the
                    // way the PIN hash is.
                    if (Hashing.matchesPairingCode(code, pairing.pairSecretHash)) {
                        ServiceLocator.settingsStore.clearPin()
                        onReset()
                    } else {
                        error = "That code doesn't match"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            shape = MaterialTheme.shapes.large,
            enabled = code.length == 6
        ) { Text("Reset PIN") }
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) { Text("Back") }
    }
}
