package com.jccdex.toolkits.vault.security

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AESCrypto {
    private const val TRANS = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    const val VAULT_V1_AAD = "vault:v1"

    fun encrypt(
        plaintext: ByteArray,
        key: ByteArray,
        aad: ByteArray?
    ): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANS)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.iv to cipher.doFinal(plaintext)
    }

    fun decrypt(
        iv: ByteArray,
        ciphertext: ByteArray,
        key: ByteArray,
        aad: ByteArray?
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANS)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
}
