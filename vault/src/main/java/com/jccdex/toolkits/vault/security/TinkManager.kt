package com.jccdex.toolkits.vault.security

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * AEAD for encrypting the on-disk vault blob. Keyset names match the historical CCDAO connector
 * defaults so apps can migrate to this library without breaking existing [vault.pb] files.
 */
object TinkManager {
    private const val KEYSET_PREF_NAME = "tink_keyset_prefs"
    private const val KEYSET_NAME = "eth_aead_keyset"
    private const val MASTER_KEY_URI = "android-keystore://tink_master_key"

    @Volatile
    private var aead: Aead? = null

    fun get(context: Context): Aead {
        aead?.let { return it }
        synchronized(this) {
            aead?.let { return it }
            AeadConfig.register()
            val handle =
                AndroidKeysetManager
                    .Builder()
                    .withSharedPref(context, KEYSET_NAME, KEYSET_PREF_NAME)
                    .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                    .withMasterKeyUri(MASTER_KEY_URI)
                    .build()
                    .keysetHandle

            val primitive = handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)

            aead = primitive
            return primitive
        }
    }
}
