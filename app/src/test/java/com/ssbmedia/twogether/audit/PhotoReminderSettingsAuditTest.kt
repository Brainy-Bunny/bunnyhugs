package com.ssbmedia.twogether.audit

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.ssbmedia.twogether.data.datastore.AppSettings
import com.ssbmedia.twogether.data.datastore.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Item 24 (UX-FIX-PLAN.md): configurable photo reminder interval (AppSettings.photoReminderMinutes,
 * default 15, replacing the old hardcoded ProximityForegroundService.FIFTEEN_MINUTES_MILLIS constant).
 *
 * SettingsStore.fromPreferences was extracted out of the `settings: Flow<AppSettings>` map{} lambda
 * specifically so this read path is unit-testable directly against a plain in-memory
 * androidx.datastore.preferences.core.Preferences snapshot - that type (and MutablePreferences/
 * emptyPreferences()/intPreferencesKey()) are ordinary JVM classes with no Android Context dependency,
 * unlike the actual DataStore file (`context.settingsDs`) which needs a real Context this project's
 * plain-JVM unit tests don't have available (no Robolectric - see app/build.gradle.kts's
 * testImplementation list). This exercises the real read-side code path SettingsStore.settings uses in
 * production, not a reimplementation of it.
 */
class PhotoReminderSettingsAuditTest {

    // Constructed independently from SettingsStore's own private Keys object - intPreferencesKey equality
    // in DataStore Preferences is by (name, type), not reference identity, so this resolves to the exact
    // same map entry SettingsStore.fromPreferences reads via its own private Keys.PHOTO_REMINDER_MINUTES.
    private val photoReminderKey = intPreferencesKey("photo_reminder_minutes")

    @Test
    fun `default AppSettings has photoReminderMinutes = 15, matching the old hardcoded constant`() {
        assertEquals(15, AppSettings().photoReminderMinutes)
    }

    @Test
    fun `a settings snapshot with no photo-reminder key yet defaults to 15`() {
        val settings = SettingsStore.fromPreferences(emptyPreferences())
        assertEquals(15, settings.photoReminderMinutes)
    }

    @Test
    fun `a persisted value round-trips through fromPreferences unchanged`() {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[photoReminderKey] = 30
        }
        val settings = SettingsStore.fromPreferences(prefs)
        assertEquals(30, settings.photoReminderMinutes)
    }

    @Test
    fun `changing the persisted value changes only photoReminderMinutes, not sibling defaults`() {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[photoReminderKey] = 60
        }
        val settings = SettingsStore.fromPreferences(prefs)
        assertEquals(60, settings.photoReminderMinutes)
        // Sanity: an unrelated field with its own key untouched here still reads its own default -
        // guards against a copy/paste key-mixup inside fromPreferences accidentally wiring
        // PHOTO_REMINDER_MINUTES to some other field, or vice versa.
        assertEquals(15, settings.defaultSnoozeMinutes)
        assertEquals(true, settings.notificationsEnabled)
    }
}
