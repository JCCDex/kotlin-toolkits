package com.jccdex.toolkits.dappconnect.middleware

import android.util.Log
import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.WalletAccount
import com.jccdex.toolkits.dappconnect.WebOrigin
import com.jccdex.toolkits.dappconnect.model.UserRejectedException
import com.jccdex.toolkits.dappconnect.provider.AccountProvider
import com.jccdex.toolkits.dappconnect.provider.NodeProvider
import com.jccdex.toolkits.dappconnect.provider.SecretProvider
import com.jccdex.toolkits.wallet.sdk.WalletSdk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * SWTC RPC Middleware - handles SWTC-specific RPC methods.
 * This class is independent of Hilt and can be instantiated directly.
 */
class SwtcMiddleware(
    private val accountProvider: AccountProvider,
    private val secretProvider: SecretProvider,
    private val nodeProvider: NodeProvider
) : ISwtcMiddleware {
    companion object {
        private const val TAG = "SwtcMiddleware"
    }

    @Volatile private var requestAccountsCallback: RequestAccountsCallback? = null

    override fun setRequestAccountsCallback(callback: RequestAccountsCallback?) {
        requestAccountsCallback = callback
    }

    /**
     * Handle swtc_requestAccounts RPC call
     * Returns list of available SWTC addresses (excluding HD root accounts)
     */
    override suspend fun requestAccounts(origin: String): JSONArray {
        Log.d(TAG, "requestAccounts called from origin: $origin")

        val cb =
            requestAccountsCallback
                ?: throw UserRejectedException("RequestAccountsCallback is not set")
        if (!cb.onRequestAccounts(origin)) {
            throw UserRejectedException("User rejected the requestAccounts request")
        }

        val accounts = accountProvider.accounts.first()
        // Filter SWTC accounts and exclude HD root accounts
        val swtcAccounts =
            accounts.filter {
                it.chain.bip44Code == ChainType.SWTC.bip44Code &&
                    !(it.isHD && it.parentId == null)
            }

        val result = JSONArray()
        swtcAccounts.forEach { account ->
            result.put(account.address)
        }

        Log.d(TAG, "Returning ${result.length()} SWTC accounts (excluding HD roots)")
        return result
    }

    /**
     * Handle swtc_sendTransaction RPC call
     * Signs and sends a transaction
     */
    override suspend fun sendTransaction(
        txParams: JSONObject,
        origin: String
    ): String {
        require(origin.isNotBlank()) { "origin must not be blank for sendTransaction" }
        Log.d(TAG, "sendTransaction called from origin: $origin")

        val account = txParams.getString("Account")

        // Verify account exists in wallet
        val accounts = accountProvider.accounts.first()
        val walletAccount =
            accounts.find { it.address == account }
                ?: throw IllegalArgumentException("Account not found in wallet: $account")

        // Validate account belongs to SWTC chain
        if (walletAccount.chain.bip44Code != ChainType.SWTC.bip44Code) {
            throw IllegalArgumentException("Account is not a SWTC account: $account")
        }

        Log.d(TAG, "Processing transaction")

        // Get sequence if not provided
        if (!txParams.has("Sequence")) {
            val sequence = nodeProvider.fetchSequence(account)
            txParams.put("Sequence", sequence)
        }

        // Get secret
        val secret = secretProvider.getSecretForAddress(account, origin)
            ?: throw IllegalStateException("Failed to get secret for address: $account")

        // Sign transaction using WalletSdk
        val blob = WalletSdk.signTransaction(txParams, secret)

        // Submit to blockchain
        return nodeProvider.sendRawTransaction(blob)
    }

    /**
     * Handle swtc_multiSign RPC call
     */
    override suspend fun multiSign(
        msParams: JSONObject,
        origin: String
    ): JSONObject {
        Log.d(TAG, "multiSign called from origin: $origin")

        val account = msParams.getString("account")

        // Verify account exists in wallet
        val accounts = accountProvider.accounts.first()
        val walletAccount =
            accounts.find { it.address == account }
                ?: throw IllegalArgumentException("Account not found in wallet: $account")

        // Validate account belongs to SWTC chain
        if (walletAccount.chain.bip44Code != ChainType.SWTC.bip44Code) {
            throw IllegalArgumentException("Account is not a SWTC account: $account")
        }

        Log.d(TAG, "Processing multi-sign")

        // Get secret for signing
        val secret = secretProvider.getSecretForAddress(account, origin)
            ?: throw IllegalStateException("Failed to get secret for address: $account")

        // Get tx from multi-sign parameters
        val tx = msParams.getJSONObject("tx")

        // Sign using WalletSdk
        val result = WalletSdk.multiSign(tx, secret)

        return JSONObject().apply {
            put("result", result)
        }
    }

    /**
     * Handle swtc_signMessage RPC call
     * Signs a message with the specified address
     */
    override suspend fun signMessage(
        from: String,
        data: String,
        origin: String
    ): String {
        Log.d(TAG, "signMessage called from origin: $origin")

        // Verify address exists in wallet
        val accounts = accountProvider.accounts.first()
        val walletAccount =
            accounts.find { it.address == from }
                ?: throw IllegalArgumentException("Address not found in wallet: $from")

        // Validate address belongs to SWTC chain
        if (walletAccount.chain.bip44Code != ChainType.SWTC.bip44Code) {
            throw IllegalArgumentException("Address is not a SWTC address: $from")
        }

        Log.d(TAG, "Signing message")

        // Get secret for signing
        val secret = secretProvider.getSecretForAddress(from, origin)
            ?: throw IllegalStateException("Failed to get secret for address: $from")

        // Sign message using WalletSdk
        val signature = WalletSdk.signMessage(from, data, secret)

        return signature
    }

    /**
     * Handle swtc_getPublicKey RPC call
     * Returns the public key for the specified address
     */
    override suspend fun getPublicKey(
        address: String,
        origin: String
    ): String {
        Log.d(TAG, "getPublicKey called from origin: $origin")

        // Verify address exists in wallet
        val accounts = accountProvider.accounts.first()
        val walletAccount =
            accounts.find { it.address == address }
                ?: throw IllegalArgumentException("Address not found in wallet: $address")

        // Validate address belongs to SWTC chain
        if (walletAccount.chain.bip44Code != ChainType.SWTC.bip44Code) {
            throw IllegalArgumentException("Address is not a SWTC address: $address")
        }

        Log.d(TAG, "Getting public key")

        // Return the public key from wallet account
        val publicKey = walletAccount.publicKey

        Log.d(TAG, "Public key retrieved")
        return publicKey
    }

    /**
     * Send transaction with provided password (for native UI usage)
     */
    suspend fun sendTransactionWithPassword(
        txParams: JSONObject,
        password: String,
        origin: String
    ): String {
        Log.d(TAG, "sendTransactionWithPassword from origin: $origin")

        val account = txParams.getString("Account")

        val accounts = accountProvider.accounts.first()
        val walletAccount =
            accounts.find { it.address == account }
                ?: throw IllegalArgumentException("Account not found in wallet: $account")

        if (walletAccount.chain.bip44Code != ChainType.SWTC.bip44Code) {
            throw IllegalArgumentException("Account is not a SWTC account: $account")
        }

        if (!txParams.has("Sequence")) {
            val sequence = nodeProvider.fetchSequence(account)
            txParams.put("Sequence", sequence)
        }

        // Note: This method assumes secretProvider can work with password directly
        // In practice, this might need a different approach
        val secret = secretProvider.getSecretForAddress(account, origin)
            ?: throw IllegalStateException("Failed to get secret for address: $account")

        val blob = WalletSdk.signTransaction(txParams, secret)

        return nodeProvider.sendRawTransaction(blob)
    }

    /**
     * Send NFT transaction for **native UI** (password already collected by the host).
     * Uses [WebOrigin.WALLET_INTERNAL] instead of a blank origin so secret providers can
     * distinguish intentional in-app access from missing DApp origin (M-18).
     */
    suspend fun sendNftTransactionWithPassword(
        address: String,
        to: String,
        tokenId: String,
        memo: String,
        password: String
    ): String {
        Log.d(TAG, "sendNftTransactionWithPassword NFT transfer")

        val accounts = accountProvider.accounts.first()
        val walletAccount =
            accounts.find { it.address == address }
                ?: throw IllegalArgumentException("Account not found in wallet: $address")

        if (walletAccount.chain.bip44Code != ChainType.SWTC.bip44Code) {
            throw IllegalArgumentException("Account is not a SWTC account: $address")
        }

        // Build NFT transfer transaction
        val rawTx = WalletSdk.buildSwtcNftTransfer(address, to, tokenId, memo)
        val txParams = JSONObject(rawTx)

        // Get sequence
        if (!txParams.has("Sequence")) {
            val sequence = nodeProvider.fetchSequence(address)
            txParams.put("Sequence", sequence)
        }

        val secret = secretProvider.getSecretForAddress(address, WebOrigin.WALLET_INTERNAL)
            ?: throw IllegalStateException("Failed to get secret for address: $address")

        val signedTxBlob = WalletSdk.signTransaction(txParams, secret)

        return nodeProvider.sendRawTransaction(signedTxBlob)
    }

    /**
     * 批量交易（dapp RPC：swtc_batchTransactions），对齐浏览器插件 send/return 语义。
     * - send：逐笔签名 + 广播，单笔失败不中断，返回 [{hash}, {error}, ...]
     * - return：只签名返回 blob 数组，不广播
     * 注意：本实现为 dapp-connect 库**唯一实现**（ccdao / jdid 等宿主共用）；
     * ccdao 的 SdkSwtcAdapter → app SwtcMiddleware 委托本类；jdid 等宿主直接装配本类。
     */
    override suspend fun batchTransactions(
        batchReq: JSONObject,
        origin: String
    ): JSONArray {
        Log.d(TAG, "batchTransactions from origin: $origin")

        val from = batchReq.getString("from")
        val mode = batchReq.optString("mode", "send")
        if (mode != "send" && mode != "return") {
            throw IllegalArgumentException("Unsupported batch mode: $mode")
        }
        val transfers = SwtcBatchTransactions.parseTransfers(batchReq.optJSONArray("transfers"))
        val createOrders = SwtcBatchTransactions.parseCreateOrders(batchReq.optJSONArray("createOrders"))
        val cancelOrders = SwtcBatchTransactions.parseCancelOrders(batchReq.optJSONArray("cancelOrders"))

        if (transfers.isEmpty() && createOrders.isEmpty() && cancelOrders.isEmpty()) {
            throw IllegalArgumentException(
                "At least one of transfers, createOrders, or cancelOrders must be non-empty"
            )
        }

        val accounts = accountProvider.accounts.first()
        val walletAccount =
            accounts.find { it.address == from }
                ?: throw IllegalArgumentException("Account not found in wallet: $from")
        if (walletAccount.chain.bip44Code != ChainType.SWTC.bip44Code) {
            throw IllegalArgumentException("Account is not a SWTC account: $from")
        }

        // 语义校验（对齐 app 层：金额 / currency-issuer / 地址 / type）
        transfers.forEach { if (!SwtcBatchTransactions.isValidTransfer(it)) throw IllegalArgumentException("Invalid batch transfers") }
        createOrders.forEach { if (!SwtcBatchTransactions.isValidCreateOrder(it)) throw IllegalArgumentException("Invalid batch createOrders") }
        cancelOrders.forEach { if (it.sequence < 0) throw IllegalArgumentException("Invalid batch cancelOrders") }

        val secret = secretProvider.getSecretForAddress(from, origin)
            ?: throw IllegalStateException("Failed to get secret for address: $from")

        val txs = SwtcBatchTransactions.buildTxs(from, transfers, createOrders, cancelOrders)

        val results = JSONArray()
        if (mode == "return") {
            val startSeq = nodeProvider.fetchSequence(from)
            txs.forEachIndexed { index, tx ->
                tx.put("Sequence", startSeq + index)
                results.put(WalletSdk.signTransaction(tx, secret))
            }
        } else {
            var currentSeq = nodeProvider.fetchSequence(from)
            for (tx in txs) {
                delay(200)
                tx.put("Sequence", currentSeq)
                try {
                    val blob = WalletSdk.signTransaction(tx, secret)
                    val hash = nodeProvider.sendRawTransaction(blob)
                    results.put(JSONObject().apply { put("hash", hash) })
                    currentSeq++
                } catch (e: Exception) {
                    results.put(JSONObject().apply { put("error", e.message ?: "failed") })
                    currentSeq = nodeProvider.fetchSequence(from)
                }
            }
        }
        return results
    }

}
