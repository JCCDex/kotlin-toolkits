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

    /**
     * Display form `m/44'/<coinType>'/<account>'/<change>/<index>`.
     *
     * [chain] carries the BIP44 hardened bit ([ChainType.bip44Code] = `slip44 or 0x8000_0000`),
     * so it is masked off for display — mirroring the JS wallet bridge
     * (`path.chain & 0x7FFFFFFF`) and Swift `Path.derivationPath`
     * (`let displayChain = self.chain & 0x7FFF_FFFF`). This also keeps persisted
     * `derivationPath` values aligned with `VaultRepository`'s `m/44'/60'/0'/0/0` convention.
     */
    override fun toString(): String {
        val displayChain = chain and 0x7FFF_FFFFL
        return "m/44'/$displayChain'/$account'/$change/$index"
    }
}
