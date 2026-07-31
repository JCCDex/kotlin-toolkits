package com.jccdex.toolkits.appupdate

import com.jccdex.toolkits.apkverify.ReleaseChecksums
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateCheckerTest {

    private val sampleChecksums =
        ReleaseChecksums(
            versionName = "2.0.0",
            versionCode = 2,
            apkSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            signingCertSha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        )

    @Test
    fun `evaluate returns UpdateAvailable when remote is newer`() {
        val result = AppUpdateChecker.evaluate(1, sampleChecksums, "https://example.com/app.apk")

        assertTrue(result is AppUpdateCheckResult.UpdateAvailable)
        val update = result as AppUpdateCheckResult.UpdateAvailable
        assertEquals(sampleChecksums, update.remote)
        assertEquals("https://example.com/app.apk", update.apkDownloadUrl)
    }

    @Test
    fun `evaluate returns AlreadyLatest when local equals remote`() {
        val result = AppUpdateChecker.evaluate(2, sampleChecksums, "https://example.com/app.apk")

        assertTrue(result is AppUpdateCheckResult.AlreadyLatest)
    }

    @Test
    fun `evaluate returns AlreadyLatest when local is newer`() {
        val result = AppUpdateChecker.evaluate(3, sampleChecksums, "https://example.com/app.apk")

        assertTrue(result is AppUpdateCheckResult.AlreadyLatest)
    }

    @Test
    fun `evaluate with remote versionCode zero returns AlreadyLatest for local one`() {
        val zeroVersion = sampleChecksums.copy(versionCode = 0)

        val result = AppUpdateChecker.evaluate(1, zeroVersion, "https://example.com/app.apk")

        assertTrue(result is AppUpdateCheckResult.AlreadyLatest)
    }
}

class AppUpdateCheckThrottleTest {

    @Test
    fun `shouldCheck returns true when force is set`() {
        assertTrue(AppUpdateCheckThrottle.shouldCheck(nowMs = 1000, lastCheckMs = 1000, force = true))
        assertTrue(AppUpdateCheckThrottle.shouldCheck(nowMs = 1000, lastCheckMs = 900, force = true))
    }

    @Test
    fun `shouldCheck returns true when never checked before`() {
        assertTrue(AppUpdateCheckThrottle.shouldCheck(nowMs = 1000, lastCheckMs = 0, force = false))
        assertTrue(AppUpdateCheckThrottle.shouldCheck(nowMs = 1000, lastCheckMs = -1, force = false))
    }

    @Test
    fun `shouldCheck returns false within interval`() {
        assertFalse(
            AppUpdateCheckThrottle.shouldCheck(
                nowMs = 1000,
                lastCheckMs = 500,
                intervalMs = 1000,
                force = false
            )
        )
    }

    @Test
    fun `shouldCheck returns true after interval elapsed`() {
        assertTrue(
            AppUpdateCheckThrottle.shouldCheck(
                nowMs = 2000,
                lastCheckMs = 500,
                intervalMs = 1000,
                force = false
            )
        )
    }

    @Test
    fun `shouldCheck returns true exactly at interval boundary`() {
        assertTrue(
            AppUpdateCheckThrottle.shouldCheck(
                nowMs = 1500,
                lastCheckMs = 500,
                intervalMs = 1000,
                force = false
            )
        )
    }

    @Test
    fun `shouldCheck uses default interval when not specified`() {
        val result =
            AppUpdateCheckThrottle.shouldCheck(
                nowMs = AppUpdateCheckThrottle.DEFAULT_INTERVAL_MS,
                lastCheckMs = 0,
                force = false
            )

        assertTrue(result)
    }
}
