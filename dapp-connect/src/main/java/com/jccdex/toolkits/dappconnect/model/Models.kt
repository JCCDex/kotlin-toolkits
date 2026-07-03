package com.jccdex.toolkits.dappconnect.model

import com.jccdex.toolkits.core.model.ChainType

/**
 * Result of signing a transaction.
 */
data class SignTransactionResult(
    val data: String,
    val chain: ChainType
)

/**
 * Exception thrown when requested chain is not supported.
 */
class ChainNotSupportedException(
    val chainId: Long,
    message: String
) : Exception(message) {
    val errorCode = 4902 // EIP-3326 defined error code
}

/**
 * Exception thrown when user rejects a request.
 */
class UserRejectedException(
    message: String
) : Exception(message) {
    val errorCode = 4001 // EIP-1193 defined user rejection error code
}

/**
 * Exception thrown when account is not authorized.
 */
class UnauthorizedException(
    message: String = "Account not authorized"
) : Exception(message) {
    val errorCode = 4100 // EIP-1193 unauthorized error code
}

/**
 * Exception for transaction-related errors.
 */
class TransactionException(
    message: String,
    val code: Int = -32603 // Internal error
) : Exception(message)

/**
 * JSON-RPC error response.
 */
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null
)

/**
 * JSON-RPC response wrapper.
 */
sealed class JsonRpcResponse {
    abstract val id: Long
    abstract val jsonrpc: String

    data class Success(
        override val id: Long,
        val result: Any,
        override val jsonrpc: String = "2.0"
    ) : JsonRpcResponse()

    data class Error(
        override val id: Long,
        val error: JsonRpcError,
        override val jsonrpc: String = "2.0"
    ) : JsonRpcResponse()
}
