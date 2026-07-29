package com.jccdex.toolkits.vault

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.google.crypto.tink.subtle.Hex
import com.google.protobuf.ByteString
import com.jccdex.toolkits.vault.model.VaultPrivateKeyImport
import com.jccdex.toolkits.vault.security.AESCrypto
import com.jccdex.toolkits.vault.security.Argon2idKdf
import com.jccdex.toolkits.vault.serializer.VaultSerializer
import com.jccdex.toolkits.vault.util.wipe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.Locale.getDefault
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class VaultRepository private constructor(
    private val vaultStore: DataStore<Vault>
) {
    private val mutex = Mutex()

    // ── VaultSession ──

    class VaultSession(private val key: ByteArray) {
        fun derivedKey(): ByteArray = key.copyOf()

        fun destroy() {
            key.wipe()
        }
    }

    @Volatile
    private var vaultSession: VaultSession? = null
    val isUnlocked: Boolean get() = vaultSession != null

    fun lock() {
        vaultSession?.destroy()
        vaultSession = null
    }

    suspend fun unlock(password: ByteArray): Boolean {
        if (!hasPassword()) {
            password.wipe()
            return false
        }
        val data = vaultStore.data.first()
        val salt = data.password.salt.toByteArray()
        val params = Argon2idKdf.Params(data.password.iterations, data.password.memoryKib, data.password.parallelism)
        val key = Argon2idKdf.deriveKey(password, salt, params)
        if (!verifyProof(key, data.password)) {
            key.wipe()
            password.wipe()
            return false
        }
        vaultSession = VaultSession(key)
        password.wipe()
        return true
    }

    private fun verifyProof(
        key: ByteArray,
        env: PasswordEntry
    ): Boolean {
        return try {
            if (env.proofIv.isEmpty) {
                // New format: HMAC-SHA256
                MessageDigest.isEqual(computeProof(key), env.proofCt.toByteArray())
            } else {
                // Old format: AES-GCM encrypted password
                AESCrypto.decrypt(
                    env.proofIv.toByteArray(),
                    env.proofCt.toByteArray(),
                    key,
                    env.aad.toByteArray()
                )
                // AES-GCM auth is sufficient — wrong key → AEADBadTagException
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun computeProof(key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(PROOF_DOMAIN_SEPARATOR)
    }

    companion object {
        private val PROOF_DOMAIN_SEPARATOR = "CCDAO_VAULT_V1_PASSWORD_PROOF".toByteArray()

        @Volatile
        private var instance: VaultRepository? = null

        fun get(context: Context): VaultRepository {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val app = context.applicationContext
                val vs =
                    DataStoreFactory.create(
                        serializer = VaultSerializer(app),
                        produceFile = { app.dataStoreFile("vault.pb") }
                    )
                return VaultRepository(vs).also { instance = it }
            }
        }
    }

    suspend fun initializePassword(
        password: ByteArray,
        params: Argon2idKdf.Params =
            Argon2idKdf.Params(
                iterations = 3,
                memoryKiB = 64 * 1024,
                parallelism = 1
            )
    ) = mutex.withLock {
        if (hasPassword()) {
            password.wipe()
            return@withLock
        }
        val salt = Argon2idKdf.randomSalt()
        val aad = AESCrypto.VAULT_V1_AAD.toByteArray()
        val key = Argon2idKdf.deriveKey(password, salt, params)

        try {
            val proof = computeProof(key)
            val newEnv =
                PasswordEntry
                    .newBuilder()
                    .setSalt(ByteString.copyFrom(salt))
                    .setIterations(params.iterations)
                    .setMemoryKib(params.memoryKiB)
                    .setParallelism(params.parallelism)
                    .setAad(ByteString.copyFrom(aad))
                    .setProofIv(ByteString.EMPTY)
                    .setProofCt(ByteString.copyFrom(proof))
                    .setHasBiometricCache(false)
                    .build()
            vaultStore.updateData {
                Vault
                    .newBuilder()
                    .setPassword(newEnv)
                    .setDerivedKey(Hex.encode(key))
                    .build()
            }
        } finally {
            key.wipe()
            password.wipe()
        }
    }

    suspend fun hasBiometric(): Boolean = vaultStore.data.first().hasBiometric()

    suspend fun clearBiometric() =
        mutex.withLock {
            vaultStore.updateData { vault ->
                vault
                    .toBuilder()
                    .clearBiometric()
                    .build()
            }
        }

    suspend fun getBiometric(): BiometricEntry {
        if (!hasBiometric()) {
            throw Error("Biometric cache is not exist")
        }
        return vaultStore.data.first().biometric
    }

    suspend fun updateBiometric(
        ciphertext: ByteArray,
        iv: ByteArray
    ) = mutex.withLock {
        try {
            vaultStore.updateData { vault ->
                vault
                    .toBuilder()
                    .setBiometric(
                        BiometricEntry
                            .newBuilder()
                            .setIv(ByteString.copyFrom(iv))
                            .setCiphertext(ByteString.copyFrom(ciphertext))
                            .build()
                    )
                    .build()
            }
        } finally {
            iv.wipe()
            ciphertext.wipe()
        }
    }

    suspend fun hasPassword(): Boolean = vaultStore.data.first().hasPassword()

    suspend fun verifyPassword(password: ByteArray): Boolean {
        if (!hasPassword()) {
            password.wipe()
            return false
        }
        val data = vaultStore.data.first()
        val env = data.password
        val key = derivedKey()
        val valid =
            try {
                if (env.proofIv.isEmpty) {
                    verifyProof(key, env)
                } else {
                    val pt =
                        AESCrypto.decrypt(
                            env.proofIv.toByteArray(),
                            env.proofCt.toByteArray(),
                            key,
                            env.aad.toByteArray()
                        )
                    MessageDigest.isEqual(pt, password)
                }
            } catch (_: Throwable) {
                false
            }
        password.wipe()
        key.wipe()
        return valid
    }

    suspend fun importPrivateKey(
        address: String,
        privateKey: ByteArray
    ) = mutex.withLock {
        lockedImportPrivateKey(address, privateKey)
    }

    suspend fun importMnemonic(
        address: String,
        mnemonic: ByteArray,
        privateKey: ByteArray,
        pathPrefix: String = "m/44'/60'/0'/0/0",
        language: String = "english"
    ) = mutex.withLock {
        lockedImportPrivateKey(address, privateKey)

        if (addressInMnemonics(address)) {
            mnemonic.wipe()
            return@withLock
        }
        val key = derivedKey()
        val aad = getMnemonicAAD(address)
        val (iv, ct) = AESCrypto.encrypt(mnemonic, key, aad)
        try {
            vaultStore.updateData { vault ->
                val entry =
                    MnemonicEntry
                        .newBuilder()
                        .setAddress(address)
                        .setIv(ByteString.copyFrom(iv))
                        .setCiphertext(ByteString.copyFrom(ct))
                        .setDerivationPath(pathPrefix)
                        .setLang(language)
                        .build()
                vault
                    .toBuilder()
                    .addMnemonics(entry)
                    .build()
            }
        } finally {
            key.wipe()
            mnemonic.wipe()
        }
    }

    suspend fun importSecret(
        address: String,
        privateKey: ByteArray,
        secret: ByteArray
    ) = mutex.withLock {
        lockedImportPrivateKey(address, privateKey)
        if (addressInSecrets(address)) {
            secret.wipe()
            return@withLock
        }
        val key = derivedKey()
        val aad = getSecretAAD(address)
        val (iv, ct) = AESCrypto.encrypt(secret, key, aad)
        try {
            vaultStore.updateData { vault ->
                val entry =
                    SecretEntry
                        .newBuilder()
                        .setAddress(address)
                        .setIv(ByteString.copyFrom(iv))
                        .setCiphertext(ByteString.copyFrom(ct))
                        .build()
                vault
                    .toBuilder()
                    .addSecrets(entry)
                    .build()
            }
        } finally {
            key.wipe()
            secret.wipe()
        }
    }

    suspend fun importPrivateKeys(privateKeys: MutableList<VaultPrivateKeyImport>) {
        try {
            val keys =
                privateKeys
                    .filter { !addressInKeys(it.address) }
                    .distinctBy { it.address.lowercase(getDefault()) }
            val derivedKey = derivedKey()
            val entries = mutableListOf<PrivateKeyEntry>()
            for (key in keys) {
                val aad = getAddressAAD(address = key.address)
                val (iv, ct) = AESCrypto.encrypt(key.privateKey, derivedKey, aad)
                val entry =
                    PrivateKeyEntry
                        .newBuilder()
                        .setAddress(key.address)
                        .setIv(ByteString.copyFrom(iv))
                        .setCiphertext(ByteString.copyFrom(ct))
                        .build()
                entries.add(entry)
            }

            try {
                vaultStore.updateData { vault ->
                    vault.toBuilder().addAllKeys(entries).build()
                }
            } finally {
                derivedKey.wipe()
            }
        } finally {
            privateKeys.forEach { it.privateKey.wipe() }
        }
    }

    suspend fun removeAddress(
        address: String,
        password: ByteArray
    ) = mutex.withLock {
        if (!verifyPassword(password)) {
            throw IllegalArgumentException("Password is wrong")
        }

        vaultStore.updateData { vault ->
            val keyIndex = vault.keysList.indexOfFirst { it.address.equals(address, true) }
            val mnemonicIndex =
                vault.mnemonicsList.indexOfFirst { it.address.equals(address, true) }
            val secretIndex = vault.secretsList.indexOfFirst { it.address.equals(address, true) }
            val builder = vault.toBuilder()
            if (keyIndex >= 0) {
                builder.removeKeys(keyIndex)
            }
            if (mnemonicIndex >= 0) {
                builder.removeMnemonics(mnemonicIndex)
            }
            if (secretIndex >= 0) {
                builder.removeSecrets(secretIndex)
            }
            builder.build()
        }
    }

    suspend fun listAccounts(): List<String> =
        vaultStore.data
            .first()
            .keysList
            .map { it.address }

    suspend fun getPrivateKey(
        address: String,
        password: ByteArray
    ): ByteArray {
        val verified = verifyPassword(password = password)
        if (!verified) {
            throw IllegalArgumentException("Password is wrong")
        }
        if (!addressInKeys(address)) {
            throw IllegalArgumentException("Private key is not exist")
        }
        val data = vaultStore.data.first()
        val entry = data.keysList.first { it.address.equals(address, true) }
        val key = derivedKey()
        try {
            val aad = getAddressAAD(address = address)
            return AESCrypto.decrypt(
                entry.iv.toByteArray(),
                entry.ciphertext.toByteArray(),
                key,
                aad
            )
        } finally {
            key.wipe()
        }
    }

    suspend fun getSecret(
        address: String,
        password: ByteArray
    ): ByteArray {
        val verified = verifyPassword(password = password)
        if (!verified) {
            throw IllegalArgumentException("Password is wrong")
        }
        if (!addressInSecrets(address)) {
            throw IllegalArgumentException("Secret is not exist")
        }
        val data = vaultStore.data.first()
        val entry = data.secretsList.first { it.address.equals(address, true) }
        val key = derivedKey()
        try {
            val aad = getSecretAAD(address = address)
            return AESCrypto.decrypt(
                entry.iv.toByteArray(),
                entry.ciphertext.toByteArray(),
                key,
                aad
            )
        } finally {
            key.wipe()
        }
    }

    suspend fun getMnemonic(
        address: String,
        password: ByteArray
    ): ByteArray {
        val verified = verifyPassword(password = password)
        if (!verified) {
            throw IllegalArgumentException("Password is wrong")
        }
        return getMnemonicInternal(address)
    }

    suspend fun getMnemonicLanguage(address: String): String {
        if (!addressInMnemonics(address)) {
            throw IllegalArgumentException("Mnemonic is not exist")
        }
        val data = vaultStore.data.first()
        val entry = data.mnemonicsList.first { it.address.equals(address, true) }
        return entry.lang
    }

    suspend fun getMnemonicInternal(address: String): ByteArray {
        if (!addressInMnemonics(address)) {
            throw IllegalArgumentException("Mnemonic is not exist")
        }
        val data = vaultStore.data.first()
        val key = derivedKey()
        try {
            val entry = data.mnemonicsList.first { it.address.equals(address, true) }
            val aad = getMnemonicAAD(address)
            val pt =
                AESCrypto.decrypt(entry.iv.toByteArray(), entry.ciphertext.toByteArray(), key, aad)
            return pt
        } finally {
            key.wipe()
        }
    }

    suspend fun addressInKeys(address: String): Boolean =
        vaultStore.data
            .first()
            .keysList
            .any { it.address.equals(address, true) }

    suspend fun getPrivateKeyInternal(address: String): ByteArray {
        if (!addressInKeys(address)) {
            throw IllegalArgumentException("Private key is not exist")
        }
        val data = vaultStore.data.first()
        val entry = data.keysList.first { it.address.equals(address, true) }
        val key = derivedKey()
        try {
            val aad = getAddressAAD(address = address)
            return AESCrypto.decrypt(
                entry.iv.toByteArray(),
                entry.ciphertext.toByteArray(),
                key,
                aad
            )
        } finally {
            key.wipe()
        }
    }

    suspend fun addressInMnemonics(address: String): Boolean =
        vaultStore.data
            .first()
            .mnemonicsList
            .any { it.address.equals(address, true) }

    suspend fun addressInSecrets(address: String): Boolean =
        vaultStore.data
            .first()
            .secretsList
            .any { it.address.equals(address, true) }

    suspend fun changePassword(
        oldPassword: ByteArray,
        newPassword: ByteArray,
        params: Argon2idKdf.Params =
            Argon2idKdf.Params(
                iterations = 3,
                memoryKiB = 64 * 1024,
                parallelism = 1
            )
    ) = mutex.withLock {
        if (!verifyPassword(oldPassword)) {
            newPassword.wipe()
            throw IllegalArgumentException("Password is wrong")
        }

        try {
            val data = vaultStore.data.first()
            val key = derivedKey()
            val salt = Argon2idKdf.randomSalt()
            val newKey = Argon2idKdf.deriveKey(newPassword, salt, params)
            val vault = Vault.newBuilder()
            try {
                val aad = data.password.aad.toByteArray()
                val proof = computeProof(newKey)
                vault.setPassword(
                    PasswordEntry
                        .newBuilder()
                        .setSalt(ByteString.copyFrom(salt))
                        .setIterations(params.iterations)
                        .setMemoryKib(params.memoryKiB)
                        .setParallelism(params.parallelism)
                        .setAad(ByteString.copyFrom(aad))
                        .setProofIv(ByteString.EMPTY)
                        .setProofCt(ByteString.copyFrom(proof))
                        .setHasBiometricCache(false)
                        .build()
                )
                vault.setDerivedKey(Hex.encode(newKey))

                for (e in data.keysList) {
                    val aadKey = getAddressAAD(address = e.address)
                    val pk =
                        AESCrypto.decrypt(e.iv.toByteArray(), e.ciphertext.toByteArray(), key, aadKey)
                    val (eIv, eCt) = AESCrypto.encrypt(pk, newKey, aadKey)
                    vault
                        .addKeys(
                            PrivateKeyEntry
                                .newBuilder()
                                .setAddress(e.address)
                                .setIv(ByteString.copyFrom(eIv))
                                .setCiphertext(ByteString.copyFrom(eCt))
                                .build()
                        )
                }

                for (s in data.secretsList) {
                    val aadSecret = getSecretAAD(address = s.address)
                    val pk =
                        AESCrypto.decrypt(s.iv.toByteArray(), s.ciphertext.toByteArray(), key, aadSecret)
                    val (sIv, sCt) = AESCrypto.encrypt(pk, newKey, aadSecret)
                    vault
                        .addSecrets(
                            SecretEntry
                                .newBuilder()
                                .setAddress(s.address)
                                .setIv(ByteString.copyFrom(sIv))
                                .setCiphertext(ByteString.copyFrom(sCt))
                                .build()
                        )
                }

                for (m in data.mnemonicsList) {
                    val aadMn = getMnemonicAAD(m.address)
                    val pt =
                        AESCrypto.decrypt(m.iv.toByteArray(), m.ciphertext.toByteArray(), key, aadMn)
                    val (mIv, mCt) = AESCrypto.encrypt(pt, newKey, aadMn)
                    vault.addMnemonics(
                        MnemonicEntry
                            .newBuilder()
                            .setAddress(m.address)
                            .setDerivationPath(m.derivationPath)
                            .setIv(ByteString.copyFrom(mIv))
                            .setCiphertext(ByteString.copyFrom(mCt))
                            .setLang(m.lang)
                            .setHint(m.hint)
                            .build()
                    )
                }
                val newData = vault.build()
                vaultStore.updateData { newData }
            } finally {
                key.wipe()
                newKey.wipe()
                vault.clear()
            }
        } finally {
            newPassword.wipe()
        }
    }

    private suspend fun derivedKey(): ByteArray =
        vaultSession?.derivedKey() ?: Hex.decode(vaultStore.data.first().derivedKey)

    private suspend fun lockedImportPrivateKey(
        address: String,
        privateKey: ByteArray
    ) {
        if (addressInKeys(address)) {
            privateKey.wipe()
            return
        }
        val key = derivedKey()
        val aad = getAddressAAD(address = address)
        val (iv, ct) = AESCrypto.encrypt(privateKey, key, aad)
        try {
            vaultStore.updateData { vault ->
                val entry =
                    PrivateKeyEntry
                        .newBuilder()
                        .setAddress(address)
                        .setIv(ByteString.copyFrom(iv))
                        .setCiphertext(ByteString.copyFrom(ct))
                        .build()
                vault
                    .toBuilder()
                    .addKeys(entry)
                    .build()
            }
        } finally {
            key.wipe()
            privateKey.wipe()
        }
    }

    private fun getMnemonicAAD(address: String): ByteArray = "mnemonic:${address.lowercase()}".toByteArray()

    private fun getAddressAAD(address: String): ByteArray = "address:${address.lowercase()}".toByteArray()

    private fun getSecretAAD(address: String): ByteArray = "secret:${address.lowercase()}".toByteArray()

    suspend fun clearAllData() =
        mutex.withLock {
            lock()
            vaultStore.updateData {
                Vault.getDefaultInstance()
            }
        }
}
