package com.jccdex.toolkits.dappconnect.host

import android.net.Uri
import android.webkit.WebResourceRequest
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C181 守卫:页面内导航只放行 **http/https**(经 `DAppConnectSdk.isSafeUrl`,与 iOS host 的
 * `decidePolicyFor` 同源),其余 scheme(`intent:` / `file:` / `tel:` / `javascript:` …)一律拦下 ——
 * 避免 DApp 页内跳转把用户带出应用内浏览器。
 *
 * 注意语义:`shouldOverrideUrlLoading` 返回 **true = 拦下(不加载)**,**false = 放行**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DappWebViewHostNavigationTest {
    private fun host(): DappWebViewHost =
        DappWebViewHost(
            RuntimeEnvironment.getApplication(),
            DappWebViewHostConfig(
                ethMiddleware = mockk(relaxed = true),
                swtcMiddleware = mockk(relaxed = true),
                accountProvider = mockk(relaxed = true),
                secretProvider = mockk(relaxed = true),
                nftProvider = mockk(relaxed = true),
                providerJs = ""
            )
        )

    private fun request(rawUrl: String): WebResourceRequest =
        mockk {
            every { url } returns Uri.parse(rawUrl)
        }

    @Test
    fun httpAndHttpsNavigationsAreAllowed() {
        val host = host()
        val client = host.webView.webViewClient
        assertFalse(client.shouldOverrideUrlLoading(host.webView, request("https://dapp.example.com/page")))
        assertFalse(client.shouldOverrideUrlLoading(host.webView, request("http://dapp.example.com")))
    }

    @Test
    fun nonHttpSchemesAndNullAreBlocked() {
        val host = host()
        val client = host.webView.webViewClient
        assertTrue(client.shouldOverrideUrlLoading(host.webView, request("intent://scan/#Intent;scheme=zxing;end")))
        assertTrue(client.shouldOverrideUrlLoading(host.webView, request("file:///etc/hosts")))
        assertTrue(client.shouldOverrideUrlLoading(host.webView, request("tel:+8613800000000")))
        assertTrue(client.shouldOverrideUrlLoading(host.webView, request("javascript:alert(1)")))
        assertTrue(client.shouldOverrideUrlLoading(host.webView, null as WebResourceRequest?))
    }
}
