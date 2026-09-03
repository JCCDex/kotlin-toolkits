package com.jccdex.toolkits.dappconnect.model

import com.jccdex.toolkits.core.error.ToolkitException
import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.rpc.ErrorCodes

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
) : ToolkitException(message, ErrorCodes.CHAIN_NOT_SUPPORTED)

/**
 * Exception thrown when user rejects a request.
 */
class UserRejectedException(
    message: String
) : ToolkitException(message, ErrorCodes.USER_REJECTED)

/**
 * Exception thrown when the wallet is not authorized (e.g. password not provided).
 */
class UnauthorizedException(
    message: String
) : ToolkitException(message, ErrorCodes.UNAUTHORIZED)
