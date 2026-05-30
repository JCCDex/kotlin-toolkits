package com.jccdex.toolkits.vault.serializer

import android.content.Context
import androidx.datastore.core.Serializer
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
        val pt = TinkManager.get(appContext).decrypt(blob, AESCrypto.VAULT_V1_AAD.toByteArray())
        return Vault.parseFrom(pt)
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
