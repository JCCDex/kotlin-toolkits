package com.jccdex.toolkits.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jccdex.toolkits.account.store.RoomAccountStore
import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.vault.VaultRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AccountSdkTest {
    private lateinit var testDb: AccountTestDatabase
    private lateinit var sdk: AccountSdk

    @Before
    fun setup() {
        AccountSdk.resetForTest()
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = AccountTestDatabase.inMemory(context)
        sdk = AccountSdk.createForTest(testDb.store)
    }

    @After
    fun tearDown() {
        AccountSdk.resetForTest()
        testDb.close()
    }

    @Test
    fun createForTest_delegatesToStore() =
        runTest {
            sdk.addAccount(AccountTestFixtures.traditional(id = "sdk-id"))

            assertThat(sdk.accounts.first()).hasSize(1)
            assertThat(sdk.findById("sdk-id")).isNotNull
        }

    @Test
    fun orchestrator_returnsAccountOrchestrator() {
        val vault = mockk<VaultRepository>(relaxed = true)
        val orchestrator = sdk.orchestrator(vault)

        assertThat(orchestrator).isNotNull
    }

    @Test
    fun getAccountsByChain_delegatesToStore() =
        runTest {
            sdk.addAccount(
                AccountTestFixtures.traditional(id = "eth-1", chain = ChainType.ETH, address = "0x1")
            )

            assertThat(sdk.getAccountsByChain(ChainType.ETH).first()).hasSize(1)
        }
}
