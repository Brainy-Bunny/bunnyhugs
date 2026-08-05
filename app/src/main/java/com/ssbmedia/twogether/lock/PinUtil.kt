package com.ssbmedia.twogether.lock

import com.ssbmedia.twogether.util.Hashing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PinUtil {
    /** BLOCKER fix: PBKDF2 at PBKDF2_ITERATIONS_CURRENT (600k rounds) takes real, user-visible time.
     * Every caller of hash()/verify() invokes it from inside a Compose-scoped coroutine
     * (viewModelScope/rememberCoroutineScope both default to Dispatchers.Main.immediate) - without this
     * withContext, the CPU-bound derivation would run directly on the UI thread and visibly freeze it
     * for the duration. Doing the dispatcher switch HERE means every current and future caller gets it
     * for free, rather than relying on each call site to remember to withContext() it themselves. */
    suspend fun hash(pin: String): String = withContext(Dispatchers.Default) { Hashing.hashWithRandomSalt(pin) }

    /** Single-pass verify - see Hashing.verifyRandomSalt's doc for why this replaced two separate
     * functions that used to each independently re-derive the same 600k-round hash. */
    suspend fun verify(pin: String, storedHash: String?): Hashing.SaltedVerifyResult =
        withContext(Dispatchers.Default) { Hashing.verifyRandomSalt(pin, storedHash) }

    suspend fun matches(pin: String, storedHash: String?): Boolean =
        verify(pin, storedHash) != Hashing.SaltedVerifyResult.NO_MATCH
}
