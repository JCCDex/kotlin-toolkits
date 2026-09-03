package com.jccdex.toolkits.account.storage.room

/**
 * M-15A: thrown when an account row carries an unknown bip44 chain code — data corruption or a
 * future chain. Replaces the silent `ChainType.ETH` fallback so corrupt rows fail observably
 * instead of being routed to the wrong chain.
 */
class UnknownChainCodeException(
    val chainCode: Long
) : IllegalStateException("Unknown chain bip44 code: $chainCode")
