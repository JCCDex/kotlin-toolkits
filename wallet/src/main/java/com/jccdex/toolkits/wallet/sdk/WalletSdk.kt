package com.jccdex.toolkits.wallet.sdk

import android.content.Context
import com.google.gson.Gson
import com.jccdex.toolkits.wallet.model.GenerateHDWalletResult
import com.jccdex.toolkits.wallet.model.Mnemonic
import com.jccdex.toolkits.wallet.model.SubWallet
import com.jccdex.toolkits.wallet.model.TraditionalDeriveResult
import org.json.JSONObject

object WalletSdk {
    private val gson = Gson()

    @Volatile
    private var bridge: IWalletBridge? = null

    @Synchronized
    fun initialize(context: Context) {
        if (bridge == null) {
            bridge = AndroidWalletWebRuntime(context)
        }
    }

    fun start() {
        bridgeOrThrow().start()
    }

    fun destroy() {
        bridge?.destroy()
        bridge = null
    }

    internal fun installBridgeForTest(fakeBridge: IWalletBridge?) {
        bridge?.destroy()
        bridge = fakeBridge
    }

    suspend fun callJsMethod(
        method: String,
        params: JSONObject? = null,
        timeoutMs: Long = 30_000L,
        readyWaitMs: Long = 15_000L
    ): String = bridgeOrThrow().call(method, params, timeoutMs, readyWaitMs)

    suspend fun <T> callJsMethodAs(
        method: String,
        params: JSONObject? = null,
        clazz: Class<T>,
        timeoutMs: Long = 30_000L,
        readyWaitMs: Long = 15_000L
    ): T = bridgeOrThrow().callAs(method, params, clazz, timeoutMs, readyWaitMs)

    suspend fun validateMnemonic(
        mnemonic: String,
        language: String = "english"
    ): Boolean =
        callJsMethod(
            "validateMnemonic",
            JSONObject().apply {
                put("mnemonic", mnemonic)
                put("language", language)
            }
        ).toBoolean()

    suspend fun generateMnemonic(
        length: Int = 128,
        language: String = "english"
    ): Mnemonic =
        parse(
            callJsMethod(
                "generateMnemonic",
                JSONObject().apply {
                    put("length", length)
                    put("language", language)
                }
            )
        )

    suspend fun deriveChild(
        mnemonic: String,
        chain: Long,
        account: Int = 0,
        change: Int = 0,
        index: Int = 0,
        language: String = "english"
    ): SubWallet =
        callJsMethodAs(
            "deriveChild",
            JSONObject().apply {
                put("mnemonic", mnemonic)
                put("chain", chain)
                put("account", account)
                put("change", change)
                put("index", index)
                put("language", language)
            },
            SubWallet::class.java
        )

    suspend fun hdWalletFromMnemonic(
        mnemonic: String,
        chains: List<Long> = emptyList(),
        language: String = "english"
    ): GenerateHDWalletResult =
        callJsMethodAs(
            "hdWalletFromMnemonic",
            JSONObject().apply {
                put("mnemonic", mnemonic)
                put("chains", chains)
                put("language", language)
            },
            GenerateHDWalletResult::class.java
        )

    suspend fun deriveFromMnemonic(
        mnemonic: String,
        chain: Long,
        account: Int = 0,
        change: Int = 0,
        index: Int = 0,
        language: String = "english"
    ): TraditionalDeriveResult =
        callJsMethodAs(
            "deriveFromMnemonic",
            JSONObject().apply {
                put("mnemonic", mnemonic)
                put("chain", chain)
                put("account", account)
                put("change", change)
                put("index", index)
                put("language", language)
            },
            TraditionalDeriveResult::class.java
        )

    suspend fun deriveFromPrivateKey(
        privateKey: String,
        chain: Long
    ): TraditionalDeriveResult =
        callJsMethodAs(
            "deriveFromPrivateKey",
            JSONObject().apply {
                put("privateKey", privateKey)
                put("chain", chain)
            },
            TraditionalDeriveResult::class.java
        )

    suspend fun validatePrivateKey(
        privateKey: String,
        chain: Long
    ): Boolean =
        callJsMethod(
            "validatePrivateKey",
            JSONObject().apply {
                put("privateKey", privateKey)
                put("chain", chain)
            }
        ).toBoolean()

