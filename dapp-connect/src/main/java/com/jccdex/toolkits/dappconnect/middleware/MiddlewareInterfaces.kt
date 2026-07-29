package com.jccdex.toolkits.dappconnect.middleware

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Minimal interface for EVM middleware used by WebAppInterface */
interface IEthMiddleware {
    val currentChainType: StateFlow<com.jccdex.toolkits.core.model.ChainType>
    fun setOnAccountSwitched(callback: (String) -> Unit)
    fun setCurrentChainType(chainType: com.jccdex.toolkits.core.model.ChainType)
    suspend fun requestAccounts(origin: String): JSONArray
    fun getChainId(): String
    suspend fun getBlockNumber(): String
    suspend fun personalSign(address: String, message: String, origin: String): String
    suspend fun recoverPersonalSignature(message: String, signature: String): String
    suspend fun signTypedData(address: String, typedData: String, version: String, origin: String = ""): String
    suspend fun getEncryptionPublicKey(address: String, origin: String = ""): String
    suspend fun decrypt(address: String, encryptedData: String, origin: String = ""): String
    suspend fun signTransaction(txParams: JSONObject, origin: String = ""): Any // SignTransactionResult
    suspend fun sendTransaction(txParams: JSONObject): String
    suspend fun switchEthereumChain(chainIdHex: String, origin: String)
}

/** Minimal interface for SWTC middleware used by WebAppInterface */
interface ISwtcMiddleware {
    suspend fun requestAccounts(origin: String): JSONArray
    suspend fun sendTransaction(txParams: JSONObject, origin: String): String
    suspend fun multiSign(msParams: JSONObject, origin: String): Any
    suspend fun signMessage(from: String, data: String, origin: String): String
    suspend fun getPublicKey(address: String, origin: String): String
}
