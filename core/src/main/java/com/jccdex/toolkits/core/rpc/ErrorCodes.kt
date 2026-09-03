package com.jccdex.toolkits.core.rpc

/**
 * EIP-1193 / JSON-RPC error codes shared across toolkit modules.
 *
 * @see <a href="https://eips.ethereum.org/EIPS/eip-1193">EIP-1193</a>
 */
object ErrorCodes {
    // EIP-1193 provider errors
    const val USER_REJECTED = 4001
    const val UNAUTHORIZED = 4100
    const val UNSUPPORTED_METHOD = 4200
    const val DISCONNECTED = 4900
    const val CHAIN_DISCONNECTED = 4901
    const val CHAIN_NOT_SUPPORTED = 4902

    // JSON-RPC 2.0 standard errors
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    /** Generic wallet / signing failure (MetaMask-style 32000 range). */
    const val WALLET_ERROR = 32000
}
