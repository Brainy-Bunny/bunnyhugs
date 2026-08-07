package com.ssbmedia.twogether.audit

import com.ssbmedia.twogether.data.update.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ultimate-app-review spec-test (Step 4, Fable finding, 2026-08-07) - independently derived from
 * checklist.md's constraint that the auto-updater must only ever install from a genuine GitHub
 * release asset URL, never the GitHub Releases API response's `browser_download_url` verbatim.
 * Guards `isTrustedReleaseAssetUrl` (private, invoked via reflection) against the exact gap Fable's
 * adversarial pass found: an unvalidated host/scheme letting a tampered API response point the
 * "tap to install" flow at an arbitrary server.
 */
class UpdateCheckerUrlValidationAuditTest {

    private fun isTrusted(url: String): Boolean {
        val method = UpdateChecker.javaClass.getDeclaredMethod("isTrustedReleaseAssetUrl", String::class.java)
        method.isAccessible = true
        return method.invoke(UpdateChecker, url) as Boolean
    }

    @Test
    fun `github-com https asset url is trusted`() {
        assertTrue(isTrusted("https://github.com/Brainy-Bunny/bunnyhugs/releases/download/v15/Twogether-2.5.apk"))
    }

    @Test
    fun `githubusercontent-com redirect-signing CDN is trusted`() {
        assertTrue(isTrusted("https://objects.githubusercontent.com/some/signed/path/Twogether-2.5.apk"))
    }

    @Test
    fun `host match is case-insensitive`() {
        assertTrue(isTrusted("https://GitHub.COM/Brainy-Bunny/bunnyhugs/releases/download/v15/Twogether-2.5.apk"))
    }

    @Test
    fun `plain http is rejected even on the real host`() {
        assertFalse(isTrusted("http://github.com/Brainy-Bunny/bunnyhugs/releases/download/v15/Twogether-2.5.apk"))
    }

    @Test
    fun `an arbitrary host is rejected`() {
        assertFalse(isTrusted("https://evil.example.com/Twogether-2.5.apk"))
    }

    @Test
    fun `a lookalike host that merely contains githubusercontent-com is rejected`() {
        // e.g. attacker-controlled "githubusercontent.com.evil.net" must not match via a naive
        // substring/contains check - only a genuine subdomain of the real domain should pass.
        assertFalse(isTrusted("https://objects.githubusercontent.com.evil.net/Twogether-2.5.apk"))
    }

    @Test
    fun `a malformed url is rejected, not thrown`() {
        assertFalse(isTrusted("not a url at all"))
    }
}
