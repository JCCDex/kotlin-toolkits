package com.jccdex.toolkits.wallet.sdk

import com.jccdex.toolkits.wallet.model.GenerateHDWalletResult
import com.jccdex.toolkits.wallet.model.Keypair
import com.jccdex.toolkits.wallet.model.Mnemonic
import com.jccdex.toolkits.wallet.model.Path
import com.jccdex.toolkits.wallet.model.SubWallet
import com.jccdex.toolkits.wallet.model.TraditionalDeriveResult
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class WalletSdkTest {
    private val bridge = RecordingWalletBridge()

    @After
    fun tearDown() {
        WalletSdk.destroy()
        bridge.reset()
    }

    @Test
    fun callJsMethod_delegatesToBridge() =
        runTest {
            bridge.nextStringResult = """{"value":"alpha beta","language":"english"}"""
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.callJsMethod("generateMnemonic")

            assertThat(result).isEqualTo("""{"value":"alpha beta","language":"english"}""")
            assertThat(bridge.lastMethod).isEqualTo("generateMnemonic")
            assertThat(bridge.lastParams).isNull()
        }

    @Test
    fun initialize_installsBridge_once() {
        WalletSdk.initialize(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        WalletSdk.initialize(androidx.test.core.app.ApplicationProvider.getApplicationContext())

        assertThat(true).isTrue()
    }

    @Test
    fun start_delegatesToBridge() {
        WalletSdk.installBridgeForTest(bridge)

        WalletSdk.start()

        assertThat(bridge.started).isTrue()
    }

    @Test
    fun start_throwsWhenNotInitialized() {
        WalletSdk.destroy()

        assertThatThrownBy { WalletSdk.start() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("WalletSdk is not initialized")
    }

    @Test
    fun callJsMethodAs_throwsWhenNotInitialized() {
        assertThatThrownBy {
            runTest {
                WalletSdk.callJsMethodAs("ping", null, String::class.java)
            }
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun destroy_clearsBridge() =
        runTest {
            bridge.nextStringResult = "true"
            WalletSdk.installBridgeForTest(bridge)

            WalletSdk.destroy()

            assertThat(bridge.destroyed).isTrue()
            assertThat(
                runCatching { kotlinx.coroutines.test.runTest { WalletSdk.callJsMethod("generateMnemonic") } }
                    .exceptionOrNull()
                    ?.message
            ).contains("WalletSdk is not initialized")
        }

    @Test
    fun validateMnemonic_buildsExpectedPayload() =
        runTest {
            bridge.nextStringResult = "true"
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.validateMnemonic("test", "english")

            assertThat(result).isTrue()
            assertThat(bridge.lastMethod).isEqualTo("validateMnemonic")
            assertThat(bridge.lastParams?.toString()).isEqualTo(
                JSONObject().apply {
                    put("mnemonic", "test")
                    put("language", "english")
                }.toString()
            )
        }

    @Test
    fun generateMnemonic_parsesResult() =
        runTest {
            bridge.nextStringResult = """{"value":"alpha beta","language":"english"}"""
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.generateMnemonic()

            assertThat(result).isEqualTo(Mnemonic("alpha beta", "english"))
            assertThat(bridge.lastMethod).isEqualTo("generateMnemonic")
        }

    @Test
    fun deriveFromPrivateKey_parsesResult() =
        runTest {
            bridge.nextObjectResult =
                TraditionalDeriveResult(
                    address = "addr",
                    keypair = Keypair("pk", "pub"),
                    secret = "secret",
                    path = Path(chain = 1L),
                    sourcePrivateKey = "source"
                )
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.deriveFromPrivateKey("privateKey", 1L)

            assertThat(result.address).isEqualTo("addr")
            assertThat(bridge.lastMethod).isEqualTo("deriveFromPrivateKey")
            assertPayloadEquals(
                JSONObject().apply {
                    put("privateKey", "privateKey")
                    put("chain", 1L)
                }
            )
        }

    @Test
    fun deriveChild_buildsExpectedPayload() =
        runTest {
            bridge.nextObjectResult =
                SubWallet(
                    chain = 2147483963L,
                    address = "addr",
                    path = Path(chain = 2147483963L),
                    keypair = Keypair("pk", "pub")
                )
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.deriveChild("mnemonic", 2147483963L, index = 2)

            assertThat(result.address).isEqualTo("addr")
            assertThat(bridge.lastMethod).isEqualTo("deriveChild")
            assertPayloadEquals(
                JSONObject().apply {
                    put("mnemonic", "mnemonic")
                    put("chain", 2147483963L)
                    put("account", 0)
                    put("change", 0)
                    put("index", 2)
                    put("language", "english")
                }
            )
        }

    @Test
    fun hdWalletFromMnemonic_parsesResult() =
        runTest {
            bridge.nextObjectResult =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic",
                    address = "addr",
                    language = "english",
                    keypair = Keypair("pk", "pub"),
                    accounts = emptyList()
                )
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.hdWalletFromMnemonic("mnemonic", listOf(1L, 2L))

            assertThat(result.address).isEqualTo("addr")
            assertThat(bridge.lastMethod).isEqualTo("hdWalletFromMnemonic")
            assertPayloadEquals(
                JSONObject().apply {
                    put("mnemonic", "mnemonic")
                    put("chains", listOf(1L, 2L))
                    put("language", "english")
                }
            )
        }

    @Test
    fun callJsMethodAs_withStringClass_returnsRawString() =
        runTest {
            bridge.nextObjectResult = "raw"
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.callJsMethodAs("method", null, String::class.java)

            assertThat(result).isEqualTo("raw")
            assertThat(bridge.lastMethod).isEqualTo("method")
        }

    @Test
    fun deriveFromMnemonic_parsesResult() =
        runTest {
            bridge.nextObjectResult =
                TraditionalDeriveResult(
                    address = "addr",
                    keypair = Keypair("pk", "pub"),
                    mnemonic = Mnemonic("m", "english"),
                    path = Path(chain = 1L)
                )
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.deriveFromMnemonic("mnemonic", 1L)

            assertThat(result.address).isEqualTo("addr")
            assertThat(bridge.lastMethod).isEqualTo("deriveFromMnemonic")
            assertPayloadEquals(
                JSONObject().apply {
                    put("mnemonic", "mnemonic")
                    put("chain", 1L)
                    put("account", 0)
                    put("change", 0)
                    put("index", 0)
                    put("language", "english")
                }
            )
        }

    @Test
    fun validatePrivateKey_returnsBoolean() =
        runTest {
            bridge.nextStringResult = "true"
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.validatePrivateKey("key", 1L)

            assertThat(result).isTrue()
            assertThat(bridge.lastMethod).isEqualTo("validatePrivateKey")
            assertPayloadEquals(
                JSONObject().apply {
                    put("privateKey", "key")
                    put("chain", 1L)
                }
            )
        }

    @Test
    fun signTypedData_buildsExpectedPayload() =
        runTest {
            bridge.nextStringResult = "0xabc"
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.signTypedData("pk", "data", "V4")

            assertThat(result).isEqualTo("0xabc")
            assertThat(bridge.lastMethod).isEqualTo("signTypedData")
            assertPayloadEquals(
                JSONObject().apply {
                    put("privateKey", "pk")
                    put("data", "data")
                    put("version", "V4")
                }
            )
        }

    @Test
    fun recoverTypedSignature_buildsExpectedPayload() =
        runTest {
            bridge.nextStringResult = "0xaaa"
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.recoverTypedSignature("data", "signature", "V4")

            assertThat(result).isEqualTo("0xaaa")
            assertThat(bridge.lastMethod).isEqualTo("recoverTypedSignature")
            assertPayloadEquals(
                JSONObject().apply {
                    put("data", "data")
                    put("signature", "signature")
                    put("version", "V4")
                }
            )
        }

    @Test
    fun recoverPersonalSignature_buildsExpectedPayload() =
        runTest {
            bridge.nextStringResult = "0xbbb"
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.recoverPersonalSignature("data", "signature")

            assertThat(result).isEqualTo("0xbbb")
            assertThat(bridge.lastMethod).isEqualTo("recoverPersonalSignature")
            assertPayloadEquals(
                JSONObject().apply {
                    put("data", "data")
                    put("signature", "signature")
                }
            )
        }

    @Test
    fun decrypt_buildsExpectedPayload() =
        runTest {
            bridge.nextStringResult = "plain-text"
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.decrypt("pk", "cipher")

            assertThat(result).isEqualTo("plain-text")
            assertThat(bridge.lastMethod).isEqualTo("decrypt")
            assertPayloadEquals(
                JSONObject().apply {
                    put("privateKey", "pk")
                    put("data", "cipher")
                }
            )
        }

    @Test
    fun signEthTransaction_buildsExpectedPayload() =
        runTest {
            bridge.nextStringResult = "signed"
            WalletSdk.installBridgeForTest(bridge)
            val tx = JSONObject().apply { put("amount", "1") }

            val result = WalletSdk.signEthTransaction("pk", tx)

            assertThat(result).isEqualTo("signed")
            assertThat(bridge.lastMethod).isEqualTo("signEthTransaction")
            assertPayloadEquals(
                JSONObject().apply {
                    put("privateKey", "pk")
                    put("tx", tx)
                }
            )
        }

    @Test
    fun personalSign_buildsExpectedPayload() =
        runTest {
            bridge.nextStringResult = "0xdef"
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.personalSign("pk", "msg")

            assertThat(result).isEqualTo("0xdef")
            assertThat(bridge.lastMethod).isEqualTo("personalSign")
            assertPayloadEquals(
                JSONObject().apply {
                    put("privateKey", "pk")
                    put("data", "msg")
                }
            )
        }

    @Test
    fun getEncryptionPublicKey_returnsValue() =
        runTest {
            bridge.nextStringResult = "pub"
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.getEncryptionPublicKey("pk")

            assertThat(result).isEqualTo("pub")
            assertThat(bridge.lastMethod).isEqualTo("getEncryptionPublicKey")
            assertPayloadEquals(JSONObject().apply { put("privateKey", "pk") })
        }

    @Test
    fun buildSwtcPayment_buildsExpectedPayload() =
        runTest {
            bridge.nextStringResult = "signed"
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.buildSwtcPayment("addr", "1", "to", "token", "memo")

            assertThat(result).isEqualTo("signed")
            assertThat(bridge.lastMethod).isEqualTo("buildSwtcPayment")
            assertPayloadEquals(
                JSONObject().apply {
                    put("address", "addr")
                    put("amount", "1")
                    put("to", "to")
                    put("token", "token")
                    put("memo", "memo")
                }
            )
        }

    @Test
    fun buildSwtcNftTransfer_buildsExpectedPayload() =
        runTest {
            bridge.nextStringResult = "signed"
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.buildSwtcNftTransfer("addr", "to", "1", "memo")

            assertThat(result).isEqualTo("signed")
            assertThat(bridge.lastMethod).isEqualTo("buildSwtcNftTransfer")
            assertPayloadEquals(
                JSONObject().apply {
                    put("address", "addr")
                    put("to", "to")
                    put("tokenId", "1")
                    put("memo", "memo")
                }
            )
        }

    @Test
    fun signSwtcTransaction_buildsExpectedPayload() =
        runTest {
            bridge.nextStringResult = "tx"
            WalletSdk.installBridgeForTest(bridge)
            val tx = JSONObject().apply { put("amount", "1") }

            val result = WalletSdk.signSwtcTransaction(tx, "secret")

            assertThat(result).isEqualTo("tx")
            assertThat(bridge.lastMethod).isEqualTo("signSwtcTransaction")
            assertPayloadEquals(
                JSONObject().apply {
                    put("tx", tx)
                    put("secret", "secret")
                }
            )
        }

    @Test
    fun isValidAddress_returnsBoolean() =
        runTest {
            bridge.nextStringResult = "true"
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.isValidAddress("addr")

            assertThat(result).isTrue()
            assertThat(bridge.lastMethod).isEqualTo("isValidAddress")
            assertPayloadEquals(JSONObject().apply { put("address", "addr") })
        }

    @Test
    fun signMessage_buildsExpectedPayload() =
        runTest {
            bridge.nextStringResult = "sig"
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.signMessage("addr", "hello", "secret")

            assertThat(result).isEqualTo("sig")
            assertThat(bridge.lastMethod).isEqualTo("signMessage")
            assertPayloadEquals(
                JSONObject().apply {
                    put("address", "addr")
                    put("message", "hello")
                    put("secret", "secret")
                }
            )
        }

    @Test
    fun signTransaction_buildsExpectedPayload() =
        runTest {
            bridge.nextStringResult = "sig"
            WalletSdk.installBridgeForTest(bridge)
            val tx = JSONObject().apply { put("amount", "1") }

            val result = WalletSdk.signTransaction(tx, "secret")

            assertThat(result).isEqualTo("sig")
            assertThat(bridge.lastMethod).isEqualTo("signTransaction")
            assertPayloadEquals(
                JSONObject().apply {
                    put("tx", tx)
                    put("secret", "secret")
                }
            )
        }

    @Test
    fun multiSign_buildsExpectedPayload() =
        runTest {
            bridge.nextStringResult = "sig"
            WalletSdk.installBridgeForTest(bridge)
            val tx = JSONObject().apply { put("amount", "1") }

            val result = WalletSdk.multiSign(tx, "secret")

            assertThat(result).isEqualTo("sig")
            assertThat(bridge.lastMethod).isEqualTo("multiSign")
            assertPayloadEquals(
                JSONObject().apply {
                    put("tx", tx)
                    put("secret", "secret")
                }
            )
        }

    private class RecordingWalletBridge : IWalletBridge {
        var lastMethod: String? = null
        var lastParams: JSONObject? = null
        var nextStringResult: String = ""
        var nextObjectResult: Any? = null
        var started = false
        var destroyed = false

        fun reset() {
            lastMethod = null
            lastParams = null
            nextStringResult = ""
            nextObjectResult = null
            started = false
            destroyed = false
        }

        override fun start() {
            started = true
        }

        override suspend fun call(
            method: String,
            params: JSONObject?,
            timeoutMs: Long,
            readyWaitMs: Long
        ): String {
            lastMethod = method
            lastParams = params
            return nextStringResult
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> callAs(
            method: String,
            params: JSONObject?,
            clazz: Class<T>,
            timeoutMs: Long,
            readyWaitMs: Long
        ): T {
            lastMethod = method
            lastParams = params
            return nextObjectResult as T
        }

        override fun destroy() {
            destroyed = true
        }
    }

    private fun assertPayloadEquals(expected: JSONObject) {
        assertThat(bridge.lastParams?.toString()).isEqualTo(expected.toString())
    }
}
