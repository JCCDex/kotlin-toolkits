package com.jccdex.toolkits.wallet.sdk

import com.jccdex.toolkits.wallet.model.Keypair
import com.jccdex.toolkits.wallet.model.Mnemonic
import com.jccdex.toolkits.wallet.model.Path
import com.jccdex.toolkits.wallet.model.SubWallet
import com.jccdex.toolkits.wallet.model.TraditionalDeriveResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class WalletSdkTest {
    @After
    fun tearDown() {
        WalletSdk.installBridgeForTest(null)
        WalletSdk.destroy()
    }

    @Test
    fun generateMnemonic_delegatesToBridge() =
        runTest {
            val bridge = mockk<IWalletBridge>(relaxed = true)
            coEvery { bridge.call("generateMnemonic", any(), any(), any()) } returns """{"value":"alpha beta","language":"english"}"""
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.generateMnemonic()

            assertThat(result).isEqualTo(Mnemonic("alpha beta", "english"))
        }

    @Test
    fun deriveChild_buildsExpectedPayload() =
        runTest {
            val bridge = mockk<IWalletBridge>(relaxed = true)
            coEvery { bridge.callAs("deriveChild", any(), SubWallet::class.java, any(), any()) } returns
                SubWallet(
                    chain = 2147483963L,
                    address = "addr",
                    path = Path(chain = 2147483963L),
                    keypair = Keypair("pk", "pub")
                )
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.deriveChild("mnemonic", 2147483963L, index = 2)

            assertThat(result.address).isEqualTo("addr")
        }

    @Test
    fun validateMnemonic_returnsBoolean() =
        runTest {
            val bridge = mockk<IWalletBridge>(relaxed = true)
            coEvery { bridge.call("validateMnemonic", any(), any(), any()) } returns "true"
            WalletSdk.installBridgeForTest(bridge)

            assertThat(WalletSdk.validateMnemonic("test")).isTrue()
        }

    @Test
    fun deriveFromMnemonic_parsesResult() =
        runTest {
            val bridge = mockk<IWalletBridge>(relaxed = true)
            coEvery { bridge.callAs("deriveFromMnemonic", any(), TraditionalDeriveResult::class.java, any(), any()) } returns
                TraditionalDeriveResult(
                    address = "addr",
                    keypair = Keypair("pk", "pub"),
                    mnemonic = Mnemonic("m", "english"),
                    path = Path(chain = 1L)
                )
            WalletSdk.installBridgeForTest(bridge)

            val result = WalletSdk.deriveFromMnemonic("mnemonic", 1L)

            assertThat(result.address).isEqualTo("addr")
        }
}
