package com.jccdex.toolkits.dappconnect.middleware

import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.WalletAccount
import com.jccdex.toolkits.dappconnect.model.UserRejectedException
import com.jccdex.toolkits.dappconnect.provider.AccountProvider
import com.jccdex.toolkits.dappconnect.provider.NodeProvider
import com.jccdex.toolkits.dappconnect.provider.SecretProvider
import com.jccdex.toolkits.wallet.sdk.WalletSdk
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SwtcMiddlewareTest {
    private class SpySecretProvider : SecretProvider {
        var lastOrigin: String? = null
        var lastAddress: String? = null

        override suspend fun getPrivateKeyForAddress(
            address: String,
            origin: String
        ): String? {
            lastAddress = address
            lastOrigin = origin
            return null
        }

        override suspend fun getSecretForAddress(
            address: String,
            origin: String
        ): String? {
            lastAddress = address
            lastOrigin = origin
            return "sec-$address"
        }
    }

    private class StubAccountProvider(
        private val accountsList: List<WalletAccount> = emptyList()
    ) : AccountProvider {
        override val accounts: Flow<List<WalletAccount>> = flowOf(accountsList)

        override fun getAccountsByChain(chain: ChainType): Flow<List<WalletAccount>> = flowOf(accountsList)

        override val currentAccount: Flow<WalletAccount?> = flowOf(null)

        override suspend fun getAccountByAddress(address: String): WalletAccount? =
            accountsList.find { it.address == address }

        override suspend fun setCurrentAccount(accountId: String) = Unit

        override suspend fun getAccountName(address: String): String? = null
    }

    private class StubNodeProvider : NodeProvider {
        override suspend fun getRpcUrl(chain: ChainType): String = "https://rpc.test"

        override suspend fun getBlockNumber(chain: ChainType): String = "0x1"

        override suspend fun getTransactionCount(
            address: String,
            chain: ChainType
        ): String = "0x0"

        override suspend fun getGasPrice(chain: ChainType): String = "0x1"

        override suspend fun getMaxPriorityFeePerGas(chain: ChainType): String = "0x1"

        override suspend fun estimateGas(
            txParams: JSONObject,
            chain: ChainType
        ): String = "0x5208"

        override suspend fun broadcastTransaction(
            signedTx: String,
            chain: ChainType
        ): String = "0xhash"

        override suspend fun sendRawTransaction(signedBlob: String): String = "0xhash"

        override suspend fun fetchSequence(address: String): Long = 1
    }

    // ── M-18 / H-02: origin passing ──

    @Test
    fun `requestAccounts filters swtc accounts excluding hd roots`() =
        runTest {
            val swtcAccount =
                WalletAccount(
                    id = "swtc-1",
                    address = "jSwtcAddress",
                    chain = ChainType.SWTC,
                    name = "swtc",
                    isHD = false,
                    parentId = null,
                    path = null,
                    publicKey = "pub"
                )
            val middleware =
                SwtcMiddleware(
                    StubAccountProvider(listOf(swtcAccount)),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            middleware.setRequestAccountsCallback { true }

            val result = middleware.requestAccounts("https://dapp.example.com")

            assertEquals(1, result.length())
            assertEquals("jSwtcAddress", result.getString(0))
        }

    @Test
    fun `requestAccounts throws when no callback set`() =
        runTest {
            val middleware =
                SwtcMiddleware(
                    StubAccountProvider(),
                    SpySecretProvider(),
                    StubNodeProvider()
                )

            assertFailsWith<UserRejectedException> {
                middleware.requestAccounts("https://dapp.example.com")
            }
        }

    @Test
    fun `multiSign throws IllegalArgumentException when tx missing`() =
        runTest {
            val middleware =
                SwtcMiddleware(
                    StubAccountProvider(listOf(swtcAccount())),
                    SpySecretProvider(),
                    StubNodeProvider()
                ).also { it.setTransactionConfirmCallback { true } }
            val msParams =
                JSONObject().apply {
                    put("account", "jSwtcAddress")
                    put("tx", "not-an-object")
                }

            val error =
                assertFailsWith<IllegalArgumentException> {
                    middleware.multiSign(msParams, "https://dapp.example.com")
                }
            assertTrue(error.message!!.contains("tx"))
        }

    @Test
    fun `multiSign throws IllegalArgumentException when account blank`() =
        runTest {
            val middleware =
                SwtcMiddleware(
                    StubAccountProvider(listOf(swtcAccount())),
                    SpySecretProvider(),
                    StubNodeProvider()
                ).also { it.setTransactionConfirmCallback { true } }
            val msParams =
                JSONObject().apply {
                    put("account", "  ")
                    put("tx", JSONObject())
                }

            val error =
                assertFailsWith<IllegalArgumentException> {
                    middleware.multiSign(msParams, "https://dapp.example.com")
                }
            assertTrue(error.message!!.contains("account"))
        }

    @Test
    fun `multiSign throws IllegalArgumentException when account missing`() =
        runTest {
            val middleware =
                SwtcMiddleware(
                    StubAccountProvider(listOf(swtcAccount())),
                    SpySecretProvider(),
                    StubNodeProvider()
                ).also { it.setTransactionConfirmCallback { true } }
            val msParams = JSONObject().put("tx", JSONObject())

            val error =
                assertFailsWith<IllegalArgumentException> {
                    middleware.multiSign(msParams, "https://dapp.example.com")
                }
            assertTrue(error.message!!.contains("account"))
        }

    @Test
    fun `multiSign throws UserRejectedException when no callback set`() =
        runTest {
            val middleware =
                SwtcMiddleware(
                    StubAccountProvider(listOf(swtcAccount())),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            val msParams =
                JSONObject().apply {
                    put("account", "jSwtcAddress")
                    put("tx", JSONObject().put("TransactionType", "Payment"))
                }

            assertFailsWith<UserRejectedException> {
                middleware.multiSign(msParams, "https://dapp.example.com")
            }
        }

    @Test
    fun `multiSign throws UserRejectedException when callback rejects`() =
        runTest {
            val middleware =
                SwtcMiddleware(
                    StubAccountProvider(listOf(swtcAccount())),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            middleware.setTransactionConfirmCallback { false }
            val msParams =
                JSONObject().apply {
                    put("account", "jSwtcAddress")
                    put("tx", JSONObject().put("TransactionType", "Payment"))
                }

            assertFailsWith<UserRejectedException> {
                middleware.multiSign(msParams, "https://dapp.example.com")
            }
        }

    // ── batchTransactions ──

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun swtcAccount() =
        WalletAccount(
            id = "swtc-1",
            address = "jSwtcAddress",
            chain = ChainType.SWTC,
            name = "swtc",
            isHD = false,
            parentId = null,
            path = null,
            publicKey = "pub"
        )

    private fun mockWalletSdk() {
        mockkObject(WalletSdk)
        coEvery { WalletSdk.isValidAddress(any()) } returns true
        coEvery { WalletSdk.signTransaction(any(), any()) } returns "blob123"
        val fakeTx =
            """{"Account":"jSwtcAddress","TransactionType":"Payment","Destination":"jDest","Amount":"1","Fee":"0.01"}"""
        coEvery {
            WalletSdk.buildSwtcCreateOrder(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns fakeTx
        coEvery { WalletSdk.buildSwtcCancelOrder(any(), any()) } returns fakeTx
    }

    private fun batchMiddleware() =
        SwtcMiddleware(
            StubAccountProvider(listOf(swtcAccount())),
            SpySecretProvider(),
            StubNodeProvider()
        ).also { it.setTransactionConfirmCallback { true } } // Auto-approve for tests

    private fun batchReq(
        mode: String? = null,
        transfers: JSONArray? = null,
        createOrders: JSONArray? = null,
        cancelOrders: JSONArray? = null
    ) = JSONObject().apply {
        put("from", "jSwtcAddress")
        mode?.let { put("mode", it) }
        transfers?.let { put("transfers", it) }
        createOrders?.let { put("createOrders", it) }
        cancelOrders?.let { put("cancelOrders", it) }
    }

    private fun transfer(
        to: String = "jDest",
        amount: String = "1",
        currency: String = "SWT"
    ) = JSONObject().apply {
        put("to", to)
        put("amount", amount)
        put("currency", currency)
    }

    // ── M-4/M-D4: TransactionConfirmCallback failure scenarios ──

    @Test
    fun `sendTransaction throws UserRejectedException when no callback set`() =
        runTest {
            mockWalletSdk()
            val middleware =
                SwtcMiddleware(
                    StubAccountProvider(listOf(swtcAccount())),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            val tx =
                JSONObject().apply {
                    put("Account", "jSwtcAddress")
                    put("Destination", "jDest")
                    put("Amount", "1")
                }

            assertFailsWith<UserRejectedException> {
                middleware.sendTransaction(tx, "https://dapp.example.com")
            }
        }

    @Test
    fun `sendTransaction throws UserRejectedException when callback rejects`() =
        runTest {
            mockWalletSdk()
            val middleware =
                SwtcMiddleware(
                    StubAccountProvider(listOf(swtcAccount())),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            middleware.setTransactionConfirmCallback { false }
            val tx =
                JSONObject().apply {
                    put("Account", "jSwtcAddress")
                    put("Destination", "jDest")
                    put("Amount", "1")
                }

            assertFailsWith<UserRejectedException> {
                middleware.sendTransaction(tx, "https://dapp.example.com")
            }
        }

    @Test
    fun `signMessage throws UserRejectedException when no callback set`() =
        runTest {
            val middleware =
                SwtcMiddleware(
                    StubAccountProvider(listOf(swtcAccount())),
                    SpySecretProvider(),
                    StubNodeProvider()
                )

            assertFailsWith<UserRejectedException> {
                middleware.signMessage("jSwtcAddress", "test message", "https://dapp.example.com")
            }
        }

    @Test
    fun `signMessage throws UserRejectedException when callback rejects`() =
        runTest {
            val middleware =
                SwtcMiddleware(
                    StubAccountProvider(listOf(swtcAccount())),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            middleware.setTransactionConfirmCallback { false }

            assertFailsWith<UserRejectedException> {
                middleware.signMessage("jSwtcAddress", "test message", "https://dapp.example.com")
            }
        }

    @Test
    fun `signMessage passes correct TransactionRequest to callback`() =
        runTest {
            var capturedRequest: TransactionRequest? = null
            val middleware =
                SwtcMiddleware(
                    StubAccountProvider(listOf(swtcAccount())),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            middleware.setTransactionConfirmCallback { request ->
                capturedRequest = request
                true
            }

            runCatching { middleware.signMessage("jSwtcAddress", "test message", "https://dapp.example.com") }

            assertTrue(capturedRequest is TransactionRequest.SignMessage)
            val signRequest = capturedRequest as TransactionRequest.SignMessage
            assertEquals("test message", signRequest.message)
            assertEquals(ChainType.SWTC, signRequest.chain)
            assertEquals("https://dapp.example.com", signRequest.origin)
            assertEquals(SignType.SIGN_MESSAGE, signRequest.type)
        }

    @Test
    fun `batchTransactions throws UserRejectedException when no callback set`() =
        runTest {
            mockWalletSdk()
            val middleware =
                SwtcMiddleware(
                    StubAccountProvider(listOf(swtcAccount())),
                    SpySecretProvider(),
                    StubNodeProvider()
                )

            assertFailsWith<UserRejectedException> {
                middleware.batchTransactions(
                    batchReq(transfers = JSONArray().apply { put(transfer()) }),
                    "https://dapp.example.com"
                )
            }
        }

    @Test
    fun `batchTransactions throws UserRejectedException when callback rejects`() =
        runTest {
            mockWalletSdk()
            val middleware =
                SwtcMiddleware(
                    StubAccountProvider(listOf(swtcAccount())),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            middleware.setTransactionConfirmCallback { false }

            assertFailsWith<UserRejectedException> {
                middleware.batchTransactions(
                    batchReq(transfers = JSONArray().apply { put(transfer()) }),
                    "https://dapp.example.com"
                )
            }
        }

    @Test
    fun `batchTransactions passes Batch request with total amount to callback`() =
        runTest {
            mockWalletSdk()
            var capturedRequest: TransactionRequest? = null
            val middleware =
                SwtcMiddleware(
                    StubAccountProvider(listOf(swtcAccount())),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            middleware.setTransactionConfirmCallback { request ->
                capturedRequest = request
                true
            }

            runCatching {
                middleware.batchTransactions(
                    batchReq(
                        transfers =
                            JSONArray().apply {
                                put(transfer(amount = "10"))
                                put(transfer(amount = "20"))
                            }
                    ),
                    "https://dapp.example.com"
                )
            }

            assertTrue(capturedRequest is TransactionRequest.SwtcBatchTransaction)
            val batchRequest = capturedRequest as TransactionRequest.SwtcBatchTransaction
            assertEquals(ChainType.SWTC, batchRequest.chain)
            assertEquals("https://dapp.example.com", batchRequest.origin)
            assertEquals(2, batchRequest.totalCount)
            assertEquals("30", batchRequest.totalAmount)
        }

    @Test
    fun `batchTransactions send mode submits and returns hashes`() =
        runTest {
            mockWalletSdk()
            val middleware = batchMiddleware()

            val result =
                middleware.batchTransactions(
                    batchReq(transfers = JSONArray().apply { put(transfer()) }),
                    "https://dapp.example.com"
                )

            assertEquals(1, result.length())
            assertEquals("0xhash", result.getJSONObject(0).getString("hash"))
        }

    @Test
    fun `batchTransactions return mode returns blobs without broadcasting`() =
        runTest {
            mockWalletSdk()
            val middleware = batchMiddleware()

            val result =
                middleware.batchTransactions(
                    batchReq(mode = "return", transfers = JSONArray().apply { put(transfer()) }),
                    "https://dapp.example.com"
                )

            assertEquals(1, result.length())
            assertEquals("blob123", result.getString(0))
        }

    @Test
    fun `batchTransactions rejects unknown fields`() =
        runTest {
            val middleware = batchMiddleware()

            assertFailsWith<IllegalArgumentException> {
                middleware.batchTransactions(
                    batchReq(
                        transfers =
                            JSONArray().apply {
                                put(
                                    JSONObject().apply {
                                        put("to", "jDest")
                                        put("amount", "1")
                                        put("unknown", "x")
                                    }
                                )
                            }
                    ),
                    "https://dapp.example.com"
                )
            }
        }

    @Test
    fun `batchTransactions rejects unknown mode`() =
        runTest {
            val middleware = batchMiddleware()

            assertFailsWith<IllegalArgumentException> {
                middleware.batchTransactions(batchReq(mode = "bad"), "https://dapp.example.com")
            }
        }

    @Test
    fun `batchTransactions rejects empty request`() =
        runTest {
            val middleware = batchMiddleware()

            assertFailsWith<IllegalArgumentException> {
                middleware.batchTransactions(batchReq(), "https://dapp.example.com")
            }
        }

    @Test
    fun `batchTransactions rejects invalid amount`() =
        runTest {
            val middleware = batchMiddleware()

            assertFailsWith<IllegalArgumentException> {
                middleware.batchTransactions(
                    batchReq(transfers = JSONArray().apply { put(transfer(amount = "-5")) }),
                    "https://dapp.example.com"
                )
            }
        }

    @Test
    fun `batchTransactions rejects oversized batch`() =
        runTest {
            val middleware = batchMiddleware()

            assertFailsWith<IllegalArgumentException> {
                middleware.batchTransactions(
                    batchReq(
                        transfers =
                            JSONArray().apply {
                                repeat(51) { put(transfer()) }
                            }
                    ),
                    "https://dapp.example.com"
                )
            }
        }

    @Test
    fun `batchTransactions rejects total amount over limit`() =
        runTest {
            mockWalletSdk()
            val middleware = batchMiddleware()

            assertFailsWith<IllegalArgumentException> {
                middleware.batchTransactions(
                    batchReq(
                        transfers =
                            JSONArray().apply {
                                // Each transfer is under the per-tx cap (1e12), but the batch total
                                // (1.2e12) exceeds MAX_BATCH_TOTAL_AMOUNT (1e12) → M-D8 total check.
                                put(transfer(amount = "600000000000"))
                                put(transfer(amount = "600000000000"))
                            }
                    ),
                    "https://dapp.example.com"
                )
            }
        }
}
