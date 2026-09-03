package com.jccdex.toolkits.core.error

/**
 * Base exception for toolkit modules that carry a stable RPC / UI error code.
 */
open class ToolkitException(
    message: String,
    val errorCode: Int,
    cause: Throwable? = null
) : Exception(message, cause)
