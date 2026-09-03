package com.jccdex.toolkits.apkverify

import android.content.pm.PackageInfo
import android.content.pm.SigningInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ApkSigningFingerprintTest {
    private fun createSignature(): android.content.pm.Signature {
        // Use a real certificate-like byte array
        val certBytes = byteArrayOf(
            0x30.toByte(), 0x0D.toByte(), 0x06.toByte(), 0x09.toByte(),
            0x60.toByte(), 0x86.toByte(), 0x48.toByte(), 0x01.toByte(),
            0x65.toByte(), 0x03.toByte(), 0x04.toByte(), 0x02.toByte(),
            0x01.toByte(), 0x05.toByte(), 0x00.toByte()
        )
        return android.content.pm.Signature(certBytes)
    }

    private fun createPackageInfo(signers: Array<android.content.pm.Signature>): PackageInfo {
        val info = PackageInfo()
        val signingInfo = mockk<SigningInfo>()
        every { signingInfo.apkContentsSigners } returns signers
        info.signingInfo = signingInfo
        return info
    }

    @Test
    fun `certSha256FromInfo returns fingerprint for single signer`() {
        val sig = createSignature()
        val info = createPackageInfo(arrayOf(sig))

        val result = ApkSigningFingerprint.certSha256FromInfo(info)

        // Should return a 64-char hex string
        assertTrue(result != null)
        assertTrue(result!!.length == 64)
    }

    @Test
    fun `certSha256FromInfo returns first signer fingerprint for multiple signers`() {
        val sig1 = createSignature()
        val sig2 = createSignature()
        val info = createPackageInfo(arrayOf(sig1, sig2))

        val result = ApkSigningFingerprint.certSha256FromInfo(info)

        assertTrue(result != null)
        assertTrue(result!!.length == 64)
    }

    @Test
    fun `allCertSha256FromInfo returns all fingerprints for multiple signers`() {
        val sig1 = createSignature()
        val sig2 = createSignature()
        val info = createPackageInfo(arrayOf(sig1, sig2))

        val result = ApkSigningFingerprint.allCertSha256FromInfo(info)

        assertEquals(2, result.size)
        result.forEach { assertTrue(it.length == 64) }
    }

    @Test
    fun `allCertSha256FromInfo returns empty list for null signingInfo`() {
        val info = PackageInfo()

        val result = ApkSigningFingerprint.allCertSha256FromInfo(info)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `matchesAnySigner returns true when fingerprint matches`() {
        val sig = createSignature()
        val info = createPackageInfo(arrayOf(sig))
        val fingerprint = ApkSigningFingerprint.certSha256FromInfo(info)!!

        val result = ApkSigningFingerprint.matchesAnySigner(info, fingerprint)

        assertTrue(result)
    }

    @Test
    fun `matchesAnySigner returns false when no signer matches`() {
        val sig = createSignature()
        val info = createPackageInfo(arrayOf(sig))
        val unknownFingerprint = "a".repeat(64)

        val result = ApkSigningFingerprint.matchesAnySigner(info, unknownFingerprint)

        assertFalse(result)
    }

    @Test
    fun `matchesAnySigner is case insensitive`() {
        val sig = createSignature()
        val info = createPackageInfo(arrayOf(sig))
        val fingerprint = ApkSigningFingerprint.certSha256FromInfo(info)!!
        val upperFingerprint = fingerprint.uppercase()

        val result = ApkSigningFingerprint.matchesAnySigner(info, upperFingerprint)

        assertTrue(result)
    }

    @Test
    fun `matchesAnySigner returns true for second signer`() {
        val sig1 = createSignature()
        val sig2 = createSignature()
        val info1 = createPackageInfo(arrayOf(sig1))
        val info2 = createPackageInfo(arrayOf(sig1, sig2))
        val fingerprint2 = ApkSigningFingerprint.allCertSha256FromInfo(info2)[1]

        val result = ApkSigningFingerprint.matchesAnySigner(info2, fingerprint2)

        assertTrue(result)
    }
}