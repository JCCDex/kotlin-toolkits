package com.jccdex.toolkits.wallet.model

import com.jccdex.toolkits.core.model.Path

data class Keypair(
    val privateKey: String,
    val publicKey: String
) {
    // Mask the private key in logs/crash reports (M-W7). equals/hashCode stay field-based.
    override fun toString(): String = "Keypair(privateKey=***, publicKey=$publicKey)"
}

data class Mnemonic(
    val value: String,
    val language: String
) {
    override fun toString(): String = "Mnemonic(value=***, language=$language)"
}

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
) {
    override fun toString(): String =
        "GenerateHDWalletResult(mnemonic=***, address=$address, language=$language, " +
            "keypair=$keypair, accounts=$accounts)"
}

data class TraditionalDeriveResult(
    val address: String,
    val keypair: Keypair,
    val mnemonic: Mnemonic? = null,
    val secret: String? = null,
    val path: Path? = null,
    val sourcePrivateKey: String? = null
) {
    override fun toString(): String =
        "TraditionalDeriveResult(address=$address, keypair=$keypair, mnemonic=$mnemonic, " +
            "secret=${secret?.let { "***" }}, path=$path, sourcePrivateKey=${sourcePrivateKey?.let { "***" }})"
}
