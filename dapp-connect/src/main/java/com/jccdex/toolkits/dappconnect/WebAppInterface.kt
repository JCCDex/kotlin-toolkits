package com.jccdex.toolkits.dappconnect

import android.util.Log
import android.webkit.JavascriptInterface
import com.jccdex.toolkits.core.error.ToolkitException
import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.rpc.ErrorCodes
import com.jccdex.toolkits.dappconnect.middleware.IEthMiddleware
import com.jccdex.toolkits.dappconnect.middleware.ISwtcMiddleware
import com.jccdex.toolkits.dappconnect.model.DAppMethod
import com.jccdex.toolkits.dappconnect.model.SignTransactionResult
import com.jccdex.toolkits.dappconnect.model.UnauthorizedException
import com.jccdex.toolkits.dappconnect.model.UserRejectedException
import com.jccdex.toolkits.dappconnect.provider.AccountProvider
import com.jccdex.toolkits.dappconnect.provider.ChainProvider
import com.jccdex.toolkits.dappconnect.provider.NftProvider
import com.jccdex.toolkits.dappconnect.provider.SecretProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Parsed fields of a valid [WebAppInterface.postMessage] request (M-D2 guard result). */
private data class PostMessageRequest(
    val method: DAppMethod,
    val network: String,
    val id: String,
    val nonce: String,
    val obj: JSONObject
)

/** Returns element at [index] only when it is a JSON object (M-D2 params guard). */
private fun JSONArray.paramObject(index: Int): JSONObject? = optJSONObject(index)

/** Returns element at [index] only when it is a non-blank string (M-D2 params guard). */
private fun JSONArray.paramString(index: Int): String? =
    opt(index)?.takeIf { it is String && (it as String).isNotBlank() } as? String

/** Returns params when omitted (empty array) or a JSON array; null when `params` is wrong type (M-D2). */
private fun JSONObject.paramsArray(): JSONArray? {
    if (!has("params")) return JSONArray()
    return optJSONArray("params")
}

/** Returns element at [index] when it is a JSON array of byte values 0..255 (M-D2 params guard). */
private fun JSONArray.paramIntArray(index: Int): IntArray? {
    val arr = optJSONArray(index) ?: return null
    if (arr.length() == 0) return null
    return IntArray(arr.length()) { i ->
        val value = arr.opt(i)
        if (value !is Number) return null
        val byte = value.toInt()
        if (byte !in 0..255) return null
        byte
    }
}

/**
 * WebAppInterface - JavaScript interface for DApp communication.
 * This class handles messages from the WebView and routes them to appropriate middleware.
 *
 * Example usage:
 * ```kotlin
 * val webAppInterface = DAppConnectSdk.createWebAppInterface(
 *     ethMiddleware = ethMiddleware,
 *     swtcMiddleware = swtcMiddleware
 * )
 * webView.addJavascriptInterface(webAppInterface, "_tw_")
 * ```
 */
