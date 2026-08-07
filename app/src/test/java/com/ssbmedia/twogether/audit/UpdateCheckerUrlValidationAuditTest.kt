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

    // Below: proposed by the fresh Sonnet reviewer that re-verified isTrustedReleaseAssetUrl
    // (ultimate-app-review Step 4 localized-fix routing) - closing the coverage gaps it flagged.

    @Test
    fun `userinfo before the real host does not prevent trust`() {
        // https://evil.com@github.com/... - uri.host is genuinely "github.com" here (userinfo is a
        // separate URI component), so this one is EXPECTED to be trusted - included to document that,
        // not as a bypass. The dangerous direction (real host name used only as userinfo, real host
        // being something else) is the next test.
        assertTrue(isTrusted("https://evil.com@github.com/Twogether-2.5.apk"))
    }

    @Test
    fun `userinfo used to smuggle a fake host in front of the real-looking suffix is rejected`() {
        // https://github.com@evil.com/... - uri.host is "evil.com"; "github.com" is just userinfo
        // (credentials), the realistic phishing shape a naive string-contains check would miss.
        assertFalse(isTrusted("https://github.com@evil.com/Twogether-2.5.apk"))
    }

    @Test
    fun `an IP-literal host is rejected`() {
        assertFalse(isTrusted("https://140.82.121.3/Twogether-2.5.apk"))
    }

    @Test
    fun `a javascript scheme is rejected`() {
        assertFalse(isTrusted("javascript:alert(1)"))
    }

    @Test
    fun `a file scheme is rejected`() {
        assertFalse(isTrusted("file:///etc/passwd"))
    }

    @Test
    fun `a trailing-dot FQDN for the real host is still trusted`() {
        // "github.com." is DNS-equivalent to "github.com" - must not be falsely rejected (fails safe
        // either way, but the intent is to accept the real host in all its equivalent forms).
        assertTrue(isTrusted("https://github.com./Twogether-2.5.apk"))
    }

    @Test
    fun `uppercase scheme is still accepted`() {
        assertTrue(isTrusted("HTTPS://github.com/Twogether-2.5.apk"))
    }

    // Below: Fable's F-3 finding (ultimate-app-review Step 4) - the original `.githubusercontent.com`
    // SUFFIX match covered `raw.` and `gist.`, where any GitHub user can host arbitrary bytes under
    // their own account; under this fix's own threat model (a tampered API response), that's a real
    // bypass. Narrowed to the exact `objects.githubusercontent.com` host.

    @Test
    fun `raw-githubusercontent-com is no longer trusted - any GitHub user can host bytes there`() {
        assertFalse(isTrusted("https://raw.githubusercontent.com/attacker/anyrepo/main/evil.apk"))
    }

    @Test
    fun `gist-githubusercontent-com is no longer trusted - any GitHub user can host bytes there`() {
        assertFalse(isTrusted("https://gist.githubusercontent.com/attacker/id/raw/evil.apk"))
    }

    @Test
    fun `the exact release-asset CDN host is still trusted`() {
        assertTrue(isTrusted("https://objects.githubusercontent.com/some/signed/path/Twogether-2.5.apk"))
    }
}
