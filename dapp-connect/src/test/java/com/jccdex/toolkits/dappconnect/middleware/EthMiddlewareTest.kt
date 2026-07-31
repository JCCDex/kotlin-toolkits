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

        override suspend fun getPrivateKeyForAddress(address: String, origin: String): String? {
            lastAddress = address
            lastOrigin = origin
            return "privkey-$address"
        }

        override suspend fun getSecretForAddress(address: String, origin: String): String? {
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

    private class StubNodeProvider : NodeProvider {
        override suspend fun getRpcUrl(chain: ChainType): String = "https://rpc.test"
        override suspend fun getBlockNumber(chain: ChainType): String = "0x1"
        override suspend fun getTransactionCount(address: String, chain: ChainType): String = "0x0"
        override suspend fun getGasPrice(chain: ChainType): String = "0x1"
        override suspend fun getMaxPriorityFeePerGas(chain: ChainType): String = "0x1"
        override suspend fun estimateGas(txParams: JSONObject, chain: ChainType): String = "0x5208"
        override suspend fun broadcastTransaction(signedTx: String, chain: ChainType): String = "0xhash"
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
    fun `requestAccounts succeeds when no callback set`() = runTest {
        val middleware =
            EthMiddleware(
                StubAccountProvider(listOf(testAccount)),
                SpySecretProvider(),
                StubNodeProvider()
            )

        val result = middleware.requestAccounts("https://dapp.example.com")

        assertEquals(1, result.length())
        assertEquals("0xabc", result.getString(0))
    }

    @Test
    fun `requestAccounts succeeds when callback approves`() = runTest {
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
    fun `requestAccounts throws when callback rejects`() = runTest {
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
    fun `requestAccounts passes origin to callback`() = runTest {
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
    fun `requestAccounts skips hd root accounts`() = runTest {
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

        val result = middleware.requestAccounts("https://dapp.example.com")

        assertEquals(1, result.length())
        assertEquals("0xchild", result.getString(0))
    }
}
