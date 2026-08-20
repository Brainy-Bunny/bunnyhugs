package com.ssbmedia.twogether.audit

import com.ssbmedia.twogether.service.ProximityForegroundService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MINOR fix (ultimate-app-review round 1, item 3): both partners derive their advertised secret prefix
 * from the same shared pairing code, so nothing app-level otherwise distinguishes "this is my partner's
 * phone" from "this is my own advertisement echoing back to my own scanner." ScannerManager already parses
 * a per-install tie-break byte off every sighting; ProximityForegroundService.onPartnerSeen now compares
 * it against this device's OWN tie-break byte via the private pure helper isSelfEchoBeacon before treating
 * a sighting as "partner seen" at all.
 *
 * isSelfEchoBeacon is private, so this is tested directly via reflection - same pattern GraceWindowAuditTest
 * already established for withinGraceWindow/graceWindowExpired (this codebase's unit tests have no
 * Robolectric/Context available, so the Service itself can't be instantiated in a local JVM test).
 */
class SelfEchoGuardAuditTest {

    private fun isSelfEchoBeacon(sightedTieBreak: Byte, ownTieBreak: Byte): Boolean {
        val companion = ProximityForegroundService.Companion
        val method = companion.javaClass.getDeclaredMethod(
            "isSelfEchoBeacon", Byte::class.javaPrimitiveType, Byte::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(companion, sightedTieBreak, ownTieBreak) as Boolean
    }

    @Test
    fun `a sighting reporting the same tie-break byte as this device's own is flagged as a self-echo`() {
        assertTrue(isSelfEchoBeacon(42.toByte(), 42.toByte()))
    }

    @Test
    fun `a sighting reporting a different tie-break byte is NOT flagged as a self-echo`() {
        assertFalse(isSelfEchoBeacon(42.toByte(), 7.toByte()))
    }

    @Test
    fun `zero tie-break bytes on both sides still correctly match as a self-echo`() {
        // Guards against an accidental "0 means unset, never match" special-case creeping in - 0 is a
        // perfectly valid random tie-break byte draw (getOrCreateTieBreakByte draws from 0..255), not a
        // sentinel for "no value yet".
        assertTrue(isSelfEchoBeacon(0.toByte(), 0.toByte()))
    }

    @Test
    fun `negative-signed byte values (128-255 unsigned range) still compare correctly`() {
        // Byte in Kotlin/JVM is signed (-128..127) - a tie-break byte drawn from (0..255).random() and
        // stored as an Int, then narrowed .toByte(), can produce a negative Byte value for anything >= 128.
        // Equality must still hold correctly across that representation, not just for small positive draws.
        val highByte = 200 // > 127, wraps to a negative Byte
        assertTrue(isSelfEchoBeacon(highByte.toByte(), highByte.toByte()))
        assertFalse(isSelfEchoBeacon(highByte.toByte(), (highByte - 1).toByte()))
    }

    @Test
    fun `every byte value is self-symmetric - isSelfEchoBeacon(x, x) is always true across the full range`() {
        for (i in 0..255) {
            val b = i.toByte()
            assertTrue("byte value $i (as Byte $b) must match itself", isSelfEchoBeacon(b, b))
        }
    }
}
