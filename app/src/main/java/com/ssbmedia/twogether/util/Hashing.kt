package com.ssbmedia.twogether.util

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Hashing {
    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private const val PBKDF2_ITERATIONS = 20_000
    private const val PBKDF2_KEY_BITS = 256

    private fun pbkdf2Hex(input: String, saltBytes: ByteArray, iterations: Int = PBKDF2_ITERATIONS): String {
        val spec = PBEKeySpec(input.toCharArray(), saltBytes, iterations, PBKDF2_KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return key.encoded.joinToString("") { "%02x".format(it) }
    }

    /**
     * Random-salted PBKDF2 for values that only ever need to be verified LOCALLY on this one device
     * (e.g. the app-lock PIN) - safe to randomize per install since nothing else needs to independently
     * re-derive the exact same hash. Stored format: "<saltHex>:<hashHex>" (the salt isn't secret, so
     * storing it alongside the hash is fine). Thousands of PBKDF2 rounds + a random salt makes a raw
     * extracted hash resistant to both brute force and precomputed rainbow tables, unlike the previous
     * bare single-round SHA-256 of a <=6-digit PIN.
     */
    fun hashWithRandomSalt(input: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        return "$saltHex:${pbkdf2Hex(input, salt)}"
    }

    /**
     * Verifies [input] against [stored], transparently accepting the OLD bare-SHA-256 format that
     * predates this PBKDF2-with-random-salt strengthening (a stored value in the new format always
     * contains a "salt:hash" separator; the old format never did). Without this fallback, any stored
     * value still in the old format would be rejected unconditionally forever - matchesRandomSalt()
     * used to `split(":")` and bail with `parts.size != 2`, meaning the correct PIN could never verify
     * again after this hashing upgrade shipped, with no escape except uninstalling (destroying all
     * local data - sessions/moments/capsules/date-ideas). Callers that get a match via the legacy
     * branch should re-persist the value via hashWithRandomSalt() right away so the device
     * self-migrates to the stronger format the next time it succeeds - see isLegacyFormat().
     */
    fun matchesRandomSalt(input: String, stored: String?): Boolean {
        if (stored.isNullOrBlank()) return false
        if (!stored.contains(":")) return sha256Hex(input) == stored
        val parts = stored.split(":", limit = 2)
        if (parts.size != 2) return false
        val salt = try {
            ByteArray(parts[0].length / 2) { i -> parts[0].substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: Exception) {
            return false
        }
        return pbkdf2Hex(input, salt) == parts[1]
    }

    /** True iff [stored] looks like the pre-upgrade bare-SHA-256 format (no "salt:hash" separator)
     * rather than the current hashWithRandomSalt() format - see matchesRandomSalt(). Callers use this
     * right after a successful match to decide whether to re-persist in the new format. */
    fun isLegacyFormat(stored: String?): Boolean = !stored.isNullOrBlank() && !stored.contains(":")

    // A fixed, app-embedded pepper (NOT secret - it's baked into the APK and extractable by anyone with
    // the APK) used only to add PBKDF2 iteration cost to the *shared pairing-code* hash. This
    // deliberately can NOT be a per-install random salt like hashWithRandomSalt() above: both partners'
    // phones must independently derive the exact same hash from the same 6-digit pairing code (the code
    // itself is never transmitted between the phones) so their advertised BLE prefix bytes (see
    // ProximityForegroundService.hashToPrefixBytes / BleConstants.deriveBytesFromHexHash) and GATT
    // handshake tokens (see BleConstants.HANDSHAKE_TOKEN_*) agree with each other - a random per-install
    // salt would make the two phones compute different hashes from the same code and proximity
    // detection + sync would never work at all. Thousands of PBKDF2 rounds still meaningfully raise the
    // cost of brute-forcing a raw extracted hash back to one of the 10^6 possible 6-digit codes (a real
    // per-guess cost vs. a single SHA-256 call) - it just can't provide the rainbow-table protection a
    // true random salt would, since the pepper is fixed and technically recoverable from the APK.
    private const val PAIRING_CODE_PEPPER = "Twogether_v1_pairing_pepper_2e91f3a7"

    /** Deterministic-but-strengthened hash for the shared pairing code - see PAIRING_CODE_PEPPER above
     * for why this can't use a random salt. Both the "create pair" and "join pair" flows, and the
     * PIN-recovery "forgot PIN? re-enter your pairing code" flow, must all keep using this same
     * function so they keep agreeing with each other. */
    fun strengthenedPairingCodeHex(code: String): String =
        pbkdf2Hex(code, PAIRING_CODE_PEPPER.toByteArray(Charsets.UTF_8))

    /**
     * Verifies a pairing code against a stored pairSecretHash, accepting BOTH the current strengthened
     * (pepper+PBKDF2) hash and the old bare-SHA-256 hash that predates it. Unlike matchesRandomSalt()
     * above, the two formats here can't be told apart by shape alone - strengthenedPairingCodeHex() is
     * deterministic with no per-value salt, so an old sha256Hex(code) and a new
     * strengthenedPairingCodeHex(code) are both just bare 64-char hex, indistinguishable without
     * recomputing both and comparing. Used by the "Forgot PIN -> re-enter pairing code" recovery flow
     * (PinLockScreen), which was otherwise permanently broken for any device that paired before the
     * PBKDF2 upgrade - the same hashing fix that added hashWithRandomSalt() for the PIN also changed
     * this pairing-code hash.
     *
     * IMPORTANT: deliberately NOT auto-migrated/re-persisted the way the PIN hash is on a legacy match.
     * pairSecretHash is used directly (never re-derived) on BOTH partner phones to derive the BLE
     * advertise/scan prefix and GATT handshake token (see
     * ProximityForegroundService.ensureBleRunning/startGattSyncIfNeeded) - both phones' independently
     * stored copies must stay byte-identical for proximity detection to keep working. Rewriting just
     * this one device's copy to the new format the moment its owner uses "Forgot PIN" would silently
     * diverge it from a partner phone that's still on the old format (the common case, since both
     * partners almost always paired together under the same pre-upgrade build) - breaking BLE
     * detection between them, a worse and much harder-to-diagnose regression than the recovery-flow
     * bug this function fixes.
     */
    fun matchesPairingCode(code: String, stored: String?): Boolean {
        if (stored.isNullOrBlank()) return false
        return strengthenedPairingCodeHex(code) == stored || sha256Hex(code) == stored
    }
}
