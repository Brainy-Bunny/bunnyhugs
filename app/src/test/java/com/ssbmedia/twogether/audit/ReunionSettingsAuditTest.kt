package com.ssbmedia.twogether.audit

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.ssbmedia.twogether.data.datastore.AppSettings
import com.ssbmedia.twogether.data.datastore.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * User-configurable reunion threshold feature: AppSettings.sessionGraceMinutes (replaces the old
 * hardcoded ProximityForegroundService.SESSION_GRACE_MILLIS, default 10) and
 * AppSettings.reunionThresholdMinutes (replaces the old hardcoded StatsCalculator.REUNION_GAP_MILLIS,
 * default 60) - see AppSettings.reunionThresholdMinutes' own doc for the full non-retroactivity
 * guarantee this setting exists to support (tested directly against the Service's decision logic in
 * ReunionNonRetroactivityAuditTest; this file only covers the plain settings-storage round-trip).
 *
 * Mirrors PhotoReminderSettingsAuditTest's own pattern exactly: SettingsStore.fromPreferences is a pure
 * function of a plain in-memory androidx.datastore.preferences.core.Preferences snapshot, so it's
 * unit-testable without Robolectric/a real Context.
 */
class ReunionSettingsAuditTest {

    private val sessionGraceKey = intPreferencesKey("session_grace_minutes")
    private val reunionThresholdKey = intPreferencesKey("reunion_threshold_minutes")

    @Test
    fun `default AppSettings has sessionGraceMinutes = 10, matching the old hardcoded SESSION_GRACE_MILLIS`() {
        assertEquals(10, AppSettings().sessionGraceMinutes)
    }

    @Test
    fun `default AppSettings has reunionThresholdMinutes = 60, matching the old hardcoded REUNION_GAP_MILLIS`() {
        assertEquals(60, AppSettings().reunionThresholdMinutes)
    }

    @Test
    fun `a settings snapshot with neither key yet defaults to 10 and 60 respectively`() {
        val settings = SettingsStore.fromPreferences(emptyPreferences())
        assertEquals(10, settings.sessionGraceMinutes)
        assertEquals(60, settings.reunionThresholdMinutes)
    }

    @Test
    fun `persisted values round-trip through fromPreferences unchanged and independently of each other`() {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[sessionGraceKey] = 3
            this[reunionThresholdKey] = 20
        }
        val settings = SettingsStore.fromPreferences(prefs)
        assertEquals(3, settings.sessionGraceMinutes)
        assertEquals(20, settings.reunionThresholdMinutes)
        // Sanity: an unrelated sibling default is untouched - guards against a copy/paste key-mixup
        // accidentally wiring one of these two new keys to some other field, or vice versa.
        assertEquals(15, settings.photoReminderMinutes)
        assertEquals(15, settings.defaultSnoozeMinutes)
    }

    @Test
    fun `changing only reunionThresholdMinutes leaves sessionGraceMinutes at its default`() {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[reunionThresholdKey] = 15
        }
        val settings = SettingsStore.fromPreferences(prefs)
        assertEquals(15, settings.reunionThresholdMinutes)
        assertEquals("sessionGraceMinutes must be fully independent of reunionThresholdMinutes", 10, settings.sessionGraceMinutes)
    }

    @Test
    fun `changing only sessionGraceMinutes leaves reunionThresholdMinutes at its default`() {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[sessionGraceKey] = 25
        }
        val settings = SettingsStore.fromPreferences(prefs)
        assertEquals(25, settings.sessionGraceMinutes)
        assertEquals("reunionThresholdMinutes must be fully independent of sessionGraceMinutes", 60, settings.reunionThresholdMinutes)
    }
}
