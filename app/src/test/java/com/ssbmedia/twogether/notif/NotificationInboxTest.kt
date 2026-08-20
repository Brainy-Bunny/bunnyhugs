package com.ssbmedia.twogether.notif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-FIX-PLAN.md Phase 3 items 22/23: coverage for the pure "which persistent item is unresolved, in what
 * order" decision logic that drives BOTH the notification-bell panel/badge-dot (item 22) AND the
 * dialog-stacking fix for BatteryOptimizationGate (item 23 point 3). This is exactly the kind of inline
 * Composable decision logic the plan calls out for extraction + unit testing.
 */
class NotificationInboxTest {

    private fun allResolvedSignals() = InboxSignals(
        hasBlePermission = true,
        needsLocationServices = false,
        hasNotificationPermission = true,
        batteryExempt = true,
        hasDndAccess = true,
        pairingUnconfirmed = false,
        pendingResyncCount = 0,
        pendingUpdateActionable = false
    )

    // ---- blockingPermissionKind: the 3-way precedence chain ----

    @Test
    fun `blockingPermissionKind is null when all 3 are resolved`() {
        assertNull(NotificationInbox.blockingPermissionKind(hasBlePermission = true, needsLocationServices = false, hasNotificationPermission = true))
    }

    @Test
    fun `blockingPermissionKind prioritizes BLE over everything else`() {
        val kind = NotificationInbox.blockingPermissionKind(hasBlePermission = false, needsLocationServices = true, hasNotificationPermission = false)
        assertEquals(InboxItemKind.BLE_PERMISSION, kind)
    }

    @Test
    fun `blockingPermissionKind prioritizes location services over notification permission once BLE is granted`() {
        val kind = NotificationInbox.blockingPermissionKind(hasBlePermission = true, needsLocationServices = true, hasNotificationPermission = false)
        assertEquals(InboxItemKind.LOCATION_SERVICES, kind)
    }

    @Test
    fun `blockingPermissionKind falls through to notification permission last`() {
        val kind = NotificationInbox.blockingPermissionKind(hasBlePermission = true, needsLocationServices = false, hasNotificationPermission = false)
        assertEquals(InboxItemKind.NOTIFICATION_PERMISSION, kind)
    }

    // ---- buildItems: full ordered panel contents ----

    @Test
    fun `buildItems is empty when everything is resolved`() {
        assertTrue(NotificationInbox.buildItems(allResolvedSignals()).isEmpty())
        assertFalse(NotificationInbox.hasAnyUnresolved(allResolvedSignals()))
    }

    @Test
    fun `buildItems only surfaces the single most fundamental permission item, not all 3 at once`() {
        val signals = allResolvedSignals().copy(hasBlePermission = false, needsLocationServices = true, hasNotificationPermission = false)
        val items = NotificationInbox.buildItems(signals)
        assertEquals(listOf(InboxItemKind.BLE_PERMISSION), items.map { it.kind })
    }

    @Test
    fun `buildItems orders foundational permission before battery before DND before the softer items`() {
        val signals = InboxSignals(
            hasBlePermission = false,
            needsLocationServices = false,
            hasNotificationPermission = false,
            batteryExempt = false,
            hasDndAccess = false,
            pairingUnconfirmed = true,
            pendingResyncCount = 2,
            pendingUpdateActionable = true,
            pendingUpdateVersionName = "3.0"
        )
        val items = NotificationInbox.buildItems(signals)
        assertEquals(
            listOf(
                InboxItemKind.BLE_PERMISSION,
                InboxItemKind.BATTERY_OPTIMIZATION,
                InboxItemKind.DND_ACCESS,
                InboxItemKind.PAIRING_UNCONFIRMED,
                InboxItemKind.PENDING_RESYNC,
                InboxItemKind.UPDATE_AVAILABLE
            ),
            items.map { it.kind }
        )
    }

    @Test
    fun `buildItems pending resync subtitle is singular for exactly 1 request`() {
        val signals = allResolvedSignals().copy(pendingResyncCount = 1)
        val item = NotificationInbox.buildItems(signals).single { it.kind == InboxItemKind.PENDING_RESYNC }
        assertEquals("1 request is waiting for your review.", item.subtitle)
    }

    @Test
    fun `buildItems pending resync subtitle is plural for more than 1 request`() {
        val signals = allResolvedSignals().copy(pendingResyncCount = 3)
        val item = NotificationInbox.buildItems(signals).single { it.kind == InboxItemKind.PENDING_RESYNC }
        assertEquals("3 requests are waiting for your review.", item.subtitle)
    }

    @Test
    fun `buildItems update title includes the pending version name`() {
        val signals = allResolvedSignals().copy(pendingUpdateActionable = true, pendingUpdateVersionName = "3.1")
        val item = NotificationInbox.buildItems(signals).single { it.kind == InboxItemKind.UPDATE_AVAILABLE }
        assertEquals("Update 3.1 ready", item.title)
    }

    @Test
    fun `buildItems battery and DND can both be present simultaneously, unlike the 3-way permission chain`() {
        val signals = allResolvedSignals().copy(batteryExempt = false, hasDndAccess = false)
        val kinds = NotificationInbox.buildItems(signals).map { it.kind }
        assertTrue(kinds.containsAll(listOf(InboxItemKind.BATTERY_OPTIMIZATION, InboxItemKind.DND_ACCESS)))
    }

    @Test
    fun `hasAnyUnresolved is true when only one soft item is unresolved`() {
        val signals = allResolvedSignals().copy(pairingUnconfirmed = true)
        assertTrue(NotificationInbox.hasAnyUnresolved(signals))
    }
}
