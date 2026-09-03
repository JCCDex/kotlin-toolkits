package com.jccdex.toolkits.appupdate

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppUpdateApkInstallerTest {
    // ── M-7：下载文件名路径穿越 ──

    @Test
    fun `safeApkFileName accepts safe names`() {
        assertEquals("prefix-v1.2.3.apk", AppUpdateApkInstaller.safeApkFileName("prefix", "1.2.3"))
        assertEquals("a-vb_c.d-e.apk", AppUpdateApkInstaller.safeApkFileName("a", "b_c.d-e"))
    }

    @Test
    fun `safeApkFileName rejects path traversal and empty names`() {
        assertNull(AppUpdateApkInstaller.safeApkFileName("prefix", "../evil"))
        assertNull(AppUpdateApkInstaller.safeApkFileName("prefix", "1.0/../x"))
        assertNull(AppUpdateApkInstaller.safeApkFileName("prefix", "1.0\\x"))
        assertNull(AppUpdateApkInstaller.safeApkFileName("../prefix", "1.0"))
        assertNull(AppUpdateApkInstaller.safeApkFileName("prefix", ""))
        assertNull(AppUpdateApkInstaller.safeApkFileName("", "1.0"))
    }

    // ── M-W5：startInstall 权限门 ──

    @Test
    fun `canRequestInstall reflects PackageManager flag`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowPm = Shadows.shadowOf(context.packageManager)

        shadowPm.setCanRequestPackageInstalls(false)
        assertFalse(AppUpdateApkInstaller.canRequestInstall(context))

        shadowPm.setCanRequestPackageInstalls(true)
        assertTrue(AppUpdateApkInstaller.canRequestInstall(context))
    }

    @Test
    fun `startInstall returns false when install permission denied`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app.packageManager).setCanRequestPackageInstalls(false)

        val apk = File.createTempFile("mw5_", ".apk", app.cacheDir)
        try {
            assertFalse(AppUpdateApkInstaller.startInstall(app, apk))
            assertNull(Shadows.shadowOf(app).nextStartedActivity)
        } finally {
            apk.delete()
        }
    }
}
