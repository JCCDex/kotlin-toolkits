package com.jccdex.toolkits.dappconnect

import android.util.Log
import android.webkit.JavascriptInterface
import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.dappconnect.middleware.IEthMiddleware
import com.jccdex.toolkits.dappconnect.middleware.ISwtcMiddleware
import com.jccdex.toolkits.dappconnect.model.ChainNotSupportedException
import com.jccdex.toolkits.dappconnect.model.DAppMethod
import com.jccdex.toolkits.dappconnect.model.SignTransactionResult
import com.jccdex.toolkits.dappconnect.model.UserRejectedException
import com.jccdex.toolkits.dappconnect.provider.AccountProvider
import com.jccdex.toolkits.dappconnect.provider.ChainProvider
import com.jccdex.toolkits.dappconnect.provider.NftProvider
import com.jccdex.toolkits.dappconnect.provider.SecretProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

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
    }

    private var dappOrigin: String = ""
    private var chainProvider: ChainProvider? = null

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

    /**
     * Main entry point for DApp messages.
     * Rejects blank or unsafe origins (M-05). Hosts must [setOrigin] on navigation.
     */
    @JavascriptInterface
    open fun postMessage(json: String) {
        val origin = getOrigin()
        if (origin.isBlank()) {
            Log.w(TAG, "postMessage rejected: blank origin (host must setOrigin)")
            return
        }
        if (!DAppConnectSdk.isSafeUrl(origin)) {
            Log.w(TAG, "postMessage rejected: unsafe origin=$origin")
            return
        }

        val obj = JSONObject(json)
        val method = DAppMethod.fromValue(obj.getString("name"))
        val network = obj.getString("network")
        val id = obj.getString("id")
        val nonce = obj.optString("nonce", id)
        // Never log full postMessage payload (may contain tx / message / ciphertext).
        Log.d(TAG, "postMessage method=${method.name} network=$network")

        when (method) {
            // SWTC RPC Methods
            DAppMethod.SWTC_REQUESTACCOUNTS -> {
                handleSwtcRequestAccounts(network, nonce)
            }

            DAppMethod.SWTC_SENDTRANSACTION -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() > 0) {
                    val txParams = params.getJSONObject(0)
                    handleSwtcSendTransaction(network, nonce, txParams)
                } else {
                    sendErrorResponse(network, nonce, "Missing transaction parameters")
                }
            }

            DAppMethod.SWTC_MULTISIGN -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() > 0) {
                    val msParams = params.getJSONObject(0)
                    handleSwtcMultiSign(network, nonce, msParams)
                } else {
                    sendErrorResponse(network, nonce, "Missing multi-sign parameters")
                }
            }

            DAppMethod.SWTC_SIGNMESSAGE -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() >= 2) {
                    val from = params.getString(0)
                    val data = params.getString(1)
                    handleSwtcSignMessage(network, nonce, from, data)
                } else {
                    sendErrorResponse(network, nonce, "Missing sign message parameters")
                }
            }

            DAppMethod.SWTC_GETPUBLICKEY -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() > 0) {
                    val address = params.getString(0)
                    handleSwtcGetPublicKey(network, nonce, address)
                } else {
                    sendErrorResponse(network, nonce, "Missing address parameter")
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
                val params = obj.optJSONArray("params")
                if (params != null && params.length() >= 2) {
                    val message = params.getString(0)
                    val address = params.getString(1)
                    handleEthPersonalSign(network, nonce, address, message)
                } else {
                    sendErrorResponse(network, nonce, "Missing personal_sign parameters")
                }
            }

            DAppMethod.ETH_PERSONAL_ECRECOVER -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() >= 2) {
                    val message = params.getString(0)
                    val signature = params.getString(1)
                    handleEthRecoverPersonalSignature(network, nonce, message, signature)
                } else {
                    sendErrorResponse(network, nonce, "Missing personal_ecRecover parameters")
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
                val params = obj.optJSONArray("params")
                if (params != null && params.length() >= 1) {
                    val address = params.getString(0)
                    handleEthGetEncryptionPublicKey(network, nonce, address)
                } else {
                    sendErrorResponse(network, nonce, "Missing eth_getEncryptionPublicKey parameters")
                }
            }

            DAppMethod.ETH_DECRYPT -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() >= 2) {
                    val message = params.getString(0)
                    val address = params.getString(1)
                    handleEthDecrypt(network, nonce, address, message)
                } else {
                    sendErrorResponse(network, nonce, "Missing eth_decrypt parameters")
                }
            }

            DAppMethod.ETH_SIGNTRANSACTION -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() > 0) {
                    val txParams = params.getJSONObject(0)
                    handleEthSignTransaction(network, nonce, txParams)
                } else {
                    sendErrorResponse(network, nonce, "Missing transaction parameters")
                }
            }

            DAppMethod.ETH_SENDTRANSACTION -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() > 0) {
                    val txParams = params.getJSONObject(0)
                    handleEthSendTransaction(network, nonce, txParams)
                } else {
                    sendErrorResponse(network, nonce, "Missing transaction parameters")
                }
            }

            DAppMethod.WALLET_SWITCHETHEREUMCHAIN -> {
                Log.d(TAG, "Received wallet_switchEthereumChain request")
                val params = obj.optJSONArray("params")
                if (params != null && params.length() > 0) {
                    val chainParams = params.getJSONObject(0)
                    val chainId = chainParams.getString("chainId")
                    Log.d(TAG, "Chain switch requested to: $chainId")
                    handleWalletSwitchEthereumChain(network, nonce, chainId)
                } else {
                    sendErrorResponse(network, nonce, "Missing chainId parameter")
                }
            }

            DAppMethod.SWTC_REQUESTNFTS -> {
                val address = obj.optJSONArray("params")?.optString(0) ?: ""
                handleSwtcRequestNfts(network, nonce, address)
            }

            DAppMethod.ETH_REQUESTNFTS -> {
                val params = obj.optJSONArray("params")
                val address = params?.optString(0) ?: ""
                val whiteList = params?.optJSONArray(1)
                handleEthRequestNfts(network, nonce, address, whiteList)
            }

            DAppMethod.DID_REQUESTACCOUNTNAME -> {
                val address = obj.optJSONArray("params")?.optString(0) ?: ""
                handleDidRequestAccountName(network, nonce, address)
            }

            DAppMethod.DID_GETBASE58PUBLICKEY -> {
                val address = obj.optJSONArray("params")?.optString(0) ?: ""
                handleDidGetBase58PublicKey(network, nonce, address)
            }

            DAppMethod.DID_ISSUECREDENTIAL -> {
                val vcJson = obj.optJSONArray("params")?.optJSONObject(0)
                handleDidIssueCredential(network, nonce, vcJson)
            }

            DAppMethod.IPFS_PERSONALSIGN -> {
                val params = obj.optJSONArray("params")
                val dataArr = params?.optJSONArray(0)
                if (params != null && params.length() >= 2 && dataArr != null) {
                    val address = params.getString(1)
                    handleIpfsPersonalSign(network, nonce, address, dataArr)
                } else {
                    sendErrorResponse(network, nonce, "Missing ipfs_personalSign parameters")
                }
            }

            DAppMethod.IPFS_GETPUBLICKEY -> {
                val address = obj.optJSONArray("params")?.optString(0) ?: ""
                handleIpfsGetPublicKey(network, nonce, address)
            }

            DAppMethod.WEB3_CLIENTVERSION -> {
                sendSuccessResponse(network, nonce, "CCDAO/v1.0.0")
            }

            else -> {
                Log.w(TAG, "Unhandled method: ${obj.getString("name")}")
                sendErrorResponse(network, nonce, "Method not supported")
            }
        }
    }

    // SWTC Handlers
    private fun handleSwtcRequestAccounts(network: String, nonce: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (ethMiddleware.currentChainType.value != ChainType.SWTC) {
                    ethMiddleware.setCurrentChainType(ChainType.SWTC)
                }
                val accounts = swtcMiddleware.requestAccounts(getOrigin())
                sendSuccessResponse(network, nonce, accounts)
            } catch (e: UserRejectedException) {
                sendErrorResponseWithCode(network, nonce, e.errorCode, e.message ?: "User rejected")
            } catch (e: Exception) {
                Log.e(TAG, "Error in swtc_requestAccounts", e)
                sendErrorResponse(network, nonce, e.message ?: "Unknown error")
            }
        }
    }

    private fun handleSwtcSendTransaction(network: String, nonce: String, txParams: JSONObject) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = swtcMiddleware.sendTransaction(txParams, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in swtc_sendTransaction", e)
                sendErrorResponse(network, nonce, e.message ?: "Transaction failed")
            }
        }
    }

    private fun handleSwtcMultiSign(network: String, nonce: String, msParams: JSONObject) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = swtcMiddleware.multiSign(msParams, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in swtc_multiSign", e)
                sendErrorResponse(network, nonce, e.message ?: "Multi-sign failed")
            }
        }
    }

    private fun handleSwtcSignMessage(network: String, nonce: String, from: String, data: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = swtcMiddleware.signMessage(from, data, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in swtc_signMessage", e)
                sendErrorResponse(network, nonce, e.message ?: "Sign message failed")
            }
        }
    }

    private fun handleSwtcGetPublicKey(network: String, nonce: String, address: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = swtcMiddleware.getPublicKey(address, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in swtc_getPublicKey", e)
                sendErrorResponse(network, nonce, e.message ?: "Get public key failed")
            }
        }
    }

    // ETH Handlers
    private fun handleEthRequestAccounts(network: String, nonce: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val accounts = ethMiddleware.requestAccounts(getOrigin())
                sendSuccessResponse(network, nonce, accounts)
            } catch (e: UserRejectedException) {
                sendErrorResponseWithCode(network, nonce, e.errorCode, e.message ?: "User rejected")
            } catch (e: Exception) {
                Log.e(TAG, "Error in eth_requestAccounts", e)
                sendErrorResponse(network, nonce, e.message ?: "Unknown error")
            }
        }
    }

    private fun handleEthChainId(network: String, nonce: String) {
        val chainId = ethMiddleware.getChainId()
        sendSuccessResponse(network, nonce, chainId)
    }

    private fun handleEthBlockNumber(network: String, nonce: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val blockNumber = ethMiddleware.getBlockNumber()
                sendSuccessResponse(network, nonce, blockNumber)
            } catch (e: Exception) {
                Log.e(TAG, "Error in eth_blockNumber", e)
                sendErrorResponse(network, nonce, e.message ?: "Failed to get block number")
            }
        }
    }

    private fun handleEthPersonalSign(network: String, nonce: String, address: String, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.personalSign(address, message, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in personal_sign", e)
                sendErrorResponse(network, nonce, e.message ?: "Personal sign failed")
            }
        }
    }

    private fun handleEthRecoverPersonalSignature(network: String, nonce: String, message: String, signature: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.recoverPersonalSignature(message, signature)
                sendSuccessResponse(network, nonce, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in personal_ecRecover", e)
                sendErrorResponse(network, nonce, e.message ?: "Recover failed")
            }
        }
    }

    private fun handleEthSignTypedData(network: String, nonce: String, obj: JSONObject, version: String) {
        val params = obj.optJSONArray("params")
        if (params != null && params.length() >= 2) {
            val address = params.getString(0)
            val typedData = params.getString(1)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = ethMiddleware.signTypedData(address, typedData, version, getOrigin())
                    sendSuccessResponse(network, nonce, result)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in eth_signTypedData", e)
                    sendErrorResponse(network, nonce, e.message ?: "Sign typed data failed")
                }
            }
        } else {
            sendErrorResponse(network, nonce, "Missing signTypedData parameters")
        }
    }

    private fun handleEthGetEncryptionPublicKey(network: String, nonce: String, address: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.getEncryptionPublicKey(address, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in eth_getEncryptionPublicKey", e)
                sendErrorResponse(network, nonce, e.message ?: "Get encryption public key failed")
            }
        }
    }

    private fun handleEthDecrypt(network: String, nonce: String, address: String, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.decrypt(address, message, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in eth_decrypt", e)
                sendErrorResponse(network, nonce, e.message ?: "Decrypt failed")
            }
        }
    }

    private fun handleEthSignTransaction(network: String, nonce: String, txParams: JSONObject) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.signTransaction(txParams, getOrigin())
                val data = (result as? SignTransactionResult)?.data ?: result
                sendSuccessResponse(network, nonce, data)
            } catch (e: Exception) {
                Log.e(TAG, "Error in eth_signTransaction", e)
                sendErrorResponse(network, nonce, e.message ?: "Sign transaction failed")
            }
        }
    }

    private fun handleEthSendTransaction(network: String, nonce: String, txParams: JSONObject) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.sendTransaction(txParams, getOrigin())
                sendSuccessResponse(network, nonce, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in eth_sendTransaction", e)
                sendErrorResponse(network, nonce, e.message ?: "Send transaction failed")
            }
        }
    }

    private fun handleWalletSwitchEthereumChain(network: String, nonce: String, chainId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ethMiddleware.switchEthereumChain(chainId, getOrigin())
                sendSuccessResponse(network, nonce, null)
            } catch (e: ChainNotSupportedException) {
                Log.e(TAG, "Chain not supported: $chainId", e)
                sendErrorResponseWithCode(network, nonce, e.errorCode, e.message ?: "Chain not supported")
            } catch (e: UserRejectedException) {
                Log.e(TAG, "User rejected chain switch", e)
                sendErrorResponseWithCode(network, nonce, e.errorCode, e.message ?: "User rejected")
            } catch (e: Exception) {
                Log.e(TAG, "Error in wallet_switchEthereumChain", e)
                sendErrorResponse(network, nonce, e.message ?: "Chain switch failed")
            }
        }
    }

    // ── DID / IPFS / NFT handlers ──

    // ── helpers ──

    protected open suspend fun getPrivateKeyOrFail(address: String): String {
        val sp = secretProvider
            ?: throw IllegalStateException("SecretProvider not configured")
        return sp.getPrivateKeyForAddress(address, getOrigin())
            ?: throw IllegalStateException("User cancelled or private key not available")
    }

    // ── DID / IPFS / NFT handlers ──

    private fun handleDidRequestAccountName(network: String, nonce: String, address: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val name = accountProvider?.getAccountName(address) ?: ""
                sendSuccessResponse(network, nonce, name)
            } catch (e: Exception) {
                Log.e(TAG, "did_requestAccountName failed", e)
                sendSuccessResponse(network, nonce, "")
            }
        }
    }

    private fun handleDidGetBase58PublicKey(network: String, nonce: String, address: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val didSdk = DAppConnectSdk.getDidSdk()
                    ?: throw IllegalStateException("DidSdk not initialized")
                val privateKey = getPrivateKeyOrFail(address)
                val result = didSdk.didGenerateBase58PublicKey(privateKey)
                val json = JSONObject().apply {
                    put("publicKeyBase58", result.publicKeyBase58)
                    put("type", result.type)
                }
                sendSuccessResponse(network, nonce, json)
            } catch (e: Exception) {
                Log.e(TAG, "did_getBase58PublicKey failed", e)
                sendErrorResponse(network, nonce, e.message ?: "Failed to get public key")
            }
        }
    }

    private fun handleDidIssueCredential(network: String, nonce: String, vcJson: JSONObject?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (vcJson == null) {
                    sendErrorResponse(network, nonce, "Missing VC JSON parameter")
                    return@launch
                }
                val didSdk = DAppConnectSdk.getDidSdk()
                    ?: throw IllegalStateException("DidSdk not initialized")
                val address = vcJson.optJSONObject("keyDoc")?.optString("address")
                    ?: throw IllegalStateException("Missing keyDoc.address")
                val privateKey = getPrivateKeyOrFail(address)
                val signedVc = didSdk.signCredentialForDApp(privateKey, vcJson.toString())
                sendSuccessResponse(network, nonce, JSONObject(signedVc))
            } catch (e: Exception) {
                Log.e(TAG, "did_issueCredential failed", e)
                sendErrorResponse(network, nonce, e.message ?: "Failed to issue credential")
            }
        }
    }

    private fun handleIpfsPersonalSign(network: String, nonce: String, address: String, data: JSONArray?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val didSdk = DAppConnectSdk.getDidSdk()
                    ?: throw IllegalStateException("DidSdk not initialized")
                val intArr = data?.let { arr ->
                    IntArray(arr.length()) { i -> arr.getInt(i) }
                } ?: throw IllegalStateException("Missing data parameter")
                val privateKey = getPrivateKeyOrFail(address)
                val sig = didSdk.ipfsPersonalSign(privateKey, intArr)
                sendSuccessResponse(network, nonce, sig)
                didDocumentMutationListener?.onDidDocumentMutated()
            } catch (e: Exception) {
                Log.e(TAG, "ipfs_personalSign failed", e)
                sendErrorResponse(network, nonce, e.message ?: "Failed to sign")
            }
        }
    }

    private fun handleIpfsGetPublicKey(network: String, nonce: String, address: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val didSdk = DAppConnectSdk.getDidSdk()
                    ?: throw IllegalStateException("DidSdk not initialized")
                val privateKey = getPrivateKeyOrFail(address)
                val pubKey = didSdk.ipfsGetPublicKey(privateKey)
                sendSuccessResponse(network, nonce, pubKey)
            } catch (e: Exception) {
                Log.e(TAG, "ipfs_getPublicKey failed", e)
                sendErrorResponse(network, nonce, e.message ?: "Failed to get public key")
            }
        }
    }

    private fun handleSwtcRequestNfts(network: String, nonce: String, address: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (nftProvider == null) {
                    sendSuccessResponse(network, nonce, JSONObject().apply {
                        put("address", address)
                        put("total", 0)
                        put("nfts", JSONArray())
                    })
                    return@launch
                }
                val result = nftProvider.getSwtcNfts(address)
                val json = JSONObject().apply {
                    put("address", result.address)
                    put("total", result.total)
                    put("nfts", JSONArray().apply {
                        result.nfts.forEach { nft ->
                            put(JSONObject().apply {
                                putOpt("image", nft.image)
                                putOpt("issuer", nft.issuer)
                                putOpt("fundCodeName", nft.fundCodeName)
                                putOpt("tokenId", nft.tokenId)
                                putOpt("hash", nft.hash)
                            })
                        }
                    })
                }
                sendSuccessResponse(network, nonce, json)
            } catch (e: Exception) {
                Log.e(TAG, "swtc_requestNfts failed", e)
                sendErrorResponse(network, nonce, e.message ?: "Failed to get NFTs")
            }
        }
    }

    private fun handleEthRequestNfts(network: String, nonce: String, address: String, whiteList: JSONArray?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (nftProvider == null) {
                    sendSuccessResponse(network, nonce, JSONObject().apply {
                        put("address", address)
                        put("total", 0)
                        put("nfts", JSONArray())
                    })
                    return@launch
                }
                val chainIdHex = "0x" + (ethMiddleware.getChainId()
                    .replace("0x", "")
                    .toLongOrNull(16)?.toString(16) ?: "1")
                val result = nftProvider.getEvmNfts(address, chainIdHex, whiteList)
                val json = JSONObject().apply {
                    put("address", result.address)
                    put("total", result.total)
                    put("nfts", JSONArray().apply {
                        result.nfts.forEach { group ->
                            val firstToken = group.tokens.firstOrNull()
                            put(JSONObject().apply {
                                put("chainId", firstToken?.chainId ?: chainIdHex)
                                put("contractAddress", group.contractAddress)
                                put("name", firstToken?.name ?: "")
                                put("symbol", JSONObject.NULL)
                                put("standard", "ERC721")
                                put("count", group.tokens.size)
                                put("tokens", JSONArray().apply {
                                    group.tokens.forEach { token ->
                                        put(JSONObject().apply {
                                            put("tokenId", token.tokenId)
                                            put("name", token.name ?: "")
                                            put("description", "")
                                            put("image", token.imageUrl ?: "")
                                            put("tokenURI", JSONObject.NULL)
                                        })
                                    }
                                })
                            })
                        }
                    })
                }
                sendSuccessResponse(network, nonce, json)
            } catch (e: Exception) {
                Log.e(TAG, "eth_requestNfts failed", e)
                sendErrorResponse(network, nonce, e.message ?: "Failed to get NFTs")
            }
        }
    }

    // Response Helpers — never log result/error bodies (may contain signatures, ciphertext, addresses lists).
    protected open fun sendSuccessResponse(network: String, nonce: String, result: Any?) {
        Log.d(TAG, "Success response: network=$network")
    }

    protected open fun sendErrorResponse(network: String, nonce: String, error: String) {
        Log.e(TAG, "Error response: network=$network")
    }

    protected open fun sendErrorResponseWithCode(network: String, nonce: String, code: Int, error: String) {
        Log.e(TAG, "Error response with code: network=$network, code=$code")
    }
}
