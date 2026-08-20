package com.ssbmedia.twogether.ui.camera

/**
 * UX-FIX-PLAN.md Phase 3 item 16, point 6: which recovery affordance CameraScreen should show once
 * CAMERA permission is confirmed NOT granted. Pulled out as a pure function (no Context/Activity) so the
 * tri-state decision - "haven't asked yet" vs "denied once, can ask again" vs "permanently denied,
 * only Settings can fix it" - is unit-testable without Robolectric or a real Activity.
 */
enum class PermissionRecoveryState {
    /** This screen hasn't finished its first permission request yet (result not back, or app just
     * launched) - no recovery UI needed, the system dialog itself is either about to show or showing. */
    NOT_YET_ASKED,

    /** Denied at least once, but Android will still show its own request dialog again - offer a
     * "Grant access" button that simply re-launches the same request. */
    CAN_REQUEST_AGAIN,

    /** Denied "don't ask again" (or denied on a device/OS that never shows a rationale for this
     * permission) - calling the request launcher again would silently no-op with no dialog at all, so
     * the only real way out is the system app-details Settings screen. */
    PERMANENTLY_DENIED
}

object CameraPermissionState {
    /**
     * [hasRequestedBefore] - true once this screen has received at least one result (granted or denied)
     * from the CAMERA permission request launcher this composition.
     * [shouldShowRationale] - Android's own `ActivityCompat.shouldShowRequestPermissionRationale` result
     * for CAMERA. Per Android's own documented contract this is `false` BOTH before the permission has
     * ever been requested AND after the user has permanently denied it ("don't ask again") - the two
     * cases are indistinguishable from this flag alone, which is exactly why [hasRequestedBefore] has to
     * be folded in here rather than branching on `shouldShowRationale` by itself.
     */
    fun recoveryState(hasRequestedBefore: Boolean, shouldShowRationale: Boolean): PermissionRecoveryState {
        return when {
            !hasRequestedBefore -> PermissionRecoveryState.NOT_YET_ASKED
            shouldShowRationale -> PermissionRecoveryState.CAN_REQUEST_AGAIN
            else -> PermissionRecoveryState.PERMANENTLY_DENIED
        }
    }
}
