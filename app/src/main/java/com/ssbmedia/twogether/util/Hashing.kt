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

    private const val PBKDF2_ITERATIONS_CURRENT = 600_000
    // The round count this app used before the strengthening below - kept only so verifyRandomSalt()/
    // matchesPairingCode() can still verify a hash that was computed under it (any hash stored before
    // this app version). Never used to compute a NEW hash.
    private const val PBKDF2_ITERATIONS_LEGACY = 20_000
    private const val PBKDF2_KEY_BITS = 256

    private fun pbkdf2Hex(input: String, saltBytes: ByteArray, iterations: Int = PBKDF2_ITERATIONS_CURRENT): String {
        val spec = PBEKeySpec(input.toCharArray(), saltBytes, iterations, PBKDF2_KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return key.encoded.joinToString("") { "%02x".format(it) }
    }

    /** TEST-ONLY - do not call from production code. `internal` visibility (needed so HashingTest, in a
     * separate file, can reach it) makes this visible to the rest of this module too, not just tests -
     * the TEST_ONLY_ prefix exists specifically so it can never look like a legitimate hashing call at a
     * real call site or get autocompleted in place of hashWithRandomSalt() by mistake (which would
     * silently persist a PIN at the weaker legacy iteration count). Builds a realistic "salted hash
     * computed before the PBKDF2_ITERATIONS_CURRENT bump" fixture, so HashingTest can cover the
     * MATCHED_OLDER-via-legacy-iterations branch of verifyRandomSalt() without needing pbkdf2Hex's
     * iteration parameter exposed publicly. */
    internal fun TEST_ONLY_hashWithRandomSaltAtLegacyIterations(input: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        return "$saltHex:${pbkdf2Hex(input, salt, PBKDF2_ITERATIONS_LEGACY)}"
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

    /** Outcome of [verifyRandomSalt]: whether [input] matched [stored] at all, and if so, whether it
     * matched via the CURRENT strongest format or an older/weaker one that should be self-migrated. */
    enum class SaltedVerifyResult { NO_MATCH, MATCHED_CURRENT, MATCHED_OLDER }

    /**
     * Verifies [input] against [stored] in ONE pass (rather than two separate re-derivations, see the
     * MINOR fix note below), transparently accepting TWO older formats that predate the current one: the
     * bare-SHA-256 format from before random-salted PBKDF2 was added at all (no "salt:hash" separator),
     * and a salted-but-PBKDF2_ITERATIONS_LEGACY-rounds hash from before the later iteration-count
     * strengthening (same "salt:hash" shape as the current format, only distinguishable by actually
     * re-deriving at both round counts). Without this fallback, any stored value still in an older format
     * would be rejected unconditionally forever, meaning the correct PIN could never verify again after a
     * hashing upgrade shipped, with no escape except uninstalling (destroying all local data -
     * sessions/moments/capsules/date-ideas). Callers that get MATCHED_OLDER back should re-persist the
     * value via hashWithRandomSalt() right away so the device self-migrates to the current format.
     *
     * MINOR fix: this used to be two separate functions (matchesRandomSalt() + needsRehash()), and a
     * caller that needed both answers - as PinLockScreen's unlock flow does - ended up computing
     * pbkdf2Hex(input, salt) at CURRENT iterations TWICE, once inside each function, since needsRehash()
     * had no way to know what matches() had already found. At PBKDF2_ITERATIONS_CURRENT (600k rounds)
     * that doubled real unlock latency on every single successful unlock, not just ones that actually
     * needed migrating. Returning one result both callers can branch on fixes that for good.
     */
    fun verifyRandomSalt(input: String, stored: String?): SaltedVerifyResult {
        if (stored.isNullOrBlank()) return SaltedVerifyResult.NO_MATCH
        if (!stored.contains(":")) {
            return if (sha256Hex(input) == stored) SaltedVerifyResult.MATCHED_OLDER else SaltedVerifyResult.NO_MATCH
        }
        val parts = stored.split(":", limit = 2)
        if (parts.size != 2) return SaltedVerifyResult.NO_MATCH
        val salt = try {
            ByteArray(parts[0].length / 2) { i -> parts[0].substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: Exception) {
            return SaltedVerifyResult.NO_MATCH
        }
        if (pbkdf2Hex(input, salt) == parts[1]) return SaltedVerifyResult.MATCHED_CURRENT
        if (pbkdf2Hex(input, salt, PBKDF2_ITERATIONS_LEGACY) == parts[1]) return SaltedVerifyResult.MATCHED_OLDER
        return SaltedVerifyResult.NO_MATCH
    }

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
     * Verifies a pairing code against a stored pairSecretHash, accepting the CURRENT strengthened
     * (pepper+PBKDF2_ITERATIONS_CURRENT) hash, the previous strengthened (pepper+PBKDF2_ITERATIONS_LEGACY)
     * hash from before the later iteration-count bump, and the original bare-SHA-256 hash that predates
     * PBKDF2 entirely. None of these three formats can be told apart by shape alone -
     * strengthenedPairingCodeHex() is deterministic with no per-value salt, so all three are just bare
     * 64-char hex, indistinguishable without recomputing each and comparing. Used by the "Forgot PIN ->
     * re-enter pairing code" recovery flow (PinLockScreen), which was otherwise permanently broken for
     * any device that paired before a given PBKDF2 strengthening - the same hashing fix that added
     * hashWithRandomSalt() for the PIN also changed this pairing-code hash.
     *
     * IMPORTANT: deliberately NOT auto-migrated/re-persisted the way the PIN hash is on an older-format
     * match. pairSecretHash is used directly (never re-derived) on BOTH partner phones to derive the BLE
     * advertise/scan prefix and GATT handshake key (see
     * ProximityForegroundService.ensureBleRunning/startGattSyncIfNeeded) - both phones' independently
     * stored copies must stay byte-identical for proximity detection to keep working. Rewriting just
     * this one device's copy to a newer format the moment its owner uses "Forgot PIN" would silently
     * diverge it from a partner phone that's still on an older format (the common case, since both
     * partners almost always paired together under the same build) - breaking BLE detection between
     * them, a worse and much harder-to-diagnose regression than the recovery-flow bug this function fixes.
     */
    fun matchesPairingCode(code: String, stored: String?): Boolean {
        if (stored.isNullOrBlank()) return false
        return strengthenedPairingCodeHex(code) == stored ||
            pbkdf2Hex(code, PAIRING_CODE_PEPPER.toByteArray(Charsets.UTF_8), PBKDF2_ITERATIONS_LEGACY) == stored ||
            sha256Hex(code) == stored
    }

    /** TEST-ONLY - do not call from production code, see TEST_ONLY_hashWithRandomSaltAtLegacyIterations'
     * doc for why this is `internal` rather than `private`. Builds the pepper+PBKDF2_ITERATIONS_LEGACY
     * pairing-code hash a couple who paired before the iteration-count bump (but after the pepper was
     * introduced) would have stored, so HashingTest can cover that middle branch of matchesPairingCode()
     * - previously only its CURRENT and bare-SHA256 branches had coverage. */
    internal fun TEST_ONLY_strengthenedPairingCodeHexAtLegacyIterations(code: String): String =
        pbkdf2Hex(code, PAIRING_CODE_PEPPER.toByteArray(Charsets.UTF_8), PBKDF2_ITERATIONS_LEGACY)
}