    suspend fun buildSwtcPayment(
        address: String,
        amount: String,
        to: String,
        token: String,
        memo: String
    ): String =
        callJsMethod(
            "buildSwtcPayment",
            JSONObject().apply {
                put("address", address)
                put("amount", amount)
                put("to", to)
                put("token", token)
                put("memo", memo)
            }
        )

    suspend fun buildSwtcNftTransfer(
        address: String,
        to: String,
        tokenId: String,
        memo: String
    ): String =
        callJsMethod(
            "buildSwtcNftTransfer",
            JSONObject().apply {
                put("address", address)
                put("to", to)
                put("tokenId", tokenId)
                put("memo", memo)
            }
        )

    suspend fun buildSwtcCreateOrder(
        address: String,
        amount: String,
        base: String,
        counter: String,
        sum: String,
        type: String,
        platform: String? = null,
        issuer: String? = null
    ): String =
        callJsMethod(
            "buildSwtcCreateOrder",
            JSONObject().apply {
                put("address", address)
                put("amount", amount)
                put("base", base)
                put("counter", counter)
                put("sum", sum)
                put("type", type)
                platform?.let { put("platform", it) }
                issuer?.let { put("issuer", it) }
            }
        )

    suspend fun buildSwtcCancelOrder(
        address: String,
        sequence: Long
    ): String =
        callJsMethod(
            "buildSwtcCancelOrder",
            JSONObject().apply {
                put("address", address)
                put("sequence", sequence)
            }
        )

    suspend fun signSwtcTransaction(
        tx: JSONObject,
        secret: String
    ): String =
        callJsMethod(
            "signSwtcTransaction",
            JSONObject().apply {
                put("tx", tx)
                put("secret", secret)
            }
        )

    suspend fun isValidAddress(address: String): Boolean =
        callJsMethod(
            "isValidAddress",
            JSONObject().apply { put("address", address) }
        ).toBoolean()

    suspend fun signMessage(
        address: String,
        message: String,
        secret: String
    ): String =
        callJsMethod(
            "signMessage",
            JSONObject().apply {
                put("address", address)
                put("message", message)
                put("secret", secret)
            }
        )

    suspend fun signTransaction(
        tx: JSONObject,
        secret: String
    ): String =
        callJsMethod(
            "signTransaction",
            JSONObject().apply {
                put("tx", tx)
                put("secret", secret)
            }
        )

    suspend fun multiSign(
        tx: JSONObject,
        secret: String
    ): String =
        callJsMethod(
            "multiSign",
            JSONObject().apply {
                put("tx", tx)
                put("secret", secret)
            }
        )

    suspend fun personalSign(
        privateKey: String,
        data: String
    ): String =
        callJsMethod(
            "personalSign",
            JSONObject().apply {
                put("privateKey", privateKey)
                put("data", data)
            }
        )

    suspend fun signTypedData(
        privateKey: String,
        data: String,
        version: String
    ): String =
        callJsMethod(
            "signTypedData",
            JSONObject().apply {
                put("privateKey", privateKey)
                put("data", data)
                put("version", version)
            }
        )

    suspend fun recoverTypedSignature(
        data: String,
        signature: String,
        version: String
    ): String =
        callJsMethod(
            "recoverTypedSignature",
            JSONObject().apply {
                put("data", data)
                put("signature", signature)
                put("version", version)
            }
        )

    suspend fun recoverPersonalSignature(
        data: String,
        signature: String
    ): String =
        callJsMethod(
            "recoverPersonalSignature",
            JSONObject().apply {
                put("data", data)
                put("signature", signature)
            }
        )

    suspend fun getEncryptionPublicKey(privateKey: String): String =
        callJsMethod(
            "getEncryptionPublicKey",
            JSONObject().apply { put("privateKey", privateKey) }
        )

    suspend fun decrypt(
        privateKey: String,
        data: String
    ): String =
        callJsMethod(
            "decrypt",
            JSONObject().apply {
                put("privateKey", privateKey)
                put("data", data)
            }
        )

    suspend fun signEthTransaction(
        privateKey: String,
        tx: JSONObject
    ): String =
        callJsMethod(
            "signEthTransaction",
            JSONObject().apply {
                put("privateKey", privateKey)
                put("tx", tx)
            }
        )

    private fun bridgeOrThrow(): IWalletBridge =
        bridge ?: throw IllegalStateException("WalletSdk is not initialized. Call initialize(context) first.")

    private inline fun <reified T> parse(raw: String): T = gson.fromJson(raw, T::class.java)
}
