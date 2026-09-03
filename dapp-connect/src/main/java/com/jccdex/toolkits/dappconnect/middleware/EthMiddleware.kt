package com.jccdex.toolkits.dappconnect.middleware

import android.util.Log
import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.WalletAccount
import com.jccdex.toolkits.dappconnect.model.ChainNotSupportedException
import com.jccdex.toolkits.dappconnect.model.SignTransactionResult
import com.jccdex.toolkits.dappconnect.model.UnauthorizedException
import com.jccdex.toolkits.dappconnect.model.UserRejectedException
import com.jccdex.toolkits.dappconnect.provider.AccountProvider
import com.jccdex.toolkits.dappconnect.provider.ChainProvider
import com.jccdex.toolkits.dappconnect.provider.NodeProvider
import com.jccdex.toolkits.dappconnect.provider.SecretProvider
import com.jccdex.toolkits.wallet.sdk.WalletSdk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * ETH RPC Middleware - handles ETH-specific RPC methods.
 * This class is independent of Hilt and can be instantiated directly.
 */
class EthMiddleware(
    private val accountProvider: AccountProvider,
    private val secretProvider: SecretProvider,
    private val nodeProvider: NodeProvider,
    chainProvider: ChainProvider? = null,
    initialChain: ChainType = ChainType.BSC
) : IEthMiddleware {
    companion object {
        private const val TAG = "EthMiddleware"
    }

    @Volatile private var chainProvider: ChainProvider? = chainProvider

    @Volatile private var onAccountSwitched: ((String) -> Unit)? = null

    @Volatile private var requestAccountsCallback: RequestAccountsCallback? = null

    @Volatile private var transactionConfirmCallback: TransactionConfirmCallback? = null

    override fun setChainProvider(provider: ChainProvider?) {
        chainProvider = provider
    }

    // Current selected chain
    private val _currentChainType = MutableStateFlow(initialChain)
    override val currentChainType: StateFlow<ChainType> = _currentChainType.asStateFlow()

    /**
     * Set callback for account switched event
     */
    override fun setOnAccountSwitched(callback: (String) -> Unit) {
        onAccountSwitched = callback
    }

    /**
     * Set callback for user approval before returning accounts (EIP-1193 connect flow)
     */
    override fun setRequestAccountsCallback(callback: RequestAccountsCallback?) {
        requestAccountsCallback = callback
    }

    /**
     * Set callback for user confirmation before signing or sending transactions
     */
    override fun setTransactionConfirmCallback(callback: TransactionConfirmCallback?) {
        transactionConfirmCallback = callback
    }

    /**
     * Set current chain type (used for initialization)
     */
    override fun setCurrentChainType(chainType: ChainType) {
        _currentChainType.value = chainType
        Log.d(TAG, "Current chain type set to: ${chainType.name}")
    }

    /**
     * Handle eth_requestAccounts RPC call
     * Returns list of ETH addresses for the current chain (excluding HD root accounts)
     */
    override suspend fun requestAccounts(origin: String): JSONArray {
        Log.d(TAG, "requestAccounts called from origin: $origin, currentChain: ${_currentChainType.value.name}")

        // Require app-layer EIP-1193 connect approval (M-06).
        val cb =
            requestAccountsCallback
                ?: throw UserRejectedException("RequestAccountsCallback is not set")
        if (!cb.onRequestAccounts(origin)) {
            throw UserRejectedException("User rejected the requestAccounts request")
        }

        val accounts = accountProvider.accounts.first()
        val currentChain = _currentChainType.value

        // Filter accounts by current chain and exclude HD root accounts
        val chainAccounts =
            accounts.filter {
                val isCurrentChain = it.chain == currentChain
                val isNotHDRoot = !(it.isHD && it.parentId == null)
                isCurrentChain && isNotHDRoot
            }

        val result = JSONArray()
        chainAccounts.forEach { account ->
            result.put(account.address)
        }
        Log.d(TAG, "Returning ${result.length()} accounts for chain ${currentChain.name}")
        return result
    }

    /**
     * Handle eth_blockNumber RPC call
     * Returns the current block number
     */
    override suspend fun getBlockNumber(): String {
        Log.d(TAG, "getBlockNumber called")
        val chainType = _currentChainType.value
        return nodeProvider.getBlockNumber(chainType)
    }

    /**
     * Handle eth_chainId RPC call
     */
    override fun getChainId(): String {
        val chainId = _currentChainType.value.evmChainId ?: 1L
        return "0x${chainId.toString(16)}"
    }

    /**
     * Validate that the address exists in wallet and belongs to an EVM chain
     * @return The wallet account if valid
     * @throws IllegalArgumentException if address not found or not EVM
     */
    private suspend fun validateEvmAddress(address: String): WalletAccount {
        val accounts = accountProvider.accounts.first()
        val walletAccount =
            accounts.find { it.address.equals(address, ignoreCase = true) }
                ?: throw IllegalArgumentException("Address not found in wallet: $address")

        if (!walletAccount.chain.isEvmChain()) {
            throw IllegalArgumentException("Address is not an EVM address: $address")
        }
        return walletAccount
    }

    /**
     * Parse chainId from hex or decimal string format
     * @return The chainId as Long
     */
    private fun parseChainIdHex(chainIdStr: String): Long =
        if (chainIdStr.startsWith("0x", ignoreCase = true)) {
            chainIdStr.substring(2).toLong(16)
        } else {
            chainIdStr.toLong()
        }

    /**
     * Handle personal_sign RPC call
     * Signs a message with the specified address
     */
    override suspend fun personalSign(
        address: String,
        message: String,
        origin: String
    ): String {
        Log.d(TAG, "personalSign called from origin: $origin")
        validateEvmAddress(address)

        val cb =
            transactionConfirmCallback
                ?: throw UserRejectedException("TransactionConfirmCallback is not set")
        val request =
            TransactionRequest.SignMessage(
                chain = _currentChainType.value,
                origin = origin,
                address = address,
                message = message,
                type = SignType.PERSONAL_SIGN
            )
        if (!cb.onConfirm(request)) {
            throw UserRejectedException("User rejected the personalSign request")
        }

        Log.d(TAG, "Signing message")
        val privateKey =
            secretProvider.getPrivateKeyForAddress(address, origin)
                ?: throw UnauthorizedException("Password required to sign transaction")

        val params =
            JSONObject().apply {
                put("privateKey", privateKey)
                put("data", message)
            }

        return WalletSdk.personalSign(privateKey, message)
    }

    /**
     * Get encryption public key for an address
     */
    override suspend fun getEncryptionPublicKey(
        address: String,
        origin: String
    ): String {
        validateEvmAddress(address)

        val cb =
            transactionConfirmCallback
                ?: throw UserRejectedException("TransactionConfirmCallback is not set")
        val request =
            TransactionRequest.GetEncryptionPublicKey(
                chain = _currentChainType.value,
                origin = origin,
                address = address
            )
        if (!cb.onConfirm(request)) {
            throw UserRejectedException("User rejected the getEncryptionPublicKey request")
        }

        val privateKey =
            secretProvider.getPrivateKeyForAddress(address, origin)
                ?: throw UnauthorizedException("Password required to sign transaction")

        return WalletSdk.getEncryptionPublicKey(privateKey)
    }

    /**
     * Decrypt data for an address
     */
    override suspend fun decrypt(
        address: String,
        encryptedData: String,
        origin: String
    ): String {
        validateEvmAddress(address)

        val cb =
            transactionConfirmCallback
                ?: throw UserRejectedException("TransactionConfirmCallback is not set")
        val request =
            TransactionRequest.Decrypt(
                chain = _currentChainType.value,
                origin = origin,
                address = address,
                encryptedData = encryptedData
            )
        if (!cb.onConfirm(request)) {
            throw UserRejectedException("User rejected the decrypt request")
        }

        val privateKey =
            secretProvider.getPrivateKeyForAddress(address, origin)
                ?: throw UnauthorizedException("Password required to sign transaction")

        return WalletSdk.decrypt(privateKey, encryptedData)
    }

    /**
     * Recover address from personal signature
     */
    override suspend fun recoverPersonalSignature(
        message: String,
        signature: String
    ): String {
        return WalletSdk.recoverPersonalSignature(message, signature)
    }

    /**
     * Common implementation for signTypedData V1, V3 and V4
     */
    override suspend fun signTypedData(
        address: String,
        typedData: String,
        version: String,
        origin: String
    ): String {
        validateEvmAddress(address)

        val cb =
            transactionConfirmCallback
                ?: throw UserRejectedException("TransactionConfirmCallback is not set")
        val request =
            TransactionRequest.SignTypedData(
                chain = _currentChainType.value,
                origin = origin,
                address = address,
                typedData = typedData,
                version = version
            )
        if (!cb.onConfirm(request)) {
            throw UserRejectedException("User rejected the signTypedData request")
        }

        val privateKey =
            secretProvider.getPrivateKeyForAddress(address, origin)
                ?: throw UnauthorizedException("Password required to sign transaction")

        return WalletSdk.signTypedData(privateKey, typedData, version)
    }

    /**
     * Recover address from typed signature
     */
    suspend fun recoverTypedSignature(
        data: String,
        signature: String,
        version: String
    ): String {
        return WalletSdk.recoverTypedSignature(data, signature, version)
    }

    /**
     * Sign a transaction without sending it
     */
    override suspend fun signTransaction(
        txParams: JSONObject,
        origin: String
    ): SignTransactionResult {
        require(origin.isNotBlank()) { "origin must not be blank for signTransaction" }
        val from = txParams.getString("from")

        // Verify account exists in wallet
        val accounts = accountProvider.accounts.first()
        val walletAccount =
            accounts.find { it.address.equals(from, ignoreCase = true) }
                ?: throw IllegalArgumentException("Account not found in wallet: $from")

        // Validate account belongs to EVM chain
        if (!walletAccount.chain.isEvmChain()) {
            throw IllegalArgumentException("Account is not an EVM address: $from")
        }

        // Get chainId from transaction params, current chain state, or account's chain
        val chainType =
            if (txParams.has("chainId")) {
                val chainId = parseChainIdHex(txParams.getString("chainId"))
                Log.d(TAG, "Transaction chainId from params: $chainId")

                ChainType.entries.find { it.evmChainId == chainId } ?: run {
                    Log.w(TAG, "Unknown chainId: $chainId, using current chain: ${_currentChainType.value.name}")
                    _currentChainType.value
                }
            } else {
                Log.d(TAG, "No chainId in params, using current chain: ${_currentChainType.value.name}")
                _currentChainType.value
            }

        Log.d(TAG, "Processing transaction for chain: ${chainType.name}")

        val cb =
            transactionConfirmCallback
                ?: throw UserRejectedException("TransactionConfirmCallback is not set")
        val request =
            TransactionRequest.SendTransaction(
                chain = chainType,
                origin = origin,
                to = txParams.optString("to", null),
                value = txParams.optString("value", null),
                data = txParams.optString("data", null),
                gas = txParams.optString("gas", null),
                gasPrice = txParams.optString("gasPrice", null),
                nonce = txParams.optString("nonce", null),
                txParams = txParams
            )
        if (!cb.onConfirm(request)) {
            throw UserRejectedException("User rejected the signTransaction request")
        }

        // Get nonce if not provided
        if (!txParams.has("nonce")) {
            val nonce = nodeProvider.getTransactionCount(from, chainType)
            txParams.put("nonce", nonce)
        }

        // Helper function to check if a hex value is zero or empty
        fun isZeroOrEmpty(value: String?): Boolean {
            if (value.isNullOrEmpty()) return true
            val hexValue = if (value.startsWith("0x")) value.substring(2) else value
            if (hexValue.isEmpty()) return true
            return try {
                hexValue.toBigInteger(16) == BigInteger.ZERO
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
        }

        // Determine transaction type and handle gas pricing
        val isEip1559 = txParams.has("maxFeePerGas") || txParams.has("maxPriorityFeePerGas")

        if (isEip1559) {
            // EIP-1559 transaction
            txParams.put("type", "0x2")

            if (!txParams.has("maxPriorityFeePerGas") ||
                isZeroOrEmpty(txParams.optString("maxPriorityFeePerGas"))
            ) {
                // Fetch from network
                try {
                    val maxPriorityFee = nodeProvider.getMaxPriorityFeePerGas(chainType)
                    txParams.put("maxPriorityFeePerGas", maxPriorityFee)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Fallback to default if not supported
                    txParams.put("maxPriorityFeePerGas", "0x1")
                }
            }

            if (!txParams.has("maxFeePerGas") || isZeroOrEmpty(txParams.optString("maxFeePerGas"))) {
                txParams.put("maxFeePerGas", nodeProvider.getGasPrice(chainType))
            }

            txParams.remove("gasPrice")
        } else {
            // Legacy transaction
            if (!txParams.has("gasPrice") || isZeroOrEmpty(txParams.optString("gasPrice"))) {
                txParams.put("gasPrice", nodeProvider.getGasPrice(chainType))
            }
        }

        // Estimate gas if not provided
        if (!txParams.has("gas") && !txParams.has("gasLimit")) {
            try {
                val gasEstimate = nodeProvider.estimateGas(txParams, chainType)
                txParams.put("gas", gasEstimate)
                txParams.put("gasLimit", gasEstimate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // M-D7: fail closed instead of silently falling back to 21000 (simple-transfer value) —
                // a complex contract call would most likely run out of gas and lose fees unnoticed.
                throw IllegalStateException("Gas estimation failed for chain $chainType: ${e.message}")
            }
        } else {
            // Ensure both gas and gasLimit are set
            val gasValue = txParams.optString("gas", txParams.optString("gasLimit"))
            txParams.put("gas", gasValue)
            txParams.put("gasLimit", gasValue)
        }

        // Ensure chainId is in the transaction params for signing
        if (!txParams.has("chainId")) {
            val chainId =
                chainType.evmChainId
                    ?: throw IllegalStateException("Chain ${chainType.name} does not have an EVM chainId")
            txParams.put("chainId", "0x${chainId.toString(16)}")
            Log.d(TAG, "Added chainId to transaction: 0x${chainId.toString(16)} for chain: ${chainType.name}")
        }

        // Get private key
        val privateKey =
            secretProvider.getPrivateKeyForAddress(from, origin)
                ?: throw UnauthorizedException("Password required to sign transaction")

        // Sign transaction using WalletSdk
        val signedTx = WalletSdk.signEthTransaction(privateKey, txParams)
        return SignTransactionResult(signedTx, chainType)
    }

    /**
     * Handle eth_sendTransaction RPC call
     * Signs and sends a transaction. [origin] must be non-blank (DApp origin).
     */
    override suspend fun sendTransaction(
        txParams: JSONObject,
        origin: String
    ): String {
        require(origin.isNotBlank()) { "origin must not be blank for sendTransaction" }
        val result = signTransaction(txParams, origin)
        val hash = nodeProvider.broadcastTransaction(result.data, result.chain)
        Log.d(TAG, "Transaction submitted successfully")
        return hash
    }

    /**
     * Handle wallet_switchEthereumChain RPC call
     * Switch to the specified EVM chain
     *
     * @param chainIdHex Hexadecimal format chainId, e.g. "0x38"
     * @param origin DApp origin
     * @throws ChainNotSupportedException if chain is not supported (error code 4902)
     * @throws UserRejectedException if user rejects (error code 4001)
     */
    override suspend fun switchEthereumChain(
        chainIdHex: String,
        origin: String
    ) {
        Log.d(TAG, "switchEthereumChain called from origin: $origin, chainId: $chainIdHex")

        // Parse chainId
        val chainId =
            try {
                parseChainIdHex(chainIdHex)
            } catch (_: NumberFormatException) {
                throw IllegalArgumentException("Invalid chainId format: $chainIdHex")
            }

        // Find corresponding ChainType
        val targetChain =
            ChainType.entries.find { it.evmChainId == chainId }
                ?: throw ChainNotSupportedException(
                    chainId,
                    "Chain with id $chainId ($chainIdHex) is not supported"
                )

        // Verify it's an EVM chain
        if (!targetChain.isEvmChain()) {
            throw ChainNotSupportedException(
                chainId,
                "Chain ${targetChain.name} is not an EVM chain"
            )
        }

        val currentChain = _currentChainType.value
        Log.d(TAG, "Current chain: ${currentChain.name}, Target chain: ${targetChain.name}")

        // If already on target chain, return success immediately
        if (currentChain == targetChain) {
            Log.d(TAG, "Already on chain ${targetChain.name}, no switch needed")
            return
        }

        // Request user confirmation
        val provider =
            chainProvider
                ?: throw IllegalStateException("ChainProvider not set")

        val confirmed = provider.requestChainSwitch(currentChain, targetChain, origin)

        if (!confirmed) {
            throw UserRejectedException("User rejected the chain switch request")
        }

        // Execute chain switch
        Log.d(TAG, "Switching from ${currentChain.name} to ${targetChain.name}")
        _currentChainType.value = targetChain

        // Switch to target chain account
        val targetChainAccounts = getAccountsForChain(targetChain)
        if (targetChainAccounts.isEmpty()) {
            Log.w(TAG, "No accounts found for chain ${targetChain.name}")
            return
        }

        // Get current account
        val currentAccount = accountProvider.currentAccount.first()
        val currentAddress = currentAccount?.address

        // Try to find same address account on target chain
        val sameAddressAccount =
            if (currentAddress != null) {
                targetChainAccounts.find { it.address.equals(currentAddress, ignoreCase = true) }
            } else {
                null
            }

        // Prefer same address account, otherwise first account on target chain
        val targetAccount = sameAddressAccount ?: targetChainAccounts.first()
        Log.d(TAG, "Switching account on chain ${targetChain.name}")
        accountProvider.setCurrentAccount(targetAccount.id)

        // Notify external account switched
        onAccountSwitched?.invoke(targetAccount.address)

        Log.d(TAG, "Successfully switched to chain ${targetChain.name}")
    }

    /**
     * Get current chain's chainId (hexadecimal format)
     */
    fun getCurrentChainIdHex(): String {
        val chainId = _currentChainType.value.evmChainId ?: 1L
        return "0x${chainId.toString(16)}"
    }

    /**
     * Get accounts for the specified chain
     */
    suspend fun getAccountsForChain(chain: ChainType): List<WalletAccount> {
        val accounts = accountProvider.accounts.first()
        return accounts.filter {
            it.chain == chain && !(it.isHD && it.parentId == null)
        }
    }
}
