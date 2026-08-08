package com.ssbmedia.twogether.audit

import com.ssbmedia.twogether.data.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * ultimate-app-review spec-test (deferred-item fix, 2026-08-08) - independently derived from the
 * user's explicit follow-up ask to finish the three remaining deferred items, including "the
 * downloaded update APK isn't hash-verified in-app." Guards UpdateChecker.sha256HexOfFile (private,
 * invoked via reflection, same pattern as isTrustedReleaseAssetUrl in
 * UpdateCheckerUrlValidationAuditTest) - the new streaming digest UpdateChecker.downloadApk now
 * compares against the release asset's own GitHub-computed `digest` field before ever renaming a
 * downloaded APK onto the notification-linked path.
 *
 * Network-dependent behavior (fetchLatestRelease's digest parsing, downloadApk's end-to-end
 * reject-on-mismatch flow) isn't covered here - both go through real HttpURLConnection calls with no
 * seam to mock in this codebase's existing test setup, same limitation UpdateCheckerUrlValidationAuditTest
 * already accepts for isTrustedReleaseAssetUrl's own caller. This file covers the one new pure,
 * network-free unit the fix introduces.
 */
class UpdateCheckerHashVerificationAuditTest {

    private fun sha256HexOfFile(file: File): String {
        val method = UpdateChecker.javaClass.getDeclaredMethod("sha256HexOfFile", File::class.java)
        method.isAccessible = true
        return method.invoke(UpdateChecker, file) as String
    }

    @Test
    fun `hashes an empty file to the well-known empty-input sha256`() {
        val file = File.createTempFile("update-checker-audit-empty", ".bin")
        try {
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                sha256HexOfFile(file)
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `hashes known content to the correct sha256, matching a standalone reference implementation`() {
        val file = File.createTempFile("update-checker-audit-known", ".bin")
        try {
            file.writeBytes("abc".toByteArray(Charsets.UTF_8))
            // Well-known SHA-256("abc") test vector (FIPS 180-2 Appendix B.1).
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                sha256HexOfFile(file)
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `hashes content larger than the internal read buffer correctly`() {
        // sha256HexOfFile streams through an 8192-byte buffer - this exercises multiple digest.update()
        // calls rather than a single-pass read, the case a tiny fixture would never catch.
        val file = File.createTempFile("update-checker-audit-large", ".bin")
        try {
            val bytes = ByteArray(50_000) { (it % 251).toByte() }
            file.writeBytes(bytes)
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, sha256HexOfFile(file))
        } finally {
            file.delete()
        }
    }
}
