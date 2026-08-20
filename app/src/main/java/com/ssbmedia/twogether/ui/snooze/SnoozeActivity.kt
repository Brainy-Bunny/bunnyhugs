package com.ssbmedia.twogether.ui.snooze

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.data.datastore.AppSettings
import com.ssbmedia.twogether.notif.Notifications
import com.ssbmedia.twogether.ui.theme.TwogetherTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SnoozeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.cancelPhotoReminder(this)

        // The persisted "default snooze length" setting (Settings screen) used to be written but never
        // actually read anywhere - this screen always showed the same hardcoded 5/15/30/60 presets
        // regardless of what the user configured. Read it here and use it as the pre-selected default.
        val defaultSnoozeMinutes = ServiceLocator.settingsStore.settings
            .stateIn(lifecycleScope, SharingStarted.Eagerly, AppSettings())

        setContent {
            TwogetherTheme {
                val settings by defaultSnoozeMinutes.collectAsState()
                Surface(color = androidx.compose.ui.graphics.Color.Transparent) {
                    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        SnoozeCard(
                            defaultMinutes = settings.defaultSnoozeMinutes,
                            onPick = { minutes -> applySnooze(minutes) },
                            onCancel = { finish() }
                        )
                    }
                }
            }
        }
    }

    private fun applySnooze(minutes: Int) {
        lifecycleScope.launch {
            val until = System.currentTimeMillis() + minutes * 60_000L
            ServiceLocator.proximityStateStore.update { it.copy(snoozeUntil = until, reminderFiredForSession = true) }
            finish()
        }
    }
}

@androidx.compose.runtime.Composable
private fun SnoozeCard(defaultMinutes: Int, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    var customMinutes by remember { mutableStateOf("") }
    // Includes the user's configured default snooze length (Settings screen) among the presets - even
    // if it isn't one of the standard 5/15/30/60 values - and highlights it as the pre-selected choice,
    // rather than always showing the same hardcoded presets regardless of what was configured.
    val presets = remember(defaultMinutes) { (listOf(5, 15, 30, 60) + defaultMinutes).distinct().sorted() }

    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Snooze the reminder", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "We'll nudge you again after this long, if you're still together.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )
            // Item 6 (deferred UX fix, 4-model advisory audit): this used to be a FilterChip highlighted
            // via `selected = minutes == defaultMinutes` - but every chip here applies immediately on tap
            // and finishes the activity (see onPick above and applySnooze), so that highlight read as a
            // misleading "pre-selection" describing a selection step that never actually happens - tapping
            // ANY chip, highlighted or not, has the identical one-tap effect. AssistChip carries no
            // selected/checked semantics at all, matching the real "tap one, done" interaction honestly.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { minutes ->
                    AssistChip(onClick = { onPick(minutes) }, label = { Text("${minutes}m") })
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = customMinutes,
                    onValueChange = { customMinutes = it.filter { c -> c.isDigit() }.take(4) },
                    placeholder = { Text("Custom minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium
                )
                Button(
                    onClick = { customMinutes.toIntOrNull()?.let { if (it > 0) onPick(it) } },
                    enabled = customMinutes.toIntOrNull()?.let { it > 0 } == true,
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("Set") }
            }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Cancel") }
        }
    }
}
