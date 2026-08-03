package com.jccdex.toolkits.dappconnect.middleware

import com.jccdex.toolkits.core.model.ChainType
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Callback for requesting user approval before returning accounts to a DApp */
fun interface RequestAccountsCallback {
    suspend fun onRequestAccounts(origin: String): Boolean
}

/** Minimal interface for EVM middleware used by WebAppInterface */
interface IEthMiddleware {
    val currentChainType: StateFlow<ChainType>
    fun setOnAccountSwitched(callback: (String) -> Unit)
    fun setCurrentChainType(chainType: ChainType)
    suspend fun requestAccounts(origin: String): JSONArray
    fun getChainId(): String
    suspend fun getBlockNumber(): String
    suspend fun personalSign(address: String, message: String, origin: String): String
    suspend fun recoverPersonalSignature(message: String, signature: String): String
    suspend fun signTypedData(address: String, typedData: String, version: String, origin: String = ""): String
    suspend fun getEncryptionPublicKey(address: String, origin: String = ""): String
    suspend fun decrypt(address: String, encryptedData: String, origin: String = ""): String
    suspend fun signTransaction(txParams: JSONObject, origin: String = ""): Any // SignTransactionResult
    suspend fun sendTransaction(txParams: JSONObject, origin: String): String
    suspend fun switchEthereumChain(chainIdHex: String, origin: String)
    fun setRequestAccountsCallback(callback: RequestAccountsCallback?)
}

/** Minimal interface for SWTC middleware used by WebAppInterface */
interface ISwtcMiddleware {
    suspend fun requestAccounts(origin: String): JSONArray
    suspend fun sendTransaction(txParams: JSONObject, origin: String): String
    suspend fun multiSign(msParams: JSONObject, origin: String): Any
    suspend fun signMessage(from: String, data: String, origin: String): String
    suspend fun getPublicKey(address: String, origin: String): String
    fun setRequestAccountsCallback(callback: RequestAccountsCallback?)
}