open class WebAppInterface(
    protected val ethMiddleware: IEthMiddleware,
    protected val swtcMiddleware: ISwtcMiddleware,
    protected val accountProvider: AccountProvider? = null,
    protected val secretProvider: SecretProvider? = null,
    protected val nftProvider: NftProvider? = null,
    private val didDocumentMutationListener: DidDocumentMutationListener? = null
) {
    companion object {
        private const val TAG = "WebAppInterface"

        /** Per-origin request budget (M-D6 DoS guard). */
        private const val RATE_LIMIT_PER_SEC = 60
    }

    private var dappOrigin: String = ""
    private var chainProvider: ChainProvider? = null
    private val requestRateLimiter = TokenBucketRateLimiter(RATE_LIMIT_PER_SEC)

    /**
     * Set the DApp origin for security checks.
     * Hosts **must** call this (e.g. on navigation) before [postMessage]; blank origin is rejected (M-05).
     * Stores a normalized web origin (`scheme://host[:port]`) when possible (H-R2 / M-R4).
     */
    fun setOrigin(origin: String) {
        this.dappOrigin = WebOrigin.normalize(origin) ?: origin.trim()
    }

    /**
     * Install the native→JS [WebMessagePort] response channel (C-03).
     *
     * Hosts using [WebAppInterfaceWithWebView] (or an app subclass with a channel) **must**
     * call this after evaluating `ccdao-eip1193-provider.js`, typically in the
     * `evaluateJavascript` completion callback on each page load.
     * Default no-op for interfaces without a WebView.
     */
    open fun installResponseChannel() = Unit

    /**
     * Get the current DApp origin
     */
    protected open fun getOrigin(): String = dappOrigin

    /**
     * Set chain provider for chain switching
     */
    fun setChainProvider(provider: ChainProvider) {
        this.chainProvider = provider
        ethMiddleware.setChainProvider(provider)
        ethMiddleware.setOnAccountSwitched { newAddress ->
            onAccountSwitched?.invoke(newAddress)
        }
    }

    private var onAccountSwitched: ((String) -> Unit)? = null

    /**
     * Set callback for account switched event
     */
    fun setOnAccountSwitched(callback: (String) -> Unit) {
        this.onAccountSwitched = callback
    }

    private var didCredentialConfirmCallback: (suspend (String) -> Boolean)? = null

    /**
     * Host hook for DApp credential signing confirmation (H-DID1). When unset, `did_issueCredential`
     * is rejected (fail closed). The host should show a confirmation UI and return whether the user
     * approved the credential being signed. Suspend so hosts can await a Compose/dialog confirm
     * (same pattern as [TransactionConfirmCallback]).
     */
    fun setDidCredentialConfirm(callback: (suspend (String) -> Boolean)?) {
        this.didCredentialConfirmCallback = callback
    }

    /**
     * Main entry point for DApp messages.
     * Rejects blank or unsafe origins (M-05). Hosts must [setOrigin] on navigation.
     */
    @JavascriptInterface
    open fun postMessage(json: String) {
        val origin = getOrigin()
        if (origin.isBlank()) {
            Log.w(TAG, "postMessage rejected: blank origin (host must setOrigin)")
            rejectPostMessage(json, "Origin not set")
            return
        }
        if (!DAppConnectSdk.isSafeUrl(origin)) {
            Log.w(TAG, "postMessage rejected: unsafe origin=$origin")
            rejectPostMessage(json, "Unsafe origin")
            return
        }

        // M-D6: per-origin rate limit (DoS guard) — drop requests over the budget.
        if (!requestRateLimiter.tryAcquire(origin)) {
            Log.w(TAG, "postMessage rejected: rate limit exceeded for origin=$origin")
            val rejected = runCatching { JSONObject(json) }.getOrNull()
            val rejectedNetwork = rejected?.optString("network").orEmpty()
            val rejectedNonce = rejected?.optString("nonce", rejected.optString("id")).orEmpty()
            if (rejectedNetwork.isNotBlank() && rejectedNonce.isNotBlank()) {
                sendErrorResponseWithCode(
                    rejectedNetwork,
                    rejectedNonce,
                    ErrorCodes.WALLET_ERROR,
                    "Rate limit exceeded"
                )
            }
            return
        }

        // M-D2: tolerate malformed or incomplete JSON from the page — drop it (cannot parse
        // network/nonce for an error response) instead of throwing inside the @JavascriptInterface
        // bridge. Guards both non-JSON payloads and valid JSON that is missing required fields
        // ("{}" or {"name":"..."} without network) or has wrong-typed values.
        val request =
            runCatching {
                val obj = JSONObject(json)
                PostMessageRequest(
                    method = DAppMethod.fromValue(obj.getString("name")),
                    network = obj.getString("network"),
                    id = obj.getString("id"),
                    nonce = obj.optString("nonce", obj.getString("id")),
                    obj = obj
                )
            }.getOrNull()
        if (request == null) {
            Log.w(TAG, "postMessage rejected: invalid or incomplete JSON payload")
            rejectPostMessage(json, "Invalid request")
            return
        }
        val method = request.method
        val network = request.network
        val nonce = request.nonce
        // Never log full postMessage payload (may contain tx / message / ciphertext).
        Log.d(TAG, "postMessage method=${method.name} network=$network")

        dispatchPostMessage(request)
    }

    /** Routes a validated [PostMessageRequest] to the appropriate RPC handler (M-D2 params guard). */
    private fun dispatchPostMessage(request: PostMessageRequest) {
        val method = request.method
        val network = request.network
        val nonce = request.nonce
        val obj = request.obj

        when (method) {
            // SWTC RPC Methods
            DAppMethod.SWTC_REQUESTACCOUNTS -> {
                handleSwtcRequestAccounts(network, nonce)
            }

            DAppMethod.SWTC_SENDTRANSACTION -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val txParams = params.paramObject(0)
                if (txParams != null) {
                    handleSwtcSendTransaction(network, nonce, txParams)
                } else {
                    sendInvalidParams(network, nonce, "Invalid transaction parameters")
                }
            }

            DAppMethod.SWTC_MULTISIGN -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val msParams = params.paramObject(0)
                if (msParams != null) {
                    handleSwtcMultiSign(network, nonce, msParams)
                } else {
                    sendInvalidParams(network, nonce, "Invalid multi-sign parameters")
                }
            }

            DAppMethod.SWTC_SIGNMESSAGE -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val from = params.paramString(0)
                val data = params.paramString(1)
                if (from != null && data != null) {
                    handleSwtcSignMessage(network, nonce, from, data)
                } else {
                    sendInvalidParams(network, nonce, "Invalid sign message parameters")
                }
            }

            DAppMethod.SWTC_GETPUBLICKEY -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val address = requireParamAddress(params, network, nonce) ?: return
                handleSwtcGetPublicKey(network, nonce, address)
            }

            DAppMethod.SWTC_BATCHTRANSACTIONS -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val batchReq = params.paramObject(0)
                if (batchReq != null) {
                    handleSwtcBatchTransactions(network, nonce, batchReq)
                } else {
                    sendInvalidParams(network, nonce, "Invalid batch transaction parameters")
                }
            }

            // ETH RPC Methods
            DAppMethod.ETH_REQUESTACCOUNTS,
            DAppMethod.ETH_ACCOUNTS -> {
                handleEthRequestAccounts(network, nonce)
            }

            DAppMethod.ETH_CHAINID -> {
                handleEthChainId(network, nonce)
            }

            DAppMethod.ETH_BLOCKNUMBER -> {
                handleEthBlockNumber(network, nonce)
            }

            DAppMethod.ETH_PERSONAL_SIGN -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val message = params.paramString(0)
                val address = params.paramString(1)
                if (message != null && address != null) {
                    handleEthPersonalSign(network, nonce, address, message)
                } else {
                    sendInvalidParams(network, nonce, "Invalid personal_sign parameters")
                }
            }

            DAppMethod.ETH_PERSONAL_ECRECOVER -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val message = params.paramString(0)
                val signature = params.paramString(1)
                if (message != null && signature != null) {
                    handleEthRecoverPersonalSignature(network, nonce, message, signature)
                } else {
                    sendInvalidParams(network, nonce, "Invalid personal_ecRecover parameters")
                }
            }

            DAppMethod.ETH_SIGNTYPEDDATA -> {
                handleEthSignTypedData(network, nonce, obj, "V1")
            }

            DAppMethod.ETH_SIGNTYPEDDATA_V3 -> {
                handleEthSignTypedData(network, nonce, obj, "V3")
            }

            DAppMethod.ETH_SIGNTYPEDDATA_V4 -> {
                handleEthSignTypedData(network, nonce, obj, "V4")
            }

            DAppMethod.ETH_GET_ENCRYPTION_PUBLICKEY -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val address = params.paramString(0)
                if (address != null) {
                    handleEthGetEncryptionPublicKey(network, nonce, address)
                } else {
                    sendInvalidParams(network, nonce, "Invalid eth_getEncryptionPublicKey parameters")
                }
            }

            DAppMethod.ETH_DECRYPT -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val message = params.paramString(0)
                val address = params.paramString(1)
                if (message != null && address != null) {
                    handleEthDecrypt(network, nonce, address, message)
                } else {
                    sendInvalidParams(network, nonce, "Invalid eth_decrypt parameters")
                }
            }

            DAppMethod.ETH_SIGNTRANSACTION -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val txParams = params.paramObject(0)
                if (txParams != null) {
                    handleEthSignTransaction(network, nonce, txParams)
                } else {
                    sendInvalidParams(network, nonce, "Invalid transaction parameters")
                }
            }

            DAppMethod.ETH_SENDTRANSACTION -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val txParams = params.paramObject(0)
                if (txParams != null) {
                    handleEthSendTransaction(network, nonce, txParams)
                } else {
                    sendInvalidParams(network, nonce, "Invalid transaction parameters")
                }
            }

            DAppMethod.WALLET_SWITCHETHEREUMCHAIN -> {
                Log.d(TAG, "Received wallet_switchEthereumChain request")
                val params = requireParamsArray(obj, network, nonce) ?: return
                val chainParams = params.paramObject(0)
                val chainId = chainParams?.optString("chainId")?.takeIf { it.isNotBlank() }
                if (chainId != null) {
                    Log.d(TAG, "Chain switch requested to: $chainId")
                    handleWalletSwitchEthereumChain(network, nonce, chainId)
                } else {
                    sendInvalidParams(network, nonce, "Invalid chainId parameter")
                }
            }

            DAppMethod.SWTC_REQUESTNFTS,
            DAppMethod.ETH_REQUESTNFTS -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val address = optionalNftAddress(params)
                if (method == DAppMethod.SWTC_REQUESTNFTS) {
                    handleSwtcRequestNfts(network, nonce, address)
                } else {
                    handleEthRequestNfts(network, nonce, address, params.optJSONArray(1))
                }
            }

            DAppMethod.DID_REQUESTACCOUNTNAME -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val address = requireParamAddress(params, network, nonce) ?: return
                handleDidRequestAccountName(network, nonce, address)
            }

            DAppMethod.DID_GETBASE58PUBLICKEY -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val address = requireParamAddress(params, network, nonce) ?: return
                handleDidGetBase58PublicKey(network, nonce, address)
            }

            DAppMethod.DID_ISSUECREDENTIAL -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val vcJson = params.paramObject(0)
                handleDidIssueCredential(network, nonce, vcJson)
            }

            DAppMethod.IPFS_PERSONALSIGN -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val dataArr = params.paramIntArray(0)
                val address = params.paramString(1)
                if (dataArr != null && address != null) {
                    handleIpfsPersonalSign(network, nonce, address, dataArr)
                } else {
                    sendInvalidParams(network, nonce, "Invalid ipfs_personalSign parameters")
                }
            }

            DAppMethod.IPFS_GETPUBLICKEY -> {
                val params = requireParamsArray(obj, network, nonce) ?: return
                val address = requireParamAddress(params, network, nonce) ?: return
                handleIpfsGetPublicKey(network, nonce, address)
            }

            DAppMethod.WEB3_CLIENTVERSION -> {
                sendSuccessResponse(network, nonce, "CCDAO/v1.0.0")
            }

            else -> {
                Log.w(TAG, "Unhandled method: ${obj.getString("name")}")
                sendErrorResponseWithCode(network, nonce, ErrorCodes.UNSUPPORTED_METHOD, "Method not supported")
            }
        }
    }

    /** Returns `params` array; sends -32602 when present but not a JSON array (M-D2). */
    private fun requireParamsArray(
        obj: JSONObject,
        network: String,
        nonce: String
    ): JSONArray? {
        val params = obj.paramsArray()
        if (params == null) {
            sendInvalidParams(network, nonce, "Invalid params")
        }
        return params
    }

    /** Returns non-blank `params[0]` address or sends -32602 (M-D2). */
    private fun requireParamAddress(
        params: JSONArray,
        network: String,
        nonce: String
    ): String? {
        val address = params.paramString(0)
        if (address == null) {
            sendInvalidParams(network, nonce, "Invalid address parameter")
        }
        return address
    }

    /** Optional NFT query address — blank/missing becomes empty string (same as EIP-1193 NFT helpers). */
    private fun optionalNftAddress(params: JSONArray): String = params.paramString(0).orEmpty()

    // SWTC Handlers
    private fun handleSwtcRequestAccounts(
        network: String,
        nonce: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (ethMiddleware.currentChainType.value != ChainType.SWTC) {
                    // M-D5: never switch the global chain silently — route through the same
                    // ChainProvider confirmation as wallet_switchEthereumChain.
                    val provider =
                        chainProvider
                            ?: throw IllegalStateException("ChainProvider not set")
                    val confirmed =
                        provider.requestChainSwitch(
                            ethMiddleware.currentChainType.value,
                            ChainType.SWTC,
                            getOrigin()
                        )
                    if (!confirmed) {
                        throw UserRejectedException("User rejected the chain switch request")
                    }
                    ethMiddleware.setCurrentChainType(ChainType.SWTC)
                }
                val accounts = swtcMiddleware.requestAccounts(getOrigin())
                sendSuccessResponse(network, nonce, accounts)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Unknown error", "swtc_requestAccounts")
            }
        }
    }

    private fun handleSwtcSendTransaction(
        network: String,
        nonce: String,
        txParams: JSONObject
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = swtcMiddleware.sendTransaction(txParams, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Transaction failed", "swtc_sendTransaction")
            }
        }
    }

    private fun handleSwtcMultiSign(
        network: String,
        nonce: String,
        msParams: JSONObject
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = swtcMiddleware.multiSign(msParams, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Multi-sign failed", "swtc_multiSign")
            }
        }
    }

    private fun handleSwtcSignMessage(
        network: String,
        nonce: String,
        from: String,
        data: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = swtcMiddleware.signMessage(from, data, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Sign message failed", "swtc_signMessage")
            }
        }
    }

    private fun handleSwtcGetPublicKey(
        network: String,
        nonce: String,
        address: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = swtcMiddleware.getPublicKey(address, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Get public key failed", "swtc_getPublicKey")
            }
        }
    }

    private fun handleSwtcBatchTransactions(
        network: String,
        nonce: String,
        batchReq: JSONObject
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = swtcMiddleware.batchTransactions(batchReq, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Batch transactions failed", "swtc_batchTransactions")
            }
        }
    }

    // ETH Handlers
    private fun handleEthRequestAccounts(
        network: String,
        nonce: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val accounts = ethMiddleware.requestAccounts(getOrigin())
                sendSuccessResponse(network, nonce, accounts)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Unknown error", "eth_requestAccounts")
            }
        }
    }

    private fun handleEthChainId(
        network: String,
        nonce: String
    ) {
        val chainId = ethMiddleware.getChainId()
        sendSuccessResponse(network, nonce, chainId)
    }

    private fun handleEthBlockNumber(
        network: String,
        nonce: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val blockNumber = ethMiddleware.getBlockNumber()
                sendSuccessResponse(network, nonce, blockNumber)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Failed to get block number", "eth_blockNumber")
            }
        }
    }

    private fun handleEthPersonalSign(
        network: String,
        nonce: String,
        address: String,
        message: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.personalSign(address, message, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Personal sign failed", "personal_sign")
            }
        }
    }

    private fun handleEthRecoverPersonalSignature(
        network: String,
        nonce: String,
        message: String,
        signature: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.recoverPersonalSignature(message, signature)
                sendSuccessResponse(network, nonce, result)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Recover failed", "personal_ecRecover")
            }
        }
    }

    private fun handleEthSignTypedData(
        network: String,
        nonce: String,
        obj: JSONObject,
        version: String
    ) {
        val params = requireParamsArray(obj, network, nonce) ?: return
        val address = params.paramString(0)
        val typedData = params.paramString(1)
        if (address != null && typedData != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = ethMiddleware.signTypedData(address, typedData, version, getOrigin())
                    sendSuccessResponse(network, nonce, result)
                } catch (e: Throwable) {
                    dispatchRpcFailure(network, nonce, e, "Sign typed data failed", "eth_signTypedData")
                }
            }
        } else {
            sendInvalidParams(network, nonce, "Invalid signTypedData parameters")
        }
    }

    private fun handleEthGetEncryptionPublicKey(
        network: String,
        nonce: String,
        address: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.getEncryptionPublicKey(address, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Get encryption public key failed", "eth_getEncryptionPublicKey")
            }
        }
    }

    private fun handleEthDecrypt(
        network: String,
        nonce: String,
        address: String,
        message: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.decrypt(address, message, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Decrypt failed", "eth_decrypt")
            }
        }
    }

    private fun handleEthSignTransaction(
        network: String,
        nonce: String,
        txParams: JSONObject
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.signTransaction(txParams, getOrigin())
                val data = (result as? SignTransactionResult)?.data ?: result
                sendSuccessResponse(network, nonce, data)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Sign transaction failed", "eth_signTransaction")
            }
        }
    }

    private fun handleEthSendTransaction(
        network: String,
        nonce: String,
        txParams: JSONObject
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.sendTransaction(txParams, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Send transaction failed", "eth_sendTransaction")
            }
        }
    }

    private fun handleWalletSwitchEthereumChain(
        network: String,
        nonce: String,
        chainId: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ethMiddleware.switchEthereumChain(chainId, getOrigin())
                sendSuccessResponse(network, nonce, null)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Chain switch failed", "wallet_switchEthereumChain")
            }
        }
    }

    // ── DID / IPFS / NFT handlers ──

    // ── helpers ──

    protected open suspend fun getPrivateKeyOrFail(address: String): String {
        val sp =
            secretProvider
                ?: throw IllegalStateException("SecretProvider not configured")
        return sp.getPrivateKeyForAddress(address, getOrigin())
            ?: throw UnauthorizedException("User cancelled or private key not available")
    }

    // ── DID / IPFS / NFT handlers ──

    private fun handleDidRequestAccountName(
        network: String,
        nonce: String,
        address: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val name = accountProvider?.getAccountName(address) ?: ""
                sendSuccessResponse(network, nonce, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "did_requestAccountName failed", e)
                sendSuccessResponse(network, nonce, "")
            }
        }
    }

    private fun handleDidGetBase58PublicKey(
        network: String,
        nonce: String,
        address: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val didSdk =
                    DAppConnectSdk.getDidSdk()
                        ?: throw IllegalStateException("DidSdk not initialized")
                val privateKey = getPrivateKeyOrFail(address)
                val result = didSdk.didGenerateBase58PublicKey(privateKey)
                val json =
                    JSONObject().apply {
                        put("publicKeyBase58", result.publicKeyBase58)
                        put("type", result.type)
                    }
                sendSuccessResponse(network, nonce, json)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Failed to get public key", "did_getBase58PublicKey failed")
            }
        }
    }

    private fun handleDidIssueCredential(
        network: String,
        nonce: String,
        vcJson: JSONObject?
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (vcJson == null) {
                    sendInvalidParams(network, nonce, "Missing VC JSON parameter")
                    return@launch
                }
                val didSdk =
                    DAppConnectSdk.getDidSdk()
                        ?: throw IllegalStateException("DidSdk not initialized")
                val address =
                    vcJson.optJSONObject("keyDoc")?.optString("address")
                        ?: throw IllegalStateException("Missing keyDoc.address")
                val privateKey = getPrivateKeyOrFail(address)
                val signedVc =
                    didSdk.signCredentialForDApp(
                        privateKey,
                        vcJson.toString(),
                        onConfirm = didCredentialConfirmCallback
                    )
                sendSuccessResponse(network, nonce, JSONObject(signedVc))
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Failed to issue credential", "did_issueCredential failed")
            }
        }
    }

    private fun handleIpfsPersonalSign(
        network: String,
        nonce: String,
        address: String,
        data: IntArray
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val didSdk =
                    DAppConnectSdk.getDidSdk()
                        ?: throw IllegalStateException("DidSdk not initialized")
                val privateKey = getPrivateKeyOrFail(address)
                val sig = didSdk.ipfsPersonalSign(privateKey, data)
                sendSuccessResponse(network, nonce, sig)
                didDocumentMutationListener?.onDidDocumentMutated()
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Failed to sign", "ipfs_personalSign failed")
            }
        }
    }

    private fun handleIpfsGetPublicKey(
        network: String,
        nonce: String,
        address: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val didSdk =
                    DAppConnectSdk.getDidSdk()
                        ?: throw IllegalStateException("DidSdk not initialized")
                val privateKey = getPrivateKeyOrFail(address)
                val pubKey = didSdk.ipfsGetPublicKey(privateKey)
                sendSuccessResponse(network, nonce, pubKey)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Failed to get public key", "ipfs_getPublicKey failed")
            }
        }
    }

    private fun handleSwtcRequestNfts(
        network: String,
        nonce: String,
        address: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (nftProvider == null) {
                    sendSuccessResponse(
                        network,
                        nonce,
                        JSONObject().apply {
                            put("address", address)
                            put("total", 0)
                            put("nfts", JSONArray())
                        }
                    )
                    return@launch
                }
                val result = nftProvider.getSwtcNfts(address)
                val json =
                    JSONObject().apply {
                        put("address", result.address)
                        put("total", result.total)
                        put(
                            "nfts",
                            JSONArray().apply {
                                result.nfts.forEach { nft ->
                                    put(
                                        JSONObject().apply {
                                            putOpt("image", nft.image)
                                            putOpt("issuer", nft.issuer)
                                            putOpt("fundCodeName", nft.fundCodeName)
                                            putOpt("tokenId", nft.tokenId)
                                            putOpt("hash", nft.hash)
                                        }
                                    )
                                }
                            }
                        )
                    }
                sendSuccessResponse(network, nonce, json)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Failed to get NFTs", "swtc_requestNfts failed")
            }
        }
    }

    private fun handleEthRequestNfts(
        network: String,
        nonce: String,
        address: String,
        whiteList: JSONArray?
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (nftProvider == null) {
                    sendSuccessResponse(
                        network,
                        nonce,
                        JSONObject().apply {
                            put("address", address)
                            put("total", 0)
                            put("nfts", JSONArray())
                        }
                    )
                    return@launch
                }
                val chainIdHex =
                    "0x" + (
                        ethMiddleware.getChainId()
                            .replace("0x", "")
                            .toLongOrNull(16)?.toString(16) ?: "1"
                    )
                val result = nftProvider.getEvmNfts(address, chainIdHex, whiteList)
                val json =
                    JSONObject().apply {
                        put("address", result.address)
                        put("total", result.total)
                        put(
                            "nfts",
                            JSONArray().apply {
                                result.nfts.forEach { group ->
                                    val firstToken = group.tokens.firstOrNull()
                                    put(
                                        JSONObject().apply {
                                            put("chainId", firstToken?.chainId ?: chainIdHex)
                                            put("contractAddress", group.contractAddress)
                                            put("name", firstToken?.name ?: "")
                                            put("symbol", JSONObject.NULL)
                                            put("standard", "ERC721")
                                            put("count", group.tokens.size)
                                            put(
                                                "tokens",
                                                JSONArray().apply {
                                                    group.tokens.forEach { token ->
                                                        put(
                                                            JSONObject().apply {
                                                                put("tokenId", token.tokenId)
                                                                put("name", token.name ?: "")
                                                                put("description", "")
                                                                put("image", token.imageUrl ?: "")
                                                                put("tokenURI", JSONObject.NULL)
                                                            }
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        )
                    }
                sendSuccessResponse(network, nonce, json)
            } catch (e: Throwable) {
                dispatchRpcFailure(network, nonce, e, "Failed to get NFTs", "eth_requestNfts failed")
            }
        }
    }

    /** Best-effort error for rejected [postMessage] calls so JS requestQueue does not hang (C-03). */
    private fun rejectPostMessage(
        json: String,
        errorMessage: String
    ) {
        val rejected = runCatching { JSONObject(json) }.getOrNull() ?: return
        val network = rejected.optString("network").takeIf { it.isNotBlank() } ?: return
        val nonce =
            rejected
                .optString("nonce")
                .takeIf { it.isNotBlank() }
                ?: rejected.optString("id").takeIf { it.isNotBlank() }
                ?: return
        sendInvalidRequest(network, nonce, errorMessage)
    }

    // Response Helpers — never log result/error bodies (may contain signatures, ciphertext, addresses lists).
    protected open fun sendSuccessResponse(
        network: String,
        nonce: String,
        result: Any?
    ) {
        Log.d(TAG, "Success response: network=$network")
    }

    protected open fun sendErrorResponse(
        network: String,
        nonce: String,
        error: String
    ) {
        sendErrorResponseWithCode(network, nonce, ErrorCodes.WALLET_ERROR, error)
    }

    protected open fun sendErrorResponseWithCode(
        network: String,
        nonce: String,
        code: Int,
        error: String
    ) {
        Log.e(TAG, "Error response with code: network=$network, code=$code")
    }

    protected fun sendInvalidParams(
        network: String,
        nonce: String,
        message: String
    ) {
        sendErrorResponseWithCode(network, nonce, ErrorCodes.INVALID_PARAMS, message)
    }

    protected fun sendInvalidRequest(
        network: String,
        nonce: String,
        message: String
    ) {
        sendErrorResponseWithCode(network, nonce, ErrorCodes.INVALID_REQUEST, message)
    }

    protected fun dispatchRpcFailure(
        network: String,
        nonce: String,
        cause: Throwable,
        defaultMessage: String,
        operation: String
    ) {
        if (cause is CancellationException) throw cause
        if (cause !is ToolkitException) {
            Log.e(TAG, "Error in $operation", cause)
        }
        val rpc = cause.toRpcError(defaultMessage)
        sendErrorResponseWithCode(network, nonce, rpc.code, rpc.message)
    }
}

/**
 * Minimal per-key token bucket (M-D6 DoS guard): allows up to [tokensPerSecond] acquisitions per
 * key, refilling continuously. Bounds abuse — not an exact QPS meter.
 */
internal class TokenBucketRateLimiter(
    private val tokensPerSecond: Int
) {
    private data class Bucket(
        var tokens: Double,
        var lastRefillNanos: Long
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryAcquire(key: String): Boolean {
        val now = System.nanoTime()
        val bucket = buckets.computeIfAbsent(key) { Bucket(tokensPerSecond.toDouble(), now) }
        synchronized(bucket) {
            val elapsedSeconds = (now - bucket.lastRefillNanos) / 1_000_000_000.0
            bucket.tokens =
                minOf(tokensPerSecond.toDouble(), bucket.tokens + elapsedSeconds * tokensPerSecond)
            bucket.lastRefillNanos = now
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                return true
            }
            return false
        }
    }
}
