package com.jccdex.toolkits.vault.model

/**
 * One address / private key pair for [com.jccdex.toolkits.vault.VaultRepository.importPrivateKeys].
 */
data class VaultPrivateKeyImport(
    val address: String,
    val privateKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VaultPrivateKeyImport

        if (address != other.address) return false
        if (!privateKey.contentEquals(other.privateKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + privateKey.contentHashCode()
        return result
    }
}
