package com.jccdex.toolkits.dappconnect.middleware

import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.WalletAccount
import com.jccdex.toolkits.dappconnect.provider.AccountProvider
import com.jccdex.toolkits.dappconnect.provider.NodeProvider
import com.jccdex.toolkits.dappconnect.provider.SecretProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SwtcMiddlewareTest {

    private class SpySecretProvider : SecretProvider {
        var lastOrigin: String? = null
        var lastAddress: String? = null

        override suspend fun getPrivateKeyForAddress(address: String, origin: String): String? {
            lastAddress = address
            lastOrigin = origin
            return null
        }

        override suspend fun getSecretForAddress(address: String, origin: String): String? {
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
        override suspend fun getTransactionCount(address: String, chain: ChainType): String = "0x0"
        override suspend fun getGasPrice(chain: ChainType): String = "0x1"
        override suspend fun getMaxPriorityFeePerGas(chain: ChainType): String = "0x1"
        override suspend fun estimateGas(txParams: org.json.JSONObject, chain: ChainType): String = "0x5208"
        override suspend fun broadcastTransaction(signedTx: String, chain: ChainType): String = "0xhash"
        override suspend fun sendRawTransaction(signedBlob: String): String = "0xhash"
        override suspend fun fetchSequence(address: String): Long = 1
    }

    // ── M-18 / H-02: origin passing ──

    @Test
    fun `requestAccounts filters swtc accounts excluding hd roots`() = runTest {
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

        val result = middleware.requestAccounts("https://dapp.example.com")

        assertEquals(1, result.length())
        assertEquals("jSwtcAddress", result.getString(0))
    }
}
