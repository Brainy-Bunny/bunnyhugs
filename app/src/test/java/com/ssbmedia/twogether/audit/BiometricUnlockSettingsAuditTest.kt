package com.ssbmedia.twogether.audit

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import com.ssbmedia.twogether.data.datastore.AppSettings
import com.ssbmedia.twogether.data.datastore.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Item 5 (deferred UX fix, 4-model advisory audit): AppSettings.biometricUnlockEnabled - the persisted
 * opt-in for the optional biometric-unlock alternative to the PIN screen (default OFF, see its own doc for
 * why this must never default on). Same SettingsStore.fromPreferences round-trip approach as
 * PhotoReminderSettingsAuditTest - see that file's own doc for why this is JVM-unit-testable with no
 * Robolectric.
 */
class BiometricUnlockSettingsAuditTest {

    private val biometricUnlockKey = booleanPreferencesKey("biometric_unlock_enabled")

    @Test
    fun `default AppSettings has biometric unlock OFF`() {
        assertFalse(AppSettings().biometricUnlockEnabled)
    }

    @Test
    fun `a settings snapshot with no biometric key yet defaults to OFF`() {
        val settings = SettingsStore.fromPreferences(emptyPreferences())
        assertFalse(settings.biometricUnlockEnabled)
    }

    @Test
    fun `a persisted ON value round-trips through fromPreferences unchanged`() {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[biometricUnlockKey] = true
        }
        val settings = SettingsStore.fromPreferences(prefs)
        assertEquals(true, settings.biometricUnlockEnabled)
    }

    @Test
    fun `changing the persisted value changes only biometricUnlockEnabled, not sibling defaults`() {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[biometricUnlockKey] = true
        }
        val settings = SettingsStore.fromPreferences(prefs)
        assertEquals(true, settings.biometricUnlockEnabled)
        assertFalse(settings.pinEnabled)
        assertEquals(10, settings.defaultSnoozeMinutes)
    }
}
