package com.jccdex.toolkits.dappconnect.middleware

import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.WalletAccount
import com.jccdex.toolkits.dappconnect.model.UserRejectedException
import com.jccdex.toolkits.dappconnect.provider.AccountProvider
import com.jccdex.toolkits.dappconnect.provider.NodeProvider
import com.jccdex.toolkits.dappconnect.provider.SecretProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class EthMiddlewareTest {
    // ── test doubles ──

    private class SpySecretProvider : SecretProvider {
        var lastOrigin: String? = null
        var lastAddress: String? = null

        override suspend fun getPrivateKeyForAddress(
            address: String,
            origin: String
        ): String? {
            lastAddress = address
            lastOrigin = origin
            return "privkey-$address"
        }

        override suspend fun getSecretForAddress(
            address: String,
            origin: String
        ): String? {
            lastAddress = address
            lastOrigin = origin
            return null
        }
    }

    private class StubAccountProvider(
        private val accountsList: List<WalletAccount> = emptyList()
    ) : AccountProvider {
        private val _accounts = MutableStateFlow(accountsList)
        override val accounts: Flow<List<WalletAccount>> = _accounts.asStateFlow()

        override fun getAccountsByChain(chain: ChainType): Flow<List<WalletAccount>> = flowOf(accountsList)

        override val currentAccount: Flow<WalletAccount?> = flowOf(null)

        override suspend fun getAccountByAddress(address: String): WalletAccount? =
            accountsList.find { it.address.equals(address, ignoreCase = true) }

        override suspend fun setCurrentAccount(accountId: String) = Unit

        override suspend fun getAccountName(address: String): String? = null
    }

    private open class StubNodeProvider : NodeProvider {
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

    private val testAccount =
        WalletAccount(
            id = "acc-1",
            address = "0xabc",
            chain = ChainType.BSC,
            name = "test",
            isHD = false,
            parentId = null,
            path = null,
            publicKey = "pub"
        )

    // ── M-06: requestAccounts callback ──

    @Test
    fun `requestAccounts throws when no callback set`() =
        runTest {
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider()
                )

            assertFailsWith<UserRejectedException> {
                middleware.requestAccounts("https://dapp.example.com")
            }
        }

    @Test
    fun `requestAccounts succeeds when callback approves`() =
        runTest {
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            middleware.setRequestAccountsCallback { true }

            val result = middleware.requestAccounts("https://dapp.example.com")

            assertEquals(1, result.length())
            assertEquals("0xabc", result.getString(0))
        }

    @Test
    fun `requestAccounts throws when callback rejects`() =
        runTest {
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            middleware.setRequestAccountsCallback { false }

            val ex =
                assertFailsWith<UserRejectedException> {
                    middleware.requestAccounts("https://dapp.example.com")
                }
            assertTrue(ex.message!!.contains("requestAccounts"))
        }

    @Test
    fun `requestAccounts passes origin to callback`() =
        runTest {
            var capturedOrigin: String? = null
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            middleware.setRequestAccountsCallback { origin ->
                capturedOrigin = origin
                true
            }

            middleware.requestAccounts("https://trusted.dapp")

            assertEquals("https://trusted.dapp", capturedOrigin)
        }

    @Test
    fun `sendTransaction passes origin to secret provider`() =
        runTest {
            val spy = SpySecretProvider()
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    spy,
                    StubNodeProvider(),
                    initialChain = ChainType.BSC
                )
            middleware.setTransactionConfirmCallback { true } // Auto-approve for test
            // Avoid WalletSdk JS bridge: force failure after origin is captured via getPrivateKey
            val tx =
                JSONObject().apply {
                    put("from", "0xabc")
                    put("to", "0xdef")
                    put("value", "0x0")
                    put("nonce", "0x0")
                    put("gasPrice", "0x1")
                    put("gas", "0x5208")
                    put("chainId", "0x38")
                }

            runCatching { middleware.sendTransaction(tx, "https://dapp.example.com") }

            assertEquals("https://dapp.example.com", spy.lastOrigin)
            assertEquals("0xabc", spy.lastAddress)
        }

    @Test
    fun `sendTransaction rejects blank origin`() =
        runTest {
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            val tx =
                JSONObject().apply {
                    put("from", "0xabc")
                    put("to", "0xdef")
                    put("value", "0x0")
                }

            assertFailsWith<IllegalArgumentException> {
                middleware.sendTransaction(tx, "  ")
            }
        }

    @Test
    fun `requestAccounts skips hd root accounts`() =
        runTest {
            val root =
                testAccount.copy(id = "root", address = "0xroot", isHD = true, parentId = null)
            val child =
                testAccount.copy(id = "child", address = "0xchild", isHD = true, parentId = "root")
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(root, child)),
                    SpySecretProvider(),
                    StubNodeProvider(),
                    initialChain = ChainType.BSC
                )
            middleware.setRequestAccountsCallback { true }

            val result = middleware.requestAccounts("https://dapp.example.com")

            assertEquals(1, result.length())
            assertEquals("0xchild", result.getString(0))
        }

    // ── M-4/M-D4: TransactionConfirmCallback failure scenarios ──

    @Test
    fun `sendTransaction throws UserRejectedException when no callback set`() =
        runTest {
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider(),
                    initialChain = ChainType.BSC
                )
            val tx =
                JSONObject().apply {
                    put("from", "0xabc")
                    put("to", "0xdef")
                    put("value", "0x0")
                }

            assertFailsWith<UserRejectedException> {
                middleware.sendTransaction(tx, "https://dapp.example.com")
            }
        }

    @Test
    fun `sendTransaction throws UserRejectedException when callback rejects`() =
        runTest {
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider(),
                    initialChain = ChainType.BSC
                )
            middleware.setTransactionConfirmCallback { false }
            val tx =
                JSONObject().apply {
                    put("from", "0xabc")
                    put("to", "0xdef")
                    put("value", "0x0")
                }

            assertFailsWith<UserRejectedException> {
                middleware.sendTransaction(tx, "https://dapp.example.com")
            }
        }

    @Test
    fun `personalSign throws UserRejectedException when no callback set`() =
        runTest {
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider()
                )

            assertFailsWith<UserRejectedException> {
                middleware.personalSign("0xabc", "test message", "https://dapp.example.com")
            }
        }

    @Test
    fun `personalSign throws UserRejectedException when callback rejects`() =
        runTest {
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            middleware.setTransactionConfirmCallback { false }

            assertFailsWith<UserRejectedException> {
                middleware.personalSign("0xabc", "test message", "https://dapp.example.com")
            }
        }

    @Test
    fun `personalSign passes correct TransactionRequest to callback`() =
        runTest {
            var capturedRequest: TransactionRequest? = null
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            middleware.setTransactionConfirmCallback { request ->
                capturedRequest = request
                true
            }

            runCatching { middleware.personalSign("0xabc", "test message", "https://dapp.example.com") }

            assertTrue(capturedRequest is TransactionRequest.SignMessage)
            val signRequest = capturedRequest as TransactionRequest.SignMessage
            assertEquals("test message", signRequest.message)
            assertEquals(ChainType.BSC, signRequest.chain)
            assertEquals("https://dapp.example.com", signRequest.origin)
            assertEquals(SignType.PERSONAL_SIGN, signRequest.type)
        }

    @Test
    fun `signTransaction throws UserRejectedException when no callback set`() =
        runTest {
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider(),
                    initialChain = ChainType.BSC
                )
            val tx =
                JSONObject().apply {
                    put("from", "0xabc")
                    put("to", "0xdef")
                    put("value", "0x0")
                }

            assertFailsWith<UserRejectedException> {
                middleware.signTransaction(tx, "https://dapp.example.com")
            }
        }

    @Test
    fun `signTransaction throws UserRejectedException when callback rejects`() =
        runTest {
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider(),
                    initialChain = ChainType.BSC
                )
            middleware.setTransactionConfirmCallback { false }
            val tx =
                JSONObject().apply {
                    put("from", "0xabc")
                    put("to", "0xdef")
                    put("value", "0x0")
                }

            assertFailsWith<UserRejectedException> {
                middleware.signTransaction(tx, "https://dapp.example.com")
            }
        }

    @Test
    fun `signTransaction passes correct TransactionRequest to callback`() =
        runTest {
            var capturedRequest: TransactionRequest? = null
            val throwingNode =
                object : StubNodeProvider() {
                    override suspend fun estimateGas(
                        txParams: JSONObject,
                        chain: ChainType
                    ): String = throw RuntimeException("estimate failed")
                }
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    throwingNode,
                    initialChain = ChainType.BSC
                )
            middleware.setTransactionConfirmCallback { request ->
                capturedRequest = request
                true
            }
            val tx =
                JSONObject().apply {
                    put("from", "0xabc")
                    put("to", "0xdef")
                    put("value", "0x1")
                }

            runCatching { middleware.signTransaction(tx, "https://dapp.example.com") }

            assertTrue(capturedRequest is TransactionRequest.SendTransaction)
            val sendRequest = capturedRequest as TransactionRequest.SendTransaction
            assertEquals("0xdef", sendRequest.to)
            assertEquals("0x1", sendRequest.value)
            assertEquals(ChainType.BSC, sendRequest.chain)
            assertEquals("https://dapp.example.com", sendRequest.origin)
        }

    // ── M-4/M-D4: getEncryptionPublicKey, decrypt, signTypedData ──

    @Test
    fun `getEncryptionPublicKey throws UserRejectedException when no callback set`() =
        runTest {
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider()
                )

            assertFailsWith<UserRejectedException> {
                middleware.getEncryptionPublicKey("0xabc", "https://dapp.example.com")
            }
        }

    @Test
    fun `getEncryptionPublicKey throws UserRejectedException when callback rejects`() =
        runTest {
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            middleware.setTransactionConfirmCallback { false }

            assertFailsWith<UserRejectedException> {
                middleware.getEncryptionPublicKey("0xabc", "https://dapp.example.com")
            }
        }

    @Test
    fun `getEncryptionPublicKey passes correct TransactionRequest to callback`() =
        runTest {
            var capturedRequest: TransactionRequest? = null
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider(),
                    initialChain = ChainType.BSC
                )
            middleware.setTransactionConfirmCallback { request ->
                capturedRequest = request
                true
            }

            runCatching { middleware.getEncryptionPublicKey("0xabc", "https://dapp.example.com") }

            assertTrue(capturedRequest is TransactionRequest.GetEncryptionPublicKey)
            val req = capturedRequest as TransactionRequest.GetEncryptionPublicKey
            assertEquals("0xabc", req.address)
            assertEquals(ChainType.BSC, req.chain)
            assertEquals("https://dapp.example.com", req.origin)
        }

    @Test
    fun `decrypt throws UserRejectedException when no callback set`() =
        runTest {
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider()
                )

            assertFailsWith<UserRejectedException> {
                middleware.decrypt("0xabc", "encrypted-data", "https://dapp.example.com")
            }
        }

    @Test
    fun `decrypt throws UserRejectedException when callback rejects`() =
        runTest {
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            middleware.setTransactionConfirmCallback { false }

            assertFailsWith<UserRejectedException> {
                middleware.decrypt("0xabc", "encrypted-data", "https://dapp.example.com")
            }
        }

    @Test
    fun `decrypt passes correct TransactionRequest to callback`() =
        runTest {
            var capturedRequest: TransactionRequest? = null
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider(),
                    initialChain = ChainType.BSC
                )
            middleware.setTransactionConfirmCallback { request ->
                capturedRequest = request
                true
            }

            runCatching { middleware.decrypt("0xabc", "encrypted-data", "https://dapp.example.com") }

            assertTrue(capturedRequest is TransactionRequest.Decrypt)
            val req = capturedRequest as TransactionRequest.Decrypt
            assertEquals("0xabc", req.address)
            assertEquals("encrypted-data", req.encryptedData)
            assertEquals(ChainType.BSC, req.chain)
            assertEquals("https://dapp.example.com", req.origin)
        }

    @Test
    fun `signTypedData throws UserRejectedException when no callback set`() =
        runTest {
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider()
                )

            assertFailsWith<UserRejectedException> {
                middleware.signTypedData("0xabc", "{}", "V4", "https://dapp.example.com")
            }
        }

    @Test
    fun `signTypedData throws UserRejectedException when callback rejects`() =
        runTest {
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider()
                )
            middleware.setTransactionConfirmCallback { false }

            assertFailsWith<UserRejectedException> {
                middleware.signTypedData("0xabc", "{}", "V4", "https://dapp.example.com")
            }
        }

    @Test
    fun `signTypedData passes correct TransactionRequest to callback`() =
        runTest {
            var capturedRequest: TransactionRequest? = null
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    StubNodeProvider(),
                    initialChain = ChainType.BSC
                )
            middleware.setTransactionConfirmCallback { request ->
                capturedRequest = request
                true
            }

            runCatching { middleware.signTypedData("0xabc", "{\"types\":{}}", "V4", "https://dapp.example.com") }

            assertTrue(capturedRequest is TransactionRequest.SignTypedData)
            val req = capturedRequest as TransactionRequest.SignTypedData
            assertEquals("0xabc", req.address)
            assertEquals("{\"types\":{}}", req.typedData)
            assertEquals("V4", req.version)
            assertEquals(ChainType.BSC, req.chain)
            assertEquals("https://dapp.example.com", req.origin)
        }

    @Test
    fun `signTransaction throws when gas estimation fails instead of silent 21000 fallback`() =
        runTest {
            val throwingNode =
                object : StubNodeProvider() {
                    override suspend fun estimateGas(
                        txParams: JSONObject,
                        chain: ChainType
                    ): String = throw RuntimeException("estimate failed")
                }
            val middleware =
                EthMiddleware(
                    StubAccountProvider(listOf(testAccount)),
                    SpySecretProvider(),
                    throwingNode,
                    initialChain = ChainType.BSC
                )
            middleware.setTransactionConfirmCallback { true } // Auto-approve for test
            val tx =
                JSONObject()
                    .put("from", "0xabc")
                    .put("to", "0xdef")
                    .put("value", "0x1")

            // M-D7: estimateGas failure must fail the transaction, not silently set gas=21000.
            val ex =
                assertFailsWith<IllegalStateException> {
                    middleware.signTransaction(tx, "https://dapp.example.com")
                }
            assertTrue(ex.message.orEmpty().contains("Gas estimation"))
        }
}
