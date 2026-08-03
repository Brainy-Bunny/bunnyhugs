package com.ssbmedia.twogether.lock

import com.ssbmedia.twogether.util.Hashing

object PinUtil {
    fun hash(pin: String): String = Hashing.hashWithRandomSalt(pin)

    fun matches(pin: String, storedHash: String?): Boolean = Hashing.matchesRandomSalt(pin, storedHash)

    /** True iff [storedHash] is in the pre-upgrade bare-SHA-256 format; callers use this right after a
     * successful matches() to decide whether to self-migrate the stored value to the stronger format. */
    fun isLegacyFormat(storedHash: String?): Boolean = Hashing.isLegacyFormat(storedHash)
}
