package com.jccdex.toolkits.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jccdex.toolkits.account.store.IAccountStore
import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.WalletAccount
import com.jccdex.toolkits.vault.VaultRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    fun orchestrator_returnsSameInstanceForSameVault() {
        // M-19A: repeated calls must share one orchestrator (and its Mutex), otherwise concurrent
        // deriveSubAccount/removeAccount across instances would not serialize.
        val vault = mockk<VaultRepository>(relaxed = true)
        assertThat(sdk.orchestrator(vault)).isSameAs(sdk.orchestrator(vault))
    }

    @Test
    fun getAccountsByChain_delegatesToStore() =
        runTest {
            sdk.addAccount(
                AccountTestFixtures.traditional(id = "eth-1", chain = ChainType.ETH, address = "0x1")
            )

            assertThat(sdk.getAccountsByChain(ChainType.ETH).first()).hasSize(1)
        }

    @Test
    fun delegates_all_store_operations() =
        runTest {
            val store = RecordingAccountStore()
            val localSdk = AccountSdk.createForTest(store)

            val root = AccountTestFixtures.hdRoot(id = "root-id", address = "jRoot")
            val sub =
                AccountTestFixtures.hdSub(
                    id = "sub-id",
                    parentId = root.id,
                    address = "0xsub",
                    index = 1
                )
            val trad =
                AccountTestFixtures.traditional(
                    id = "trad-id",
                    address = "0xtrad",
                    chain = ChainType.BSC
                )

            assertThat(localSdk.accounts.first()).isEmpty()
            assertThat(localSdk.currentAccount.first()).isNull()
            assertThat(localSdk.rootHDAccounts.first()).isEmpty()
            assertThat(localSdk.subHDAccounts.first()).isEmpty()
            assertThat(localSdk.traditionalAccounts.first()).isEmpty()

            localSdk.addAccount(root)
            localSdk.addAccounts(listOf(sub, trad))
            localSdk.setCurrentAccount(root.id)
            localSdk.updateAccountName(root.id, "root-renamed")
            localSdk.updateAccountNameByAddress(trad.address, "trad-renamed")
            localSdk.updatePublicKey(sub.id, "sub-pub")
            localSdk.updateParentId(trad.id, root.id)

            assertThat(localSdk.accounts.first().map { it.id }).containsExactly(root.id, sub.id, trad.id)
            assertThat(localSdk.rootHDAccounts.first().map { it.id }).containsExactly(root.id)
            assertThat(localSdk.subHDAccounts.first().map { it.id }).containsExactly(sub.id)
            assertThat(localSdk.traditionalAccounts.first().map { it.id }).containsExactly(trad.id)
            assertThat(localSdk.currentAccount.first()?.id).isEqualTo(root.id)
            assertThat(localSdk.getCurrentAccountId()).isEqualTo(root.id)
            assertThat(localSdk.getAccountsByChain(ChainType.BSC).first().map { it.id }).containsExactly(trad.id)
            assertThat(localSdk.getSubAccountsOf(root.id).first().map { it.id })
                .containsExactlyInAnyOrder(sub.id, trad.id)
            assertThat(localSdk.getMaxIndexByChain(root.id, ChainType.ETH)).isEqualTo(1)
            assertThat(localSdk.countSubAccountsByChain(root.id, ChainType.ETH)).isEqualTo(1)
            assertThat(localSdk.getSameAccountsCount(trad.address)).isEqualTo(1)
            assertThat(localSdk.findById(root.id)?.name).isEqualTo("root-renamed")
            assertThat(localSdk.findByAddress(root.address, ChainType.SWTC)?.id).isEqualTo(root.id)
            assertThat(localSdk.findByAddress(trad.address)?.name).isEqualTo("trad-renamed")
            assertThat(localSdk.findByAddress(sub.address, ChainType.ETH)?.id).isEqualTo(sub.id)
            assertThat(localSdk.findRootAccountByAddress(root.address)?.id).isEqualTo(root.id)
            assertThat(localSdk.findNonRootAccount(sub.address, ChainType.ETH)?.id).isEqualTo(sub.id)

            localSdk.removeAccount(sub.id)
            assertThat(localSdk.findById(sub.id)).isNull()

            localSdk.clearAllAccounts()
            assertThat(localSdk.accounts.first()).isEmpty()
            assertThat(localSdk.currentAccount.first()).isNull()
        }

    @Test
    fun create_returnsDistinctSdkInstances() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val first = AccountSdk.create(context)
        val second = AccountSdk.create(context)

        assertThat(first).isNotSameAs(second)
    }

    @Test
    fun create_and_get_shareSameRoomDatabase() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            AccountSdk.resetForTest()

            val created = AccountSdk.create(context)
            created.clearAllAccounts()
            created.addAccount(AccountTestFixtures.traditional(id = "shared-db-id"))

            AccountSdk.resetForTest()
            val fromGet = AccountSdk.get(context)

            assertThat(fromGet.findById("shared-db-id")).isNotNull
            fromGet.clearAllAccounts()
            AccountSdk.resetForTest()
        }

    @Test
    fun get_and_create_useRoomBackedSdk() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()

            val created = AccountSdk.create(context)
            created.clearAllAccounts()
            created.addAccount(AccountTestFixtures.traditional(id = "room-id"))
            assertThat(created.findById("room-id")).isNotNull

            AccountSdk.resetForTest()
            val firstGet = AccountSdk.get(context)
            val secondGet = AccountSdk.get(context)
            assertThat(secondGet).isSameAs(firstGet)
            assertThat(firstGet.findById("room-id")).isNotNull

            AccountSdk.resetForTest()
            val afterReset = AccountSdk.get(context)
            assertThat(afterReset).isNotSameAs(firstGet)
            assertThat(afterReset.findById("room-id")).isNotNull
            afterReset.clearAllAccounts()
        }
}

