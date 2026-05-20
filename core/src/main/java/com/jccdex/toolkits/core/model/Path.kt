package com.jccdex.toolkits.core.model

/**
 * BIP44 derivation path segment.
 */
data class Path(
    val chain: Long,
    val account: Int = 0,
    val change: Int = 0,
    val index: Int = 0
) {
    fun isRoot(): Boolean = account == 0 && change == 0 && index == 0

    companion object {
        fun root(chainType: ChainType): Path = Path(chain = chainType.bip44Code)
    }

    override fun toString(): String = "m/44'/$chain'/$account'/$change/$index"
}
