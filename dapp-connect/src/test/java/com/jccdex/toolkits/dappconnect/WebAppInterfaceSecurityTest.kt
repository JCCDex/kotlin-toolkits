package com.jccdex.toolkits.dappconnect

import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.rpc.ErrorCodes
import com.jccdex.toolkits.dappconnect.middleware.IEthMiddleware
import com.jccdex.toolkits.dappconnect.middleware.ISwtcMiddleware
import com.jccdex.toolkits.dappconnect.provider.ChainProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WebAppInterfaceSecurityTest {
    private fun buildInterface(
        eth: IEthMiddleware,
        swtc: ISwtcMiddleware,
        provider: ChainProvider?
    ): WebAppInterface {
        val iface = WebAppInterface(eth, swtc)
        provider?.let { iface.setChainProvider(it) }
        iface.setOrigin("https://safe.example")
        return iface
    }

    private fun swtcRequestAccountsJson(): String =
        JSONObject()
            .put("name", "swtc_requestAccounts")
            .put("network", "swtc")
            .put("id", "1")
            .put("nonce", "1")
            .toString()

    /** Lets the Dispatchers.IO coroutine in handleSwtcRequestAccounts finish. */
    private fun awaitIoHandler() {
        runBlocking { delay(200) }
    }

    private fun interfaceWithErrorCapture(
        eth: IEthMiddleware = mockk(relaxed = true),
        swtc: ISwtcMiddleware = mockk(relaxed = true)
    ): Pair<WebAppInterface, MutableList<Pair<Int, String>>> {
        val errors = mutableListOf<Pair<Int, String>>()
        val iface =
            object : WebAppInterface(eth, swtc) {
                override fun sendErrorResponseWithCode(
                    network: String,
                    nonce: String,
                    code: Int,
                    error: String
                ) {
                    errors.add(code to error)
                    super.sendErrorResponseWithCode(network, nonce, code, error)
                }
            }.also { it.setOrigin("https://safe.example") }
        return iface to errors
    }

    private fun postMessageJson(
        name: String,
        network: String = "swtc",
        id: String = "1",
        params: Any? = null
    ): String =
        JSONObject()
            .apply {
                put("name", name)
                put("network", network)
                put("id", id)
                put("nonce", id)
                params?.let { put("params", it) }
            }.toString()

    // ── M-D5: no silent chain switch without ChainProvider confirmation ──

    @Test
    fun swtcRequestAccounts_rejectedByUser_doesNotSwitchChain() {
        val eth = mockk<IEthMiddleware>(relaxed = true)
        val swtc = mockk<ISwtcMiddleware>(relaxed = true)
        val provider = mockk<ChainProvider>(relaxed = true)
        every { eth.currentChainType } returns MutableStateFlow(ChainType.BSC)
        coEvery { provider.requestChainSwitch(any(), any(), any()) } returns false

        val iface = buildInterface(eth, swtc, provider)
        iface.postMessage(swtcRequestAccountsJson())
        awaitIoHandler()

        verify(exactly = 0) { eth.setCurrentChainType(any()) }
        coVerify { provider.requestChainSwitch(any(), ChainType.SWTC, any()) }
    }

    @Test
    fun swtcRequestAccounts_confirmedByUser_switchesToSwtc() {
        val eth = mockk<IEthMiddleware>(relaxed = true)
        val swtc = mockk<ISwtcMiddleware>(relaxed = true)
        val provider = mockk<ChainProvider>(relaxed = true)
        every { eth.currentChainType } returns MutableStateFlow(ChainType.BSC)
        coEvery { provider.requestChainSwitch(any(), any(), any()) } returns true
        coEvery { swtc.requestAccounts(any()) } returns JSONArray()

        val iface = buildInterface(eth, swtc, provider)
        iface.postMessage(swtcRequestAccountsJson())
        awaitIoHandler()

        verify { eth.setCurrentChainType(ChainType.SWTC) }
    }

    // ── M-D6: per-origin token bucket ──

    @Test
    fun rateLimiter_allowsBudgetThenRejects_butIndependentPerKey() {
        val limiter = TokenBucketRateLimiter(60)
        repeat(60) { assertTrue(limiter.tryAcquire("origin-a")) }
        assertFalse(limiter.tryAcquire("origin-a"))
        assertTrue(limiter.tryAcquire("origin-b"))
    }

    // ── M-D2: malformed JSON payload tolerated ──

    @Test
    fun postMessage_malformedJson_isDroppedWithoutThrowing() {
        val eth = mockk<IEthMiddleware>(relaxed = true)
        val swtc = mockk<ISwtcMiddleware>(relaxed = true)
        val iface = buildInterface(eth, swtc, provider = null)
        // Invalid JSON must not throw inside the @JavascriptInterface bridge, and must not
        // dispatch any RPC handler (no name/network/nonce can be parsed).
        iface.postMessage("""not-a-json-object {{{""")
        coVerify(exactly = 0) { swtc.requestAccounts(any()) }
    }

    @Test
    fun postMessage_incompleteJsonMissingRequiredFields_isDroppedWithoutThrowing() {
        val eth = mockk<IEthMiddleware>(relaxed = true)
        val swtc = mockk<ISwtcMiddleware>(relaxed = true)
        val iface = buildInterface(eth, swtc, provider = null)
        // Valid JSON that is missing required fields (e.g. "{}" or only a name) must be dropped,
        // not thrown out of the @JavascriptInterface bridge (M-D2).
        iface.postMessage("{}")
        iface.postMessage("""{"name":"swtc_requestAccounts"}""")
        coVerify(exactly = 0) { swtc.requestAccounts(any()) }
    }

    @Test
    fun postMessage_malformedParamsShape_isRejectedWithoutThrowing() {
        val swtc = mockk<ISwtcMiddleware>(relaxed = true)
        val (iface, errors) = interfaceWithErrorCapture(swtc = swtc)
        iface.postMessage(
            postMessageJson(
                name = "swtc_sendTransaction",
                params = JSONArray().put("not-an-object")
            )
        )
        coVerify(exactly = 0) { swtc.sendTransaction(any(), any()) }
        assertTrue(errors.any { it.second.contains("Invalid transaction parameters") })
        assertEquals(ErrorCodes.INVALID_PARAMS, errors.first().first)
    }

    @Test
    fun postMessage_paramsNotArray_isRejectedWithoutThrowing() {
        val swtc = mockk<ISwtcMiddleware>(relaxed = true)
        val (iface, errors) = interfaceWithErrorCapture(swtc = swtc)
        iface.postMessage(
            postMessageJson(
                name = "swtc_signMessage",
                id = "2",
                params = "not-an-array"
            )
        )
        coVerify(exactly = 0) { swtc.signMessage(any(), any(), any()) }
        assertEquals(ErrorCodes.INVALID_PARAMS, errors.single().first)
        assertTrue(errors.single().second.contains("Invalid params"))
    }

    @Test
    fun postMessage_missingAddressForGetPublicKey_isRejectedWithoutThrowing() {
        val swtc = mockk<ISwtcMiddleware>(relaxed = true)
        val (iface, errors) = interfaceWithErrorCapture(swtc = swtc)
        iface.postMessage(
            postMessageJson(
                name = "swtc_getPublicKey",
                id = "3",
                params = JSONArray()
            )
        )
        coVerify(exactly = 0) { swtc.getPublicKey(any(), any()) }
        assertEquals(ErrorCodes.INVALID_PARAMS, errors.single().first)
        assertTrue(errors.single().second.contains("Invalid address parameter"))
    }

    @Test
    fun postMessage_swtcRequestNfts_allowsEmptyAddress() {
        val swtc = mockk<ISwtcMiddleware>(relaxed = true)
        val (iface, errors) = interfaceWithErrorCapture(swtc = swtc)
        iface.postMessage(
            postMessageJson(
                name = "swtc_requestNfts",
                id = "4",
                params = JSONArray()
            )
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun postMessage_ipfsPersonalSign_rejectsNonNumericData() {
        val swtc = mockk<ISwtcMiddleware>(relaxed = true)
        val (iface, errors) = interfaceWithErrorCapture(swtc = swtc)
        iface.postMessage(
            postMessageJson(
                name = "ipfs_personalSign",
                id = "5",
                params =
                    JSONArray()
                        .put(JSONArray().put("not-a-byte"))
                        .put("jSwtcAddress")
            )
        )
        assertEquals(ErrorCodes.INVALID_PARAMS, errors.single().first)
        assertTrue(errors.single().second.contains("Invalid ipfs_personalSign parameters"))
    }

    @Test
    fun postMessage_ipfsPersonalSign_rejectsOutOfRangeByte() {
        val swtc = mockk<ISwtcMiddleware>(relaxed = true)
        val (iface, errors) = interfaceWithErrorCapture(swtc = swtc)
        iface.postMessage(
            postMessageJson(
                name = "ipfs_personalSign",
                id = "6",
                params =
                    JSONArray()
                        .put(JSONArray().put(256))
                        .put("jSwtcAddress")
            )
        )
        assertEquals(ErrorCodes.INVALID_PARAMS, errors.single().first)
        assertTrue(errors.single().second.contains("Invalid ipfs_personalSign parameters"))
    }
}
