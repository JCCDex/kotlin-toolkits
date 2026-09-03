package com.jccdex.toolkits.dappconnect.provider

import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.WalletAccount
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

// ── Core providers ──

interface AccountProvider {
    val accounts: Flow<List<WalletAccount>>

    fun getAccountsByChain(chain: ChainType): Flow<List<WalletAccount>>

    val currentAccount: Flow<WalletAccount?>

    suspend fun getAccountByAddress(address: String): WalletAccount?

    suspend fun setCurrentAccount(accountId: String)

    suspend fun getAccountName(address: String): String?
}

interface SecretProvider {
    suspend fun getPrivateKeyForAddress(
        address: String,
        origin: String
    ): String?

    suspend fun getSecretForAddress(
        address: String,
        origin: String
    ): String?
}

interface NodeProvider {
    suspend fun getRpcUrl(chain: ChainType): String

    suspend fun getBlockNumber(chain: ChainType): String

    suspend fun getTransactionCount(
        address: String,
        chain: ChainType
    ): String

    suspend fun getGasPrice(chain: ChainType): String

    suspend fun getMaxPriorityFeePerGas(chain: ChainType): String

    suspend fun estimateGas(
        txParams: JSONObject,
        chain: ChainType
    ): String

    suspend fun broadcastTransaction(
        signedTx: String,
        chain: ChainType
    ): String

    suspend fun sendRawTransaction(signedBlob: String): String

    suspend fun fetchSequence(address: String): Long
}

// ── Chain switching ──

interface ChainProvider {
    suspend fun requestChainSwitch(
        fromChain: ChainType,
        toChain: ChainType,
        origin: String
    ): Boolean

    val supportedChains: List<ChainType>
    val currentChain: ChainType
}

// ── NFT (optional) ──

data class EvmNftItem(
    val chainId: String,
    val contractAddress: String,
    val tokenId: String,
    val name: String?,
    val imageUrl: String?
)

data class SwtcNftItem(
    val image: String?,
    val issuer: String?,
    val fundCodeName: String?,
    val tokenId: String?,
    val hash: String?
)

data class EvmNftResult(
    val address: String,
    val total: Int,
    val nfts: List<EvmNftContractGroup>
)

data class EvmNftContractGroup(
    val contractAddress: String,
    val tokens: List<EvmNftItem>
)

data class SwtcNftResult(
    val address: String,
    val total: Int,
    val nfts: List<SwtcNftItem>
)

interface NftProvider {
    suspend fun getEvmNfts(
        address: String,
        chainIdHex: String,
        whiteList: JSONArray?
    ): EvmNftResult

    suspend fun getSwtcNfts(address: String): SwtcNftResult
}