private class RecordingAccountStore : IAccountStore {
    private val accountsState = MutableStateFlow<List<WalletAccount>>(emptyList())
    private val currentAccountIdState = MutableStateFlow<String?>(null)
    private val currentAccountState = MutableStateFlow<WalletAccount?>(null)

    override val accounts: Flow<List<WalletAccount>> = accountsState.asStateFlow()

    override val currentAccount: Flow<WalletAccount?> = currentAccountState.asStateFlow()

    override val rootHDAccounts: Flow<List<WalletAccount>> =
        accountsState.map { accounts -> accounts.filter { it.isRootHD() } }

    override val subHDAccounts: Flow<List<WalletAccount>> =
        accountsState.map { accounts -> accounts.filter { it.isSubHD() } }

    override val traditionalAccounts: Flow<List<WalletAccount>> =
        accountsState.map { accounts -> accounts.filter { it.isTraditional() } }

    override fun getAccountsByChain(chain: ChainType): Flow<List<WalletAccount>> =
        accountsState.map { accounts ->
            accounts.filter {
                it.chain == chain
            }
        }

    override suspend fun addAccount(account: WalletAccount) {
        accountsState.value = accountsState.value + account
        refreshCurrentAccount()
    }

    override suspend fun addAccounts(accounts: List<WalletAccount>) {
        accountsState.value = accountsState.value + accounts
        refreshCurrentAccount()
    }

    override suspend fun removeAccount(accountId: String) {
        accountsState.value = accountsState.value.filterNot { it.id == accountId }
        if (currentAccountIdState.value == accountId) {
            currentAccountIdState.value = null
        }
        refreshCurrentAccount()
    }

    override suspend fun setCurrentAccount(accountId: String) {
        currentAccountIdState.value = accountId
        refreshCurrentAccount()
    }

    override suspend fun updateAccountName(
        accountId: String,
        name: String
    ) {
        updateAccount(accountId) { it.copy(name = name) }
    }

    override suspend fun updateAccountNameByAddress(
        address: String,
        name: String
    ) {
        updateAccountsByAddress(address) { it.copy(name = name) }
    }

    override suspend fun updatePublicKey(
        accountId: String,
        publicKey: String
    ) {
        updateAccount(accountId) { it.copy(publicKey = publicKey) }
    }

    override suspend fun updateParentId(
        accountId: String,
        parentId: String
    ) {
        updateAccount(accountId) { it.copy(parentId = parentId) }
    }

    override suspend fun findByAddress(
        address: String,
        chain: ChainType
    ): WalletAccount? =
        accountsState.value.firstOrNull {
            it.address.equals(address, ignoreCase = true) && it.chain == chain
        }

    override suspend fun listAllAddresses(): List<String> = accountsState.value.map { it.address }

    override suspend fun findByAddress(address: String): WalletAccount? =
        accountsState.value.firstOrNull {
            it.address.equals(address, ignoreCase = true)
        }

    override suspend fun findRootAccountByAddress(address: String): WalletAccount? =
        accountsState.value.firstOrNull {
            it.address.equals(address, ignoreCase = true) && it.isRootHD()
        }

    override suspend fun findNonRootAccount(
        address: String,
        chain: ChainType
    ): WalletAccount? =
        accountsState.value.firstOrNull {
            it.address.equals(address, ignoreCase = true) &&
                it.chain == chain &&
                (it.isTraditional() || it.isSubHD())
        }

    override suspend fun findById(id: String): WalletAccount? = accountsState.value.firstOrNull { it.id == id }

    override fun getSubAccountsOf(parentId: String): Flow<List<WalletAccount>> =
        accountsState.map { accounts ->
            accounts.filter {
                it.parentId == parentId
            }
        }

    override suspend fun getMaxIndexByChain(
        parentId: String,
        chain: ChainType
    ): Int =
        accountsState.value
            .filter { it.parentId == parentId && it.chain == chain }
            .mapNotNull { it.path?.index }
            .maxOrNull()
            ?: -1

    override suspend fun countSubAccountsByChain(
        parentId: String,
        chain: ChainType
    ): Int = accountsState.value.count { it.parentId == parentId && it.chain == chain }

    override suspend fun getCurrentAccountId(): String? = currentAccountIdState.value

    override suspend fun getSameAccountsCount(address: String): Int =
        accountsState.value.count {
            it.address.equals(address, ignoreCase = true)
        }

    override suspend fun clearAllAccounts() {
        accountsState.value = emptyList()
        currentAccountIdState.value = null
        refreshCurrentAccount()
    }

    private fun updateAccount(
        accountId: String,
        transform: (WalletAccount) -> WalletAccount
    ) {
        accountsState.value =
            accountsState.value.map { account ->
                if (account.id == accountId) transform(account) else account
            }
        refreshCurrentAccount()
    }

    private fun updateAccountsByAddress(
        address: String,
        transform: (WalletAccount) -> WalletAccount
    ) {
        accountsState.value =
            accountsState.value.map { account ->
                if (account.address.equals(address, ignoreCase = true)) transform(account) else account
            }
        refreshCurrentAccount()
    }

    private fun refreshCurrentAccount() {
        currentAccountState.value =
            currentAccountIdState.value?.let { currentId ->
                accountsState.value.firstOrNull { it.id == currentId }
            }
    }
}
