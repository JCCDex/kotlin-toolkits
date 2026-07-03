package com.jccdex.toolkits.dappconnect

import android.util.Log
import android.webkit.JavascriptInterface
import com.jccdex.toolkits.dappconnect.middleware.IEthMiddleware
import com.jccdex.toolkits.dappconnect.middleware.ISwtcMiddleware
import com.jccdex.toolkits.dappconnect.model.DAppMethod
import com.jccdex.toolkits.dappconnect.provider.AccountProvider
import com.jccdex.toolkits.dappconnect.provider.ChainProvider
import com.jccdex.toolkits.dappconnect.provider.NftProvider
import com.jccdex.toolkits.dappconnect.provider.SecretProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    protected val nftProvider: NftProvider? = null
) {
    companion object {
        private const val TAG = "WebAppInterface"
    }

    private var dappOrigin: String = ""
    private var chainProvider: ChainProvider? = null

    /**
     * Set the DApp origin for security checks
     */
    fun setOrigin(origin: String) {
        this.dappOrigin = origin
    }

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
     * Main entry point for DApp messages
     */
    @JavascriptInterface
    open fun postMessage(json: String) {
        val obj = JSONObject(json)
        Log.d(TAG, "postMessage: $json")

        val method = DAppMethod.fromValue(obj.getString("name"))
        val network = obj.getString("network")
        val id = obj.getString("id")

        when (method) {
            // SWTC RPC Methods
            DAppMethod.SWTC_REQUESTACCOUNTS -> {
                handleSwtcRequestAccounts(network, id)
            }

            DAppMethod.SWTC_SENDTRANSACTION -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() > 0) {
                    val txParams = params.getJSONObject(0)
                    handleSwtcSendTransaction(network, id, txParams)
                } else {
                    sendErrorResponse(network, id, "Missing transaction parameters")
                }
            }

            DAppMethod.SWTC_MULTISIGN -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() > 0) {
                    val msParams = params.getJSONObject(0)
                    handleSwtcMultiSign(network, id, msParams)
                } else {
                    sendErrorResponse(network, id, "Missing multi-sign parameters")
                }
            }

            DAppMethod.SWTC_SIGNMESSAGE -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() >= 2) {
                    val from = params.getString(0)
                    val data = params.getString(1)
                    handleSwtcSignMessage(network, id, from, data)
                } else {
                    sendErrorResponse(network, id, "Missing sign message parameters")
                }
            }

            DAppMethod.SWTC_GETPUBLICKEY -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() > 0) {
                    val address = params.getString(0)
                    handleSwtcGetPublicKey(network, id, address)
                } else {
                    sendErrorResponse(network, id, "Missing address parameter")
                }
            }

            // ETH RPC Methods
            DAppMethod.ETH_REQUESTACCOUNTS,
            DAppMethod.ETH_ACCOUNTS -> {
                handleEthRequestAccounts(network, id)
            }

            DAppMethod.ETH_CHAINID -> {
                handleEthChainId(network, id)
            }

            DAppMethod.ETH_BLOCKNUMBER -> {
                handleEthBlockNumber(network, id)
            }

            DAppMethod.ETH_PERSONAL_SIGN -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() >= 2) {
                    val message = params.getString(0)
                    val address = params.getString(1)
                    handleEthPersonalSign(network, id, address, message)
                } else {
                    sendErrorResponse(network, id, "Missing personal_sign parameters")
                }
            }

            DAppMethod.ETH_PERSONAL_ECRECOVER -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() >= 2) {
                    val message = params.getString(0)
                    val signature = params.getString(1)
                    handleEthRecoverPersonalSignature(network, id, message, signature)
                } else {
                    sendErrorResponse(network, id, "Missing personal_ecRecover parameters")
                }
            }

            DAppMethod.ETH_SIGNTYPEDDATA -> {
                handleEthSignTypedData(network, id, obj, "V1")
            }

            DAppMethod.ETH_SIGNTYPEDDATA_V3 -> {
                handleEthSignTypedData(network, id, obj, "V3")
            }

            DAppMethod.ETH_SIGNTYPEDDATA_V4 -> {
                handleEthSignTypedData(network, id, obj, "V4")
            }

            DAppMethod.ETH_GET_ENCRYPTION_PUBLICKEY -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() >= 1) {
                    val address = params.getString(0)
                    handleEthGetEncryptionPublicKey(network, id, address)
                } else {
                    sendErrorResponse(network, id, "Missing eth_getEncryptionPublicKey parameters")
                }
            }

            DAppMethod.ETH_DECRYPT -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() >= 2) {
                    val message = params.getString(0)
                    val address = params.getString(1)
                    handleEthDecrypt(network, id, address, message)
                } else {
                    sendErrorResponse(network, id, "Missing eth_decrypt parameters")
                }
            }

            DAppMethod.ETH_SIGNTRANSACTION -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() > 0) {
                    val txParams = params.getJSONObject(0)
                    handleEthSignTransaction(network, id, txParams)
                } else {
                    sendErrorResponse(network, id, "Missing transaction parameters")
                }
            }

            DAppMethod.ETH_SENDTRANSACTION -> {
                val params = obj.optJSONArray("params")
                if (params != null && params.length() > 0) {
                    val txParams = params.getJSONObject(0)
                    handleEthSendTransaction(network, id, txParams)
                } else {
                    sendErrorResponse(network, id, "Missing transaction parameters")
                }
            }

            DAppMethod.WALLET_SWITCHETHEREUMCHAIN -> {
                Log.d(TAG, "Received wallet_switchEthereumChain request")
                val params = obj.optJSONArray("params")
                if (params != null && params.length() > 0) {
                    val chainParams = params.getJSONObject(0)
                    val chainId = chainParams.getString("chainId")
                    Log.d(TAG, "Chain switch requested to: $chainId")
                    handleWalletSwitchEthereumChain(network, id, chainId)
                } else {
                    sendErrorResponse(network, id, "Missing chainId parameter")
                }
            }

            DAppMethod.SWTC_REQUESTNFTS -> {
                val address = obj.optJSONArray("params")?.optString(0) ?: ""
                handleSwtcRequestNfts(network, id, address)
            }

            DAppMethod.ETH_REQUESTNFTS -> {
                val params = obj.optJSONArray("params")
                val address = params?.optString(0) ?: ""
                val whiteList = params?.optJSONArray(1)
                handleEthRequestNfts(network, id, address, whiteList)
            }

            DAppMethod.DID_REQUESTACCOUNTNAME -> {
                val address = obj.optJSONArray("params")?.optString(0) ?: ""
                handleDidRequestAccountName(network, id, address)
            }

            DAppMethod.DID_GETBASE58PUBLICKEY -> {
                val address = obj.optJSONArray("params")?.optString(0) ?: ""
                handleDidGetBase58PublicKey(network, id, address)
            }

            DAppMethod.DID_ISSUECREDENTIAL -> {
                val vcJson = obj.optJSONArray("params")?.optJSONObject(0)
                handleDidIssueCredential(network, id, vcJson)
            }

            DAppMethod.IPFS_PERSONALSIGN -> {
                val params = obj.optJSONArray("params")
                val dataArr = params?.optJSONArray(0)
                if (params != null && params.length() >= 2 && dataArr != null) {
                    val address = params.getString(1)
                    handleIpfsPersonalSign(network, id, address, dataArr)
                } else {
                    sendErrorResponse(network, id, "Missing ipfs_personalSign parameters")
                }
            }

            DAppMethod.IPFS_GETPUBLICKEY -> {
                val address = obj.optJSONArray("params")?.optString(0) ?: ""
                handleIpfsGetPublicKey(network, id, address)
            }

            DAppMethod.WEB3_CLIENTVERSION -> {
                sendSuccessResponse(network, id, "CCDAO/v1.0.0")
            }

            else -> {
                Log.w(TAG, "Unhandled method: ${obj.getString("name")}")
                sendErrorResponse(network, id, "Method not supported")
            }
        }
    }

    // SWTC Handlers
    private fun handleSwtcRequestAccounts(network: String, id: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Sync current chain to SWTC so app UI follows DApp network selection
                if (ethMiddleware.currentChainType.value != com.jccdex.toolkits.core.model.ChainType.SWTC) {
                    ethMiddleware.setCurrentChainType(com.jccdex.toolkits.core.model.ChainType.SWTC)
                }
                val accounts = swtcMiddleware.requestAccounts(getOrigin())
                sendSuccessResponse(network, id, accounts)
            } catch (e: Exception) {
                Log.e(TAG, "Error in swtc_requestAccounts", e)
                sendErrorResponse(network, id, e.message ?: "Unknown error")
            }
        }
    }

    private fun handleSwtcSendTransaction(network: String, id: String, txParams: JSONObject) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = swtcMiddleware.sendTransaction(txParams, getOrigin())
                sendSuccessResponse(network, id, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in swtc_sendTransaction", e)
                sendErrorResponse(network, id, e.message ?: "Transaction failed")
            }
        }
    }

    private fun handleSwtcMultiSign(network: String, id: String, msParams: JSONObject) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = swtcMiddleware.multiSign(msParams, getOrigin())
                sendSuccessResponse(network, id, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in swtc_multiSign", e)
                sendErrorResponse(network, id, e.message ?: "Multi-sign failed")
            }
        }
    }

    private fun handleSwtcSignMessage(network: String, id: String, from: String, data: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = swtcMiddleware.signMessage(from, data, getOrigin())
                sendSuccessResponse(network, id, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in swtc_signMessage", e)
                sendErrorResponse(network, id, e.message ?: "Sign message failed")
            }
        }
    }

    private fun handleSwtcGetPublicKey(network: String, id: String, address: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = swtcMiddleware.getPublicKey(address, getOrigin())
                sendSuccessResponse(network, id, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in swtc_getPublicKey", e)
                sendErrorResponse(network, id, e.message ?: "Get public key failed")
            }
        }
    }

    // ETH Handlers
    private fun handleEthRequestAccounts(network: String, id: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val accounts = ethMiddleware.requestAccounts(getOrigin())
                sendSuccessResponse(network, id, accounts)
            } catch (e: Exception) {
                Log.e(TAG, "Error in eth_requestAccounts", e)
                sendErrorResponse(network, id, e.message ?: "Unknown error")
            }
        }
    }

    private fun handleEthChainId(network: String, id: String) {
        val chainId = ethMiddleware.getChainId()
        sendSuccessResponse(network, id, chainId)
    }

    private fun handleEthBlockNumber(network: String, id: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val blockNumber = ethMiddleware.getBlockNumber()
                sendSuccessResponse(network, id, blockNumber)
            } catch (e: Exception) {
                Log.e(TAG, "Error in eth_blockNumber", e)
                sendErrorResponse(network, id, e.message ?: "Failed to get block number")
            }
        }
    }

    private fun handleEthPersonalSign(network: String, id: String, address: String, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.personalSign(address, message, getOrigin())
                sendSuccessResponse(network, id, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in personal_sign", e)
                sendErrorResponse(network, id, e.message ?: "Personal sign failed")
            }
        }
    }

    private fun handleEthRecoverPersonalSignature(network: String, id: String, message: String, signature: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.recoverPersonalSignature(message, signature)
                sendSuccessResponse(network, id, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in personal_ecRecover", e)
                sendErrorResponse(network, id, e.message ?: "Recover failed")
            }
        }
    }

    private fun handleEthSignTypedData(network: String, id: String, obj: JSONObject, version: String) {
        val params = obj.optJSONArray("params")
        if (params != null && params.length() >= 2) {
            val address = params.getString(0)
            val typedData = params.getString(1)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = ethMiddleware.signTypedData(address, typedData, version)
                    sendSuccessResponse(network, id, result)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in eth_signTypedData", e)
                    sendErrorResponse(network, id, e.message ?: "Sign typed data failed")
                }
            }
        } else {
            sendErrorResponse(network, id, "Missing signTypedData parameters")
        }
    }

    private fun handleEthGetEncryptionPublicKey(network: String, id: String, address: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.getEncryptionPublicKey(address)
                sendSuccessResponse(network, id, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in eth_getEncryptionPublicKey", e)
                sendErrorResponse(network, id, e.message ?: "Get encryption public key failed")
            }
        }
    }

    private fun handleEthDecrypt(network: String, id: String, address: String, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.decrypt(address, message)
                sendSuccessResponse(network, id, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in eth_decrypt", e)
                sendErrorResponse(network, id, e.message ?: "Decrypt failed")
            }
        }
    }

    private fun handleEthSignTransaction(network: String, id: String, txParams: JSONObject) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.signTransaction(txParams)
                val data = (result as? com.jccdex.toolkits.dappconnect.model.SignTransactionResult)?.data ?: result
                sendSuccessResponse(network, id, data)
            } catch (e: Exception) {
                Log.e(TAG, "Error in eth_signTransaction", e)
                sendErrorResponse(network, id, e.message ?: "Sign transaction failed")
            }
        }
    }

    private fun handleEthSendTransaction(network: String, id: String, txParams: JSONObject) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ethMiddleware.sendTransaction(txParams)
                sendSuccessResponse(network, id, result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in eth_sendTransaction", e)
                sendErrorResponse(network, id, e.message ?: "Send transaction failed")
            }
        }
    }

    private fun handleWalletSwitchEthereumChain(network: String, id: String, chainId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ethMiddleware.switchEthereumChain(chainId, getOrigin())
                sendSuccessResponse(network, id, null)
            } catch (e: com.jccdex.toolkits.dappconnect.model.ChainNotSupportedException) {
                Log.e(TAG, "Chain not supported: $chainId", e)
                sendErrorResponseWithCode(network, id, e.errorCode, e.message ?: "Chain not supported")
            } catch (e: com.jccdex.toolkits.dappconnect.model.UserRejectedException) {
                Log.e(TAG, "User rejected chain switch", e)
                sendErrorResponseWithCode(network, id, e.errorCode, e.message ?: "User rejected")
            } catch (e: Exception) {
                Log.e(TAG, "Error in wallet_switchEthereumChain", e)
                sendErrorResponse(network, id, e.message ?: "Chain switch failed")
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

    private fun handleDidRequestAccountName(network: String, id: String, address: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val name = accountProvider?.getAccountName(address) ?: ""
                sendSuccessResponse(network, id, name)
            } catch (e: Exception) {
                Log.e(TAG, "did_requestAccountName failed", e)
                sendSuccessResponse(network, id, "")
            }
        }
    }

    private fun handleDidGetBase58PublicKey(network: String, id: String, address: String) {
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
                sendSuccessResponse(network, id, json)
            } catch (e: Exception) {
                Log.e(TAG, "did_getBase58PublicKey failed", e)
                sendErrorResponse(network, id, e.message ?: "Failed to get public key")
            }
        }
    }

    private fun handleDidIssueCredential(network: String, id: String, vcJson: JSONObject?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (vcJson == null) {
                    sendErrorResponse(network, id, "Missing VC JSON parameter")
                    return@launch
                }
                val didSdk = DAppConnectSdk.getDidSdk()
                    ?: throw IllegalStateException("DidSdk not initialized")
                val address = vcJson.optJSONObject("keyDoc")?.optString("address")
                    ?: throw IllegalStateException("Missing keyDoc.address")
                val privateKey = getPrivateKeyOrFail(address)
                val signedVc = didSdk.signCredentialForDApp(privateKey, vcJson.toString())
                sendSuccessResponse(network, id, org.json.JSONObject(signedVc))
            } catch (e: Exception) {
                Log.e(TAG, "did_issueCredential failed", e)
                sendErrorResponse(network, id, e.message ?: "Failed to issue credential")
            }
        }
    }

    private fun handleIpfsPersonalSign(network: String, id: String, address: String, data: org.json.JSONArray?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val didSdk = DAppConnectSdk.getDidSdk()
                    ?: throw IllegalStateException("DidSdk not initialized")
                val intArr = data?.let { arr ->
                    IntArray(arr.length()) { i -> arr.getInt(i) }
                } ?: throw IllegalStateException("Missing data parameter")
                val privateKey = getPrivateKeyOrFail(address)
                val sig = didSdk.ipfsPersonalSign(privateKey, intArr)
                sendSuccessResponse(network, id, sig)
            } catch (e: Exception) {
                Log.e(TAG, "ipfs_personalSign failed", e)
                sendErrorResponse(network, id, e.message ?: "Failed to sign")
            }
        }
    }

    private fun handleIpfsGetPublicKey(network: String, id: String, address: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val didSdk = DAppConnectSdk.getDidSdk()
                    ?: throw IllegalStateException("DidSdk not initialized")
                val privateKey = getPrivateKeyOrFail(address)
                val pubKey = didSdk.ipfsGetPublicKey(privateKey)
                sendSuccessResponse(network, id, pubKey)
            } catch (e: Exception) {
                Log.e(TAG, "ipfs_getPublicKey failed", e)
                sendErrorResponse(network, id, e.message ?: "Failed to get public key")
            }
        }
    }

    private fun handleSwtcRequestNfts(network: String, id: String, address: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (nftProvider == null) {
                    sendSuccessResponse(network, id, org.json.JSONObject().apply {
                        put("address", address)
                        put("total", 0)
                        put("nfts", org.json.JSONArray())
                    })
                    return@launch
                }
                val result = nftProvider.getSwtcNfts(address)
                val json = org.json.JSONObject().apply {
                    put("address", result.address)
                    put("total", result.total)
                    put("nfts", org.json.JSONArray().apply {
                        result.nfts.forEach { nft ->
                            put(org.json.JSONObject().apply {
                                putOpt("image", nft.image)
                                putOpt("issuer", nft.issuer)
                                putOpt("fundCodeName", nft.fundCodeName)
                                putOpt("tokenId", nft.tokenId)
                                putOpt("hash", nft.hash)
                            })
                        }
                    })
                }
                sendSuccessResponse(network, id, json)
            } catch (e: Exception) {
                Log.e(TAG, "swtc_requestNfts failed", e)
                sendErrorResponse(network, id, e.message ?: "Failed to get NFTs")
            }
        }
    }

    private fun handleEthRequestNfts(network: String, id: String, address: String, whiteList: org.json.JSONArray?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (nftProvider == null) {
                    sendSuccessResponse(network, id, org.json.JSONObject().apply {
                        put("address", address)
                        put("total", 0)
                        put("nfts", org.json.JSONArray())
                    })
                    return@launch
                }
                val chainIdHex = "0x" + (ethMiddleware.getChainId()
                    .replace("0x", "")
                    .toLongOrNull(16)?.toString(16) ?: "1")
                val result = nftProvider.getEvmNfts(address, chainIdHex, whiteList)
                val json = org.json.JSONObject().apply {
                    put("address", result.address)
                    put("total", result.total)
                    put("nfts", org.json.JSONArray().apply {
                        result.nfts.forEach { group ->
                            val firstToken = group.tokens.firstOrNull()
                            put(org.json.JSONObject().apply {
                                put("chainId", firstToken?.chainId ?: chainIdHex)
                                put("contractAddress", group.contractAddress)
                                put("name", firstToken?.name ?: "")
                                put("symbol", org.json.JSONObject.NULL)
                                put("standard", "ERC721")
                                put("count", group.tokens.size)
                                put("tokens", org.json.JSONArray().apply {
                                    group.tokens.forEach { token ->
                                        put(org.json.JSONObject().apply {
                                            put("tokenId", token.tokenId)
                                            put("name", token.name ?: "")
                                            put("description", "")
                                            put("image", token.imageUrl ?: "")
                                            put("tokenURI", org.json.JSONObject.NULL)
                                        })
                                    }
                                })
                            })
                        }
                    })
                }
                sendSuccessResponse(network, id, json)
            } catch (e: Exception) {
                Log.e(TAG, "eth_requestNfts failed", e)
                sendErrorResponse(network, id, e.message ?: "Failed to get NFTs")
            }
        }
    }

    // Response Helpers
    protected open fun sendSuccessResponse(network: String, id: String, result: Any?) {
        // This should be overridden to send response back to WebView
        Log.d(TAG, "Success response: network=$network, id=$id, result=$result")
    }

    protected open fun sendErrorResponse(network: String, id: String, error: String) {
        // This should be overridden to send response back to WebView
        Log.e(TAG, "Error response: network=$network, id=$id, error=$error")
    }

    protected open fun sendErrorResponseWithCode(network: String, id: String, code: Int, error: String) {
        // This should be overridden to send response back to WebView
        Log.e(TAG, "Error response with code: network=$network, id=$id, code=$code, error=$error")
    }
}
