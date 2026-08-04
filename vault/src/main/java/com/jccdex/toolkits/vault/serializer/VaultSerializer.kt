package com.jccdex.toolkits.vault.serializer

import android.content.Context
import androidx.datastore.core.Serializer
import com.google.protobuf.CodedInputStream
import com.jccdex.toolkits.vault.Vault
import com.jccdex.toolkits.vault.security.AESCrypto
import com.jccdex.toolkits.vault.security.TinkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class VaultSerializer(
    private val appContext: Context
) : Serializer<Vault> {
    override val defaultValue: Vault = Vault.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): Vault {
        val blob = input.readBytes()
        if (blob.isEmpty()) return defaultValue
        // Reject oversized ciphertext before decrypt (M-04).
        if (blob.size > MAX_VAULT_SIZE) return defaultValue
        val pt = TinkManager.get(appContext).decrypt(blob, AESCrypto.VAULT_V1_AAD.toByteArray())
        if (pt.size > MAX_VAULT_SIZE) return defaultValue
        val coded = CodedInputStream.newInstance(pt)
        coded.setSizeLimit(MAX_VAULT_SIZE)
        val vault =
            try {
                Vault.parseFrom(coded)
            } catch (_: Exception) {
                return defaultValue
            }
        // Cap repeated entry counts so a crafted proto cannot allocate unbounded lists.
        if (vault.keysCount > MAX_ENTRIES ||
            vault.mnemonicsCount > MAX_ENTRIES ||
            vault.secretsCount > MAX_ENTRIES
        ) {
            return defaultValue
        }
        return vault
    }

    companion object {
        private const val MAX_VAULT_SIZE = 10 * 1024 * 1024 // 10MB
        private const val MAX_ENTRIES = 1_024
    }

    override suspend fun writeTo(
        t: Vault,
        output: OutputStream
    ) {
        val ct =
            TinkManager
                .get(appContext)
                .encrypt(t.toByteArray(), AESCrypto.VAULT_V1_AAD.toByteArray())
        withContext(Dispatchers.IO) {
            output.write(ct)
        }
    }
}
