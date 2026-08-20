package com.ssbmedia.twogether.notif

/**
 * UX-FIX-PLAN.md Phase 3 items 22/23: which persistent, unresolved item is currently "in the inbox" -
 * i.e. would show up as one of Home's own permission/status banners, the notification-bell panel, or (for
 * the first 3 kinds) the dialog-stacking precedence chain [NotificationInbox.blockingPermissionKind] is
 * built around.
 */
enum class InboxItemKind {
    BLE_PERMISSION,
    LOCATION_SERVICES,
    NOTIFICATION_PERMISSION,
    BATTERY_OPTIMIZATION,
    DND_ACCESS,
    PAIRING_UNCONFIRMED,
    PENDING_RESYNC,
    UPDATE_AVAILABLE
}

/** One row in the notification-bell panel - see [NotificationInbox.buildItems]. Deliberately just display
 * text + a [kind] to dispatch on; the actual action (grant permission, open settings, approve/dismiss
 * resync, install update) is wired by the caller (HomeScreen), which already owns every launcher/callback
 * needed - this stays plain data so it's trivially unit-testable without any Android/Compose dependency. */
data class InboxItem(
    val kind: InboxItemKind,
    val title: String,
    val subtitle: String
)

/**
 * Plain snapshot of every "is X currently unresolved" signal Home already independently computes for its
 * own banners (permission checks, battery-exemption/DND-access state, pairing-unconfirmed, pending resync
 * requests, pending update) - see HomeScreen.kt's call site. Passing this whole snapshot into
 * [NotificationInbox.buildItems] is what lets the bell panel and the existing banners share exactly one
 * source of truth, per UX-FIX-PLAN.md item 22's explicit ask not to build a second, parallel way of
 * computing "is X true" that could drift from the banners' own logic.
 */
data class InboxSignals(
    val hasBlePermission: Boolean,
    val needsLocationServices: Boolean,
    val hasNotificationPermission: Boolean,
    val batteryExempt: Boolean,
    val hasDndAccess: Boolean,
    val pairingUnconfirmed: Boolean,
    val pendingResyncCount: Int,
    val pendingUpdateActionable: Boolean,
    val pendingUpdateVersionName: String? = null
)

object NotificationInbox {

    /**
     * Which single "foundational" permission banner Home should currently show, mirroring EXACTLY the
     * `if (!hasBlePermission) ... else if (needsLocationServices) ... else if (!hasNotificationPermission)
     * ...` precedence chain HomeScreen's own LazyColumn already uses (BLE is more fundamental than Location
     * Services, which is more fundamental than the notification-permission nag - see that chain's own
     * comments for why). Returns null once all 3 are resolved. This is the single function both HomeScreen
     * (to pick which one banner to render) and [buildItems] (to add the matching panel row, if any) call -
     * see item 22's "reuse that same state" requirement - and it's also what
     * [BatteryOptimizationGate][com.ssbmedia.twogether.ui.battery.BatteryOptimizationGate] calls to decide
     * whether it's safe to fire yet (item 23 point 3's dialog-stacking fix: BLE > Location Services >
     * notification permission > battery optimization > DND access, and a more-fundamental blocker here
     * always wins over the battery dialog).
     */
    fun blockingPermissionKind(
        hasBlePermission: Boolean,
        needsLocationServices: Boolean,
        hasNotificationPermission: Boolean
    ): InboxItemKind? = when {
        !hasBlePermission -> InboxItemKind.BLE_PERMISSION
        needsLocationServices -> InboxItemKind.LOCATION_SERVICES
        !hasNotificationPermission -> InboxItemKind.NOTIFICATION_PERMISSION
        else -> null
    }

    /**
     * The full, ordered list of everything currently unresolved, for the notification-bell panel - see
     * UX-FIX-PLAN.md item 22. Order matches the app's own priority: the single foundational permission
     * banner (if any, via [blockingPermissionKind]) first, then battery optimization, then DND access,
     * then the 3 softer/informational items (pairing-unconfirmed, pending resync, pending update) - which
     * don't participate in [blockingPermissionKind]'s dialog-stacking precedence since none of them is
     * itself an auto-popping system dialog that could stack on top of another.
     */
    fun buildItems(signals: InboxSignals): List<InboxItem> {
        val items = mutableListOf<InboxItem>()

        when (blockingPermissionKind(signals.hasBlePermission, signals.needsLocationServices, signals.hasNotificationPermission)) {
            InboxItemKind.BLE_PERMISSION -> items += InboxItem(
                InboxItemKind.BLE_PERMISSION,
                "Bluetooth permission needed",
                "Without it Twogether can't detect when you two are nearby each other."
            )
            InboxItemKind.LOCATION_SERVICES -> items += InboxItem(
                InboxItemKind.LOCATION_SERVICES,
                "Location Services is off",
                "Bluetooth detection also needs Location Services turned on in system settings."
            )
            InboxItemKind.NOTIFICATION_PERMISSION -> items += InboxItem(
                InboxItemKind.NOTIFICATION_PERMISSION,
                "Notification permission needed",
                "You're still being tracked together, but you won't see status, photo reminders, or alerts."
            )
            else -> {}
        }

        if (!signals.batteryExempt) {
            items += InboxItem(
                InboxItemKind.BATTERY_OPTIMIZATION,
                "Background tracking may be unreliable",
                "Let Twogether skip battery optimization so it isn't killed while your phone is idle."
            )
        }
        if (!signals.hasDndAccess) {
            items += InboxItem(
                InboxItemKind.DND_ACCESS,
                "Reminders may stay silent during Do Not Disturb",
                "Grant Do Not Disturb access so photo/list reminders can actually break through."
            )
        }
        if (signals.pairingUnconfirmed) {
            items += InboxItem(
                InboxItemKind.PAIRING_UNCONFIRMED,
                "Still confirming this pairing",
                "We couldn't confirm this pairing with your partner's phone yet."
            )
        }
        if (signals.pendingResyncCount > 0) {
            items += InboxItem(
                InboxItemKind.PENDING_RESYNC,
                "A device tried to use your pairing code",
                if (signals.pendingResyncCount == 1) {
                    "1 request is waiting for your review."
                } else {
                    "${signals.pendingResyncCount} requests are waiting for your review."
                }
            )
        }
        if (signals.pendingUpdateActionable) {
            items += InboxItem(
                InboxItemKind.UPDATE_AVAILABLE,
                "Update ${signals.pendingUpdateVersionName ?: ""} ready".trimEnd(),
                "A newer version of Twogether has already been downloaded and is ready to install."
            )
        }
        return items
    }

    /** Drives the bell icon's badge dot - true iff [buildItems] would return anything at all. */
    fun hasAnyUnresolved(signals: InboxSignals): Boolean = buildItems(signals).isNotEmpty()
}
