package com.jccdex.toolkits.dappconnect.middleware

import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.dappconnect.provider.ChainProvider
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Callback for requesting user approval before returning accounts to a DApp */
fun interface RequestAccountsCallback {
    suspend fun onRequestAccounts(origin: String): Boolean
}

/** Sealed class representing different types of transaction requests */
sealed class TransactionRequest {
    abstract val chain: ChainType
    abstract val origin: String

    data class SendTransaction(
        override val chain: ChainType,
        override val origin: String,
        val to: String?,
        val value: String?,
        val data: String?,
        val gas: String?,
        val gasPrice: String?,
        val nonce: String?,
        val txParams: JSONObject
    ) : TransactionRequest()

    data class SignMessage(
        override val chain: ChainType,
        override val origin: String,
        val address: String,
        val message: String,
        val type: SignType
    ) : TransactionRequest()

    data class SignTypedData(
        override val chain: ChainType,
        override val origin: String,
        val address: String,
        val typedData: String,
        val version: String
    ) : TransactionRequest()

    data class Decrypt(
        override val chain: ChainType,
        override val origin: String,
        val address: String,
        val encryptedData: String
    ) : TransactionRequest()

    data class GetEncryptionPublicKey(
        override val chain: ChainType,
        override val origin: String,
        val address: String
    ) : TransactionRequest()

    data class SwtcBatchTransaction(
        override val chain: ChainType,
        override val origin: String,
        val totalCount: Int,
        val totalAmount: String?,
        // Accepts either JSONObject or typed Transfer objects
        val transfers: List<Any>
    ) : TransactionRequest()
}

enum class SignType { PERSONAL_SIGN, SIGN_MESSAGE }

/** Callback for requesting user confirmation before signing or sending transactions */
fun interface TransactionConfirmCallback {
    suspend fun onConfirm(request: TransactionRequest): Boolean
}

/** Minimal interface for EVM middleware used by WebAppInterface */
interface IEthMiddleware {
    val currentChainType: StateFlow<ChainType>

    fun setOnAccountSwitched(callback: (String) -> Unit)

    fun setCurrentChainType(chainType: ChainType)

    suspend fun requestAccounts(origin: String): JSONArray

    fun getChainId(): String

    suspend fun getBlockNumber(): String

    suspend fun personalSign(
        address: String,
        message: String,
        origin: String
    ): String

    suspend fun recoverPersonalSignature(
        message: String,
        signature: String
    ): String

    suspend fun signTypedData(
        address: String,
        typedData: String,
        version: String,
        origin: String = ""
    ): String

    suspend fun getEncryptionPublicKey(
        address: String,
        origin: String = ""
    ): String

    suspend fun decrypt(
        address: String,
        encryptedData: String,
        origin: String = ""
    ): String

    suspend fun signTransaction(txParams: JSONObject, origin: String = ""): Any // SignTransactionResult

    suspend fun sendTransaction(
        txParams: JSONObject,
        origin: String
    ): String

    suspend fun switchEthereumChain(
        chainIdHex: String,
        origin: String
    )

    fun setRequestAccountsCallback(callback: RequestAccountsCallback?)

    fun setTransactionConfirmCallback(callback: TransactionConfirmCallback?)

    fun setChainProvider(provider: ChainProvider?)
}

/** Minimal interface for SWTC middleware used by WebAppInterface */
interface ISwtcMiddleware {
    suspend fun requestAccounts(origin: String): JSONArray

    suspend fun sendTransaction(
        txParams: JSONObject,
        origin: String
    ): String

    suspend fun multiSign(
        msParams: JSONObject,
        origin: String
    ): Any

    suspend fun signMessage(
        from: String,
        data: String,
        origin: String
    ): String

    suspend fun getPublicKey(
        address: String,
        origin: String
    ): String

    suspend fun batchTransactions(
        batchReq: JSONObject,
        origin: String
    ): JSONArray

    fun setRequestAccountsCallback(callback: RequestAccountsCallback?)

    fun setTransactionConfirmCallback(callback: TransactionConfirmCallback?)
}
