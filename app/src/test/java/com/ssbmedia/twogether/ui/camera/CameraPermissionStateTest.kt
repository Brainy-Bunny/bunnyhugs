package com.ssbmedia.twogether.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UX-FIX-PLAN.md Phase 3 item 16, point 6: coverage for the tri-state permission-recovery decision
 * CameraScreen renders off of. The tricky part this guards against: Android's own
 * shouldShowRequestPermissionRationale is `false` in TWO different real situations (never asked yet, and
 * permanently denied) - see [CameraPermissionState.recoveryState]'s own doc.
 */
class CameraPermissionStateTest {

    @Test
    fun `not yet requested reads as NOT_YET_ASKED regardless of rationale flag`() {
        assertEquals(
            PermissionRecoveryState.NOT_YET_ASKED,
            CameraPermissionState.recoveryState(hasRequestedBefore = false, shouldShowRationale = false)
        )
        assertEquals(
            PermissionRecoveryState.NOT_YET_ASKED,
            CameraPermissionState.recoveryState(hasRequestedBefore = false, shouldShowRationale = true)
        )
    }

    @Test
    fun `requested once and rationale still allowed reads as CAN_REQUEST_AGAIN`() {
        assertEquals(
            PermissionRecoveryState.CAN_REQUEST_AGAIN,
            CameraPermissionState.recoveryState(hasRequestedBefore = true, shouldShowRationale = true)
        )
    }

    @Test
    fun `requested and rationale now suppressed reads as PERMANENTLY_DENIED`() {
        assertEquals(
            PermissionRecoveryState.PERMANENTLY_DENIED,
            CameraPermissionState.recoveryState(hasRequestedBefore = true, shouldShowRationale = false)
        )
    }
}
