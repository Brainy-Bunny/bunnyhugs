package com.ssbmedia.twogether.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression coverage for the round-3 "legacy hash format causes permanent lockout" fix.
 * Runs the actual production Hashing object (no mocking) against realistic pre-upgrade stored values
 * to prove the upgrade-compatibility shim really works, not just that it compiles.
 */
class HashingTest {

    // ---- PIN path (Hashing.matchesRandomSalt / hashWithRandomSalt) ----

    @Test
    fun `fresh PIN set-then-verify still works with the new salted format`() {
        val pin = "4321"
        val stored = Hashing.hashWithRandomSalt(pin)
        assertTrue("stored new-format hash must contain the salt separator", stored.contains(":"))
        assertTrue(Hashing.matchesRandomSalt(pin, stored))
        assertFalse(Hashing.matchesRandomSalt("0000", stored))
        assertFalse(Hashing.isLegacyFormat(stored))
    }

    @Test
    fun `legacy bare-SHA256 PIN hash from before the upgrade still verifies correctly`() {
        val pin = "1234"
        // Simulates exactly what a device that set its PIN before the PBKDF2 upgrade would have
        // persisted: the old bare sha256Hex(pin), no salt prefix.
        val legacyStored = Hashing.sha256Hex(pin)
        assertFalse("legacy stored value must NOT look like the new format", legacyStored.contains(":"))

        assertTrue("correct PIN must still verify against an old-format stored hash", Hashing.matchesRandomSalt(pin, legacyStored))
        assertFalse("wrong PIN must still be rejected against an old-format stored hash", Hashing.matchesRandomSalt("9999", legacyStored))
        assertTrue(Hashing.isLegacyFormat(legacyStored))
    }

    @Test
    fun `legacy PIN hash self-migrates to the new format and keeps verifying after migration`() {
        val pin = "5678"
        val legacyStored = Hashing.sha256Hex(pin)

        // Step 1: what PinLockScreen's onClick does - verify, detect legacy, re-hash+persist.
        assertTrue(Hashing.matchesRandomSalt(pin, legacyStored))
        assertTrue(Hashing.isLegacyFormat(legacyStored))
        val migrated = Hashing.hashWithRandomSalt(pin)

        // Step 2: the device is now on the new format and must keep working, including rejecting wrong PINs.
        assertFalse(Hashing.isLegacyFormat(migrated))
        assertTrue(Hashing.matchesRandomSalt(pin, migrated))
        assertFalse(Hashing.matchesRandomSalt("0000", migrated))
    }

    @Test
    fun `null or blank stored PIN hash never matches anything`() {
        assertFalse(Hashing.matchesRandomSalt("1234", null))
        assertFalse(Hashing.matchesRandomSalt("1234", ""))
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
    fun `legacy bare-SHA256 pairing code hash from before the upgrade still verifies via Forgot PIN`() {
        val code = "135791"
        // Simulates a couple that paired before the PBKDF2-pepper upgrade: pairSecretHash was sha256Hex(code).
        val legacyStored = Hashing.sha256Hex(code)
        // Confirms the two algorithms really do produce different bytes for the same code (otherwise
        // this whole test would be vacuous).
        assertFalse(legacyStored == Hashing.strengthenedPairingCodeHex(code))

        assertTrue("correct pairing code must verify against an old-format pairSecretHash", Hashing.matchesPairingCode(code, legacyStored))
        assertFalse("wrong pairing code must still be rejected", Hashing.matchesPairingCode("999999", legacyStored))
    }

    @Test
    fun `null pairSecretHash never matches any pairing code`() {
        assertFalse(Hashing.matchesPairingCode("123456", null))
    }
}
