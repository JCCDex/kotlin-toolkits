package com.jccdex.toolkits.dappconnect.middleware

import android.util.Log
import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.WalletAccount
import com.jccdex.toolkits.dappconnect.provider.AccountProvider
import com.jccdex.toolkits.dappconnect.provider.NodeProvider
import com.jccdex.toolkits.dappconnect.provider.SecretProvider
import com.jccdex.toolkits.wallet.sdk.WalletSdk
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

    /**
     * Handle swtc_requestAccounts RPC call
     * Returns list of available SWTC addresses (excluding HD root accounts)
     */
    override suspend fun requestAccounts(origin: String): JSONArray {
        Log.d(TAG, "requestAccounts called from origin: $origin")

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

        Log.d(TAG, "Processing transaction for account: $account")

        // Get sequence if not provided
        if (!txParams.has("Sequence")) {
            val sequence = nodeProvider.fetchSequence(account)
            txParams.put("Sequence", sequence)
        }

        // Get secret
        val secret = secretProvider.getSecretForAddress(account, origin)
            ?: throw IllegalStateException("Failed to get secret for address: $account")

        // Sign transaction using WalletSdk
        val blob = WalletSdk.signSwtcTransaction(txParams, secret)

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

        Log.d(TAG, "Processing multi-sign for account: $account")

        // Get secret for signing
        val secret = secretProvider.getSecretForAddress(account, origin)
            ?: throw IllegalStateException("Failed to get secret for address: $account")

        // Get tx from multi-sign parameters
        val tx = msParams.getJSONObject("tx")

        // Sign using WalletSdk
        val result = WalletSdk.multiSign(tx, secret)

        Log.d(TAG, "Multi-sign result: $result")
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
        Log.d(TAG, "signMessage called from origin: $origin, from: $from")

        // Verify address exists in wallet
        val accounts = accountProvider.accounts.first()
        val walletAccount =
            accounts.find { it.address == from }
                ?: throw IllegalArgumentException("Address not found in wallet: $from")

        // Validate address belongs to SWTC chain
        if (walletAccount.chain.bip44Code != ChainType.SWTC.bip44Code) {
            throw IllegalArgumentException("Address is not a SWTC address: $from")
        }

        Log.d(TAG, "Signing message for address: $from")

        // Get secret for signing
        val secret = secretProvider.getSecretForAddress(from, origin)
            ?: throw IllegalStateException("Failed to get secret for address: $from")

        // Sign message using WalletSdk
        val signature = WalletSdk.signMessage(from, data, secret)

        Log.d(TAG, "Message signing result: $signature")
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
        Log.d(TAG, "getPublicKey called from origin: $origin, address: $address")

        // Verify address exists in wallet
        val accounts = accountProvider.accounts.first()
        val walletAccount =
            accounts.find { it.address == address }
                ?: throw IllegalArgumentException("Address not found in wallet: $address")

        // Validate address belongs to SWTC chain
        if (walletAccount.chain.bip44Code != ChainType.SWTC.bip44Code) {
            throw IllegalArgumentException("Address is not a SWTC address: $address")
        }

        Log.d(TAG, "Getting public key for address: $address")

        // Return the public key from wallet account
        val publicKey = walletAccount.publicKey

        Log.d(TAG, "Public key retrieved for address: $address")
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

        val blob = WalletSdk.signSwtcTransaction(txParams, secret)

        return nodeProvider.sendRawTransaction(blob)
    }

    /**
     * Send NFT transaction with password (for native UI usage)
     */
    suspend fun sendNftTransactionWithPassword(
        address: String,
        to: String,
        tokenId: String,
        memo: String,
        password: String
    ): String {
        Log.d(TAG, "sendNftTransactionWithPassword NFT transfer: address=$address to=$to tokenId=$tokenId")

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
        Log.d(TAG, "Raw NFT transaction: $txParams")

        // Get sequence
        if (!txParams.has("Sequence")) {
            val sequence = nodeProvider.fetchSequence(address)
            txParams.put("Sequence", sequence)
        }

        Log.d(TAG, "NFT transaction with sequence: $txParams")

        val secret = secretProvider.getSecretForAddress(address, "wallet_internal")
            ?: throw IllegalStateException("Failed to get secret for address: $address")

        val signedTxBlob = WalletSdk.signSwtcTransaction(txParams, secret)

        Log.d(TAG, "Signed NFT transaction blob length: ${signedTxBlob.length}")

        return nodeProvider.sendRawTransaction(signedTxBlob)
    }
}
