package com.jccdex.toolkits.apkverify

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApkDigestTest {

    @Test
    fun `sha256Hex of bytes returns lowercase hex`() {
        val hash = ApkDigest.sha256Hex("hello".toByteArray())

        assertEquals(64, hash.length)
        assertEquals(hash, hash.lowercase())
    }

    @Test
    fun `sha256Hex of same bytes is deterministic`() {
        val a = ApkDigest.sha256Hex("abc".toByteArray())
        val b = ApkDigest.sha256Hex("abc".toByteArray())

        assertEquals(a, b)
    }

    @Test
    fun `sha256Hex of different bytes differs`() {
        val a = ApkDigest.sha256Hex("abc".toByteArray())
        val b = ApkDigest.sha256Hex("abd".toByteArray())

        assertFalse(a == b)
    }

    @Test
    fun `sha256Hex of file computes correctly`() {
        val file = File.createTempFile("apk-digest-test", ".tmp")
        try {
            file.writeText("hello")
            val hash = ApkDigest.sha256Hex(file)

            assertEquals(ApkDigest.sha256Hex("hello".toByteArray()), hash)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `sha256Hex of inputStream computes correctly`() {
        val stream = ByteArrayInputStream("hello".toByteArray())

        val hash = ApkDigest.sha256Hex(stream)

        assertEquals(ApkDigest.sha256Hex("hello".toByteArray()), hash)
    }

    @Test
    fun `sha256Hex produces valid hex characters`() {
        val hash = ApkDigest.sha256Hex("test".toByteArray())

        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }
}

class OfficialReleaseManifestLoaderTest {

    @Test
    fun `parseFromJson with valid minimal manifest succeeds`() {
        val json =
            """{"signingCertSha256":"abcdef1234567890","releases":[]}"""

        val manifest = OfficialReleaseManifestLoader.parseFromJson(json)

        assertNotNull(manifest)
        assertEquals("abcdef1234567890", manifest!!.signingCertSha256)
        assertTrue(manifest.releases.isEmpty())
    }

    @Test
    fun `parseFromJson with releases parses entries`() {
        val json =
            """{
                "signingCertSha256": "aaaa",
                "releases": [
                    {"versionCode": 1, "versionName": "1.0.0", "apkSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
                ]
            }"""

        val manifest = OfficialReleaseManifestLoader.parseFromJson(json)

        assertNotNull(manifest)
        assertEquals(1, manifest!!.releases.size)
        assertEquals(1, manifest.releases[0].versionCode)
        assertEquals("1.0.0", manifest.releases[0].versionName)
    }

    @Test
    fun `parseFromJson with missing releases returns null`() {
        val json = """{"signingCertSha256":"abc"}"""

        val manifest = OfficialReleaseManifestLoader.parseFromJson(json)

        assertNull(manifest)
    }

    @Test
    fun `parseFromJson with empty json returns null`() {
        assertNull(OfficialReleaseManifestLoader.parseFromJson("{}"))
    }

    @Test
    fun `parseFromJson with malformed json returns null`() {
        assertNull(OfficialReleaseManifestLoader.parseFromJson("not json"))
        assertNull(OfficialReleaseManifestLoader.parseFromJson(""))
    }
}

class ReleaseChecksumsParserTest {

    @Test
    fun `parse valid checksums succeeds`() {
        val text =
            """
            versionName=2.0.0
            versionCode=2
            apkSha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
            signingCertSha256=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
            """.trimIndent()

        val result = ReleaseChecksumsParser.parse(text)

        assertNotNull(result)
        assertEquals("2.0.0", result!!.versionName)
        assertEquals(2, result.versionCode)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", result.apkSha256)
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", result.signingCertSha256)
    }

    @Test
    fun `parse ignores comments and empty lines`() {
        val text =
            """
            # this is a comment
            versionName=1.0.0

            versionCode=1
            apkSha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
            signingCertSha256=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
            """.trimIndent()

        val result = ReleaseChecksumsParser.parse(text)

        assertNotNull(result)
        assertEquals(1, result!!.versionCode)
    }

    @Test
    fun `parse rejects invalid hex sha256`() {
        val text =
            """
            versionName=1.0.0
            versionCode=1
            apkSha256=too-short
            signingCertSha256=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
            """.trimIndent()

        assertNull(ReleaseChecksumsParser.parse(text))
    }

    @Test
    fun `parse rejects missing fields`() {
        assertNull(ReleaseChecksumsParser.parse("versionName=1.0.0"))
        assertNull(ReleaseChecksumsParser.parse(""))
    }

    @Test
    fun `parse rejects non-numeric versionCode`() {
        val text =
            """
            versionName=1.0.0
            versionCode=abc
            apkSha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
            signingCertSha256=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
            """.trimIndent()

        assertNull(ReleaseChecksumsParser.parse(text))
    }

    @Test
    fun `parse lowercases hex fields`() {
        val text =
            """
            versionName=1.0.0
            versionCode=1
            apkSha256=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            signingCertSha256=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB
            """.trimIndent()

        val result = ReleaseChecksumsParser.parse(text)

        assertNotNull(result)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", result!!.apkSha256)
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", result.signingCertSha256)
    }
}

class JniVerifierTest {

    @Test
    fun `hashEquals matches identical strings`() {
        assertTrue(JniVerifier.hashEquals("abc", "abc"))
    }

    @Test
    fun `hashEquals matches case-insensitively`() {
        assertTrue(JniVerifier.hashEquals("ABC", "abc"))
        assertTrue(JniVerifier.hashEquals("AbC", "aBc"))
    }

    @Test
    fun `hashEquals returns false for different values`() {
        assertFalse(JniVerifier.hashEquals("abc", "abd"))
    }

    @Test
    fun `hashEquals returns false for null`() {
        assertFalse(JniVerifier.hashEquals(null, "abc"))
        assertFalse(JniVerifier.hashEquals("abc", null))
        assertFalse(JniVerifier.hashEquals(null, null))
    }

    @Test
    fun `computeSha256 works without native library`() {
        val file = File.createTempFile("jni-test", ".tmp")
        try {
            file.writeText("test")
            val hash = JniVerifier.computeSha256(file)

            assertEquals(64, hash.length)
            assertEquals(ApkDigest.sha256Hex(file), hash)
        } finally {
            file.delete()
        }
    }
}

class ApkIntegrityVerifierTest {

    private val sampleManifest =
        OfficialReleaseManifest(
            signingCertSha256 = "cert-hash",
            releases =
                listOf(
                    ReleaseEntry(
                        versionCode = 1,
                        versionName = "1.0.0",
                        apkSha256 = "hash-v1"
                    ),
                    ReleaseEntry(
                        versionCode = 2,
                        versionName = "2.0.0",
                        apkSha256 = ""
                    )
                )
        )

    @Test
    fun `verifyApkContents hash match returns PassedFull`() {
        val result =
            ApkIntegrityVerifier.verifyApkContents(
                manifest = sampleManifest,
                versionCode = 1,
                versionName = "1.0.0",
                computedApkSha256 = "hash-v1"
            )

        assertEquals(ApkVerificationResult.PassedFull, result)
    }

    @Test
    fun `verifyApkContents hash mismatch returns HashMismatch`() {
        val result =
            ApkIntegrityVerifier.verifyApkContents(
                manifest = sampleManifest,
                versionCode = 1,
                versionName = "1.0.0",
                computedApkSha256 = "wrong-hash"
            )

        assertTrue(result is ApkVerificationResult.HashMismatch)
        assertEquals("hash-v1", (result as ApkVerificationResult.HashMismatch).expected)
        assertEquals("wrong-hash", result.actual)
    }

    @Test
    fun `verifyApkContents empty manifest hash with valid checksums returns PassedFull`() {
        val checksums = ReleaseChecksums("2.0.0", 2, "checksums-hash", "cert-hash")

        val result =
            ApkIntegrityVerifier.verifyApkContents(
                manifest = sampleManifest,
                versionCode = 2,
                versionName = "2.0.0",
                computedApkSha256 = "checksums-hash",
                checksums = checksums
            )

        assertEquals(ApkVerificationResult.PassedFull, result)
    }

    @Test
    fun `verifyApkContents checksums cert mismatch`() {
        val checksums = ReleaseChecksums("2.0.0", 2, "checksums-hash", "wrong-cert")

        val result =
            ApkIntegrityVerifier.verifyApkContents(
                manifest = sampleManifest,
                versionCode = 2,
                versionName = "2.0.0",
                computedApkSha256 = "checksums-hash",
                checksums = checksums
            )

        assertTrue(result is ApkVerificationResult.ChecksumsCertMismatch)
    }

    @Test
    fun `verifyApkContents checksums version mismatch`() {
        val checksums = ReleaseChecksums("3.0.0", 3, "checksums-hash", "cert-hash")

        val result =
            ApkIntegrityVerifier.verifyApkContents(
                manifest = sampleManifest,
                versionCode = 2,
                versionName = "2.0.0",
                computedApkSha256 = "checksums-hash",
                checksums = checksums
            )

        assertTrue(result is ApkVerificationResult.ChecksumsVersionMismatch)
    }

    @Test
    fun `verifyApkContents version not in manifest returns VersionUnknown`() {
        val result =
            ApkIntegrityVerifier.verifyApkContents(
                manifest = sampleManifest,
                versionCode = 99,
                versionName = "99.0.0",
                computedApkSha256 = "some-hash"
            )

        assertTrue(result is ApkVerificationResult.VersionUnknown)
    }

    @Test
    fun `verifyApkContents no hash no checksums returns PassedSignatureOnly`() {
        val result =
            ApkIntegrityVerifier.verifyApkContents(
                manifest = sampleManifest,
                versionCode = 2,
                versionName = "2.0.0",
                computedApkSha256 = "some-hash"
            )

        assertEquals(ApkVerificationResult.PassedSignatureOnly, result)
    }
}
