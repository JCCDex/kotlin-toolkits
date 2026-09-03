package com.jccdex.toolkits.dappconnect

import com.jccdex.toolkits.core.error.ToolkitException
import com.jccdex.toolkits.core.rpc.ErrorCodes
import java.util.Locale

/** Structured RPC error returned to DApp JavaScript. */
data class RpcError(
    val code: Int,
    val message: String
)

/**
 * Maps throwables from middleware / providers to EIP-1193–style RPC errors.
 */
fun Throwable.toRpcError(fallbackMessage: String): RpcError {
    if (this is ToolkitException) {
        val msg = message?.takeIf { it.isNotBlank() } ?: fallbackMessage
        return RpcError(errorCode, msg)
    }
    val msg = message?.takeIf { it.isNotBlank() } ?: fallbackMessage
    return when (this) {
        is IllegalArgumentException ->
            if (isPasswordOrAuthFailure(msg)) {
                RpcError(ErrorCodes.UNAUTHORIZED, msg)
            } else {
                RpcError(ErrorCodes.INVALID_PARAMS, msg)
            }
        is IllegalStateException ->
            when {
                isPasswordOrAuthFailure(msg) -> RpcError(ErrorCodes.UNAUTHORIZED, msg)
                msg.contains("not set", ignoreCase = true) ->
                    RpcError(ErrorCodes.INTERNAL_ERROR, msg)
                else -> RpcError(ErrorCodes.WALLET_ERROR, msg)
            }
        else -> RpcError(ErrorCodes.WALLET_ERROR, msg)
    }
}

private fun isPasswordOrAuthFailure(message: String): Boolean {
    val lower = message.lowercase(Locale.ROOT)
    return lower.contains("password") ||
        (lower.contains("secret") && lower.contains("required")) ||
        lower.contains("cancelled") ||
        lower.contains("canceled") ||
        lower.contains("unauthorized")
}
