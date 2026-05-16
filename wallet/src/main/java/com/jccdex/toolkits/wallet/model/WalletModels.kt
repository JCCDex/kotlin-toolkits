package com.jccdex.toolkits.wallet.model

data class Keypair(
    val privateKey: String,
    val publicKey: String
)

data class Path(
    val chain: Long,
    val account: Int = 0,
    val change: Int = 0,
    val index: Int = 0
) {
    override fun toString(): String = "m/44'/$chain'/$account'/$change/$index"
}

data class Mnemonic(
    val value: String,
    val language: String
)

data class SubWallet(
    val chain: Long,
    val address: String,
    val path: Path,
    val keypair: Keypair
)

data class GenerateHDWalletResult(
    val mnemonic: String,
    val address: String,
    val language: String,
    val keypair: Keypair,
    val accounts: List<SubWallet> = emptyList()
)

data class TraditionalDeriveResult(
    val address: String,
    val keypair: Keypair,
    val mnemonic: Mnemonic? = null,
    val secret: String? = null,
    val path: Path? = null,
    val sourcePrivateKey: String? = null
)
