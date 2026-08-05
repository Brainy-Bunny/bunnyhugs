package com.ssbmedia.twogether.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression coverage for the "legacy/older hash format must still verify" upgrade-compatibility
 * shim. Runs the actual production Hashing object (no mocking) against realistic pre-upgrade stored
 * values to prove it really works, not just that it compiles.
 */
class HashingTest {

    // ---- PIN path (Hashing.verifyRandomSalt / hashWithRandomSalt) ----

    @Test
    fun `fresh PIN set-then-verify still works with the current format`() {
        val pin = "4321"
        val stored = Hashing.hashWithRandomSalt(pin)
        assertTrue("stored new-format hash must contain the salt separator", stored.contains(":"))
        assertEquals(Hashing.SaltedVerifyResult.MATCHED_CURRENT, Hashing.verifyRandomSalt(pin, stored))
        assertEquals(Hashing.SaltedVerifyResult.NO_MATCH, Hashing.verifyRandomSalt("0000", stored))
    }

    @Test
    fun `legacy bare-SHA256 PIN hash from before any PBKDF2 upgrade still verifies correctly`() {
        val pin = "1234"
        // Simulates exactly what a device that set its PIN before the PBKDF2 upgrade would have
        // persisted: the old bare sha256Hex(pin), no salt prefix.
        val legacyStored = Hashing.sha256Hex(pin)
        assertFalse("legacy stored value must NOT look like the salted format", legacyStored.contains(":"))

        assertEquals(
            "correct PIN must still verify against a bare-SHA256 stored hash",
            Hashing.SaltedVerifyResult.MATCHED_OLDER,
            Hashing.verifyRandomSalt(pin, legacyStored)
        )
        assertEquals(
            "wrong PIN must still be rejected against a bare-SHA256 stored hash",
            Hashing.SaltedVerifyResult.NO_MATCH,
            Hashing.verifyRandomSalt("9999", legacyStored)
        )
    }

    @Test
    fun `salted PIN hash from before the iteration-count bump still verifies correctly`() {
        val pin = "2468"
        // Simulates a device that set its PIN after the salted-PBKDF2 upgrade but before the later
        // PBKDF2_ITERATIONS_CURRENT bump - same "salt:hash" shape as the current format, only
        // distinguishable by actually re-deriving at both round counts (see verifyRandomSalt's doc).
        val legacyIterationsStored = Hashing.TEST_ONLY_hashWithRandomSaltAtLegacyIterations(pin)
        assertTrue(legacyIterationsStored.contains(":"))

        assertEquals(
            "correct PIN must still verify against a legacy-iteration-count salted hash",
            Hashing.SaltedVerifyResult.MATCHED_OLDER,
            Hashing.verifyRandomSalt(pin, legacyIterationsStored)
        )
        assertEquals(
            "wrong PIN must still be rejected against a legacy-iteration-count salted hash",
            Hashing.SaltedVerifyResult.NO_MATCH,
            Hashing.verifyRandomSalt("0000", legacyIterationsStored)
        )
    }

    @Test
    fun `legacy PIN hash self-migrates to the current format and keeps verifying after migration`() {
        val pin = "5678"
        val legacyStored = Hashing.sha256Hex(pin)

        // Step 1: what PinLockScreen's onClick does - verify, detect MATCHED_OLDER, re-hash+persist.
        assertEquals(Hashing.SaltedVerifyResult.MATCHED_OLDER, Hashing.verifyRandomSalt(pin, legacyStored))
        val migrated = Hashing.hashWithRandomSalt(pin)

        // Step 2: the device is now on the current format and must keep working, including rejecting
        // wrong PINs, via the CURRENT branch this time (not the older-format fallback).
        assertEquals(Hashing.SaltedVerifyResult.MATCHED_CURRENT, Hashing.verifyRandomSalt(pin, migrated))
        assertEquals(Hashing.SaltedVerifyResult.NO_MATCH, Hashing.verifyRandomSalt("0000", migrated))
    }

    @Test
    fun `null or blank stored PIN hash never matches anything`() {
        assertEquals(Hashing.SaltedVerifyResult.NO_MATCH, Hashing.verifyRandomSalt("1234", null))
        assertEquals(Hashing.SaltedVerifyResult.NO_MATCH, Hashing.verifyRandomSalt("1234", ""))
    }

    // ---- Pairing-code recovery path (Hashing.matchesPairingCode) ----

    @Test
    fun `fresh strengthened pairing code hash verifies with the current algorithm`() {
        val code = "246810"
        val stored = Hashing.strengthenedPairingCodeHex(code)
        assertTrue(Hashing.matchesPairingCode(code, stored))
        assertFalse(Hashing.matchesPairingCode("000000", stored))
    }

    @Test
    fun `legacy bare-SHA256 pairing code hash from before any PBKDF2 upgrade still verifies via Forgot PIN`() {
        val code = "135791"
        // Simulates a couple that paired before the PBKDF2-pepper upgrade: pairSecretHash was sha256Hex(code).
        val legacyStored = Hashing.sha256Hex(code)
        // Confirms the two algorithms really do produce different bytes for the same code (otherwise
        // this whole test would be vacuous).
        assertFalse(legacyStored == Hashing.strengthenedPairingCodeHex(code))

        assertTrue("correct pairing code must verify against a bare-SHA256 pairSecretHash", Hashing.matchesPairingCode(code, legacyStored))
        assertFalse("wrong pairing code must still be rejected", Hashing.matchesPairingCode("999999", legacyStored))
    }

    @Test
    fun `pairing code hash from before the iteration-count bump still verifies via Forgot PIN`() {
        val code = "802468"
        // Simulates a couple that paired after the pepper was introduced but before the later
        // PBKDF2_ITERATIONS_CURRENT bump - same bare-64-char-hex shape as the current strengthened hash,
        // only distinguishable by actually re-deriving at both round counts (see matchesPairingCode's doc).
        val legacyIterationsStored = Hashing.TEST_ONLY_strengthenedPairingCodeHexAtLegacyIterations(code)
        assertFalse(
            "sanity check this fixture isn't accidentally identical to the current-iteration hash",
            legacyIterationsStored == Hashing.strengthenedPairingCodeHex(code)
        )

        assertTrue(
            "correct pairing code must still verify against a legacy-iteration-count pairSecretHash",
            Hashing.matchesPairingCode(code, legacyIterationsStored)
        )
        assertFalse(
            "wrong pairing code must still be rejected against a legacy-iteration-count pairSecretHash",
            Hashing.matchesPairingCode("111111", legacyIterationsStored)
        )
    }

    @Test
    fun `null pairSecretHash never matches any pairing code`() {
        assertFalse(Hashing.matchesPairingCode("123456", null))
    }
}
