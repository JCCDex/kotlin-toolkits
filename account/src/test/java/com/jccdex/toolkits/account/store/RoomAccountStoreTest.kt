package com.jccdex.toolkits.account.store

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.jccdex.toolkits.account.AccountTestDatabase
import com.jccdex.toolkits.account.AccountTestFixtures
import com.jccdex.toolkits.core.model.ChainType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RoomAccountStoreTest {
    private lateinit var testDb: AccountTestDatabase
    private lateinit var store: RoomAccountStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = AccountTestDatabase.inMemory(context)
        store = testDb.store
    }

    @After
    fun tearDown() {
        testDb.close()
    }

    @Test
    fun addAccount_hdRoot() =
        runTest {
            val account = AccountTestFixtures.hdRoot()
            store.addAccount(account)

            val accounts = store.accounts.first()
            assertThat(accounts).hasSize(1)
            assertThat(accounts[0].isRootHD()).isTrue()
        }

    @Test
    fun addAccount_hdSub() =
        runTest {
            val root = AccountTestFixtures.hdRoot()
            val sub = AccountTestFixtures.hdSub(parentId = root.id)
            store.addAccount(root)
            store.addAccount(sub)

            val subAccount = store.accounts.first().find { it.id == sub.id }
            assertThat(subAccount).isNotNull
            assertThat(subAccount!!.isSubHD()).isTrue()
        }

    @Test
    fun addAccounts_persistsMultipleAccounts() =
        runTest {
            val first = AccountTestFixtures.traditional(id = "batch-1", address = "0xbatch1")
            val second = AccountTestFixtures.traditional(id = "batch-2", address = "0xbatch2")

            store.addAccounts(listOf(first, second))

            assertThat(store.accounts.first().map { it.id }).containsExactlyInAnyOrder("batch-1", "batch-2")
        }

    @Test
    fun findByAddress_andChain() =
        runTest {
            val eth =
                AccountTestFixtures.traditional(
                    id = "eth-id",
                    address = "0x6a4f486f8f2e010c577afe8913886d977ba4b683",
                    chain = ChainType.ETH
                )
            val bsc =
                AccountTestFixtures.traditional(
                    id = "bsc-id",
                    address = "0x6a4f486f8f2e010c577afe8913886d977ba4b683",
                    chain = ChainType.BSC
                )
            store.addAccount(eth)
            store.addAccount(bsc)

            assertThat(store.findByAddress(eth.address, ChainType.ETH)?.id).isEqualTo("eth-id")
            assertThat(store.findByAddress(eth.address, ChainType.BSC)?.id).isEqualTo("bsc-id")
        }

    @Test
    fun findById_and_findByAddress() =
        runTest {
            val account =
                AccountTestFixtures.traditional(
                    id = "lookup-id",
                    address = "0xAbC123"
                )
            store.addAccount(account)

            assertThat(store.findById("lookup-id")?.address).isEqualTo("0xAbC123")
            assertThat(store.findByAddress("0xabc123")?.id).isEqualTo("lookup-id")
        }

    @Test
    fun removeAccount_clearsCurrentSelection() =
        runTest {
            val account = AccountTestFixtures.traditional(id = "current-id")
            store.addAccount(account)
            store.setCurrentAccount(account.id)

            store.removeAccount(account.id)

            assertThat(store.findById(account.id)).isNull()
            assertThat(store.getCurrentAccountId()).isNull()
            assertThat(store.currentAccount.first()).isNull()
        }

    @Test
    fun updateAccountName_andPublicKey() =
        runTest {
            val account = AccountTestFixtures.traditional(id = "upd-id", name = "old")
            store.addAccount(account)

            store.updateAccountName(account.id, "new")
            store.updatePublicKey(account.id, "pub-new")

            val updated = store.findById(account.id)
            assertThat(updated?.name).isEqualTo("new")
            assertThat(updated?.publicKey).isEqualTo("pub-new")
        }

    @Test
    fun updateAccountNameByAddress_andParentId() =
        runTest {
            val parent = AccountTestFixtures.hdRoot(id = "parent-id")
            val child = AccountTestFixtures.traditional(id = "child-id", address = "0xchild")
            store.addAccount(parent)
            store.addAccount(child)

            store.updateAccountNameByAddress("0xCHILD", "child-renamed")
            store.updateParentId(child.id, parent.id)

            val updatedChild = store.findById(child.id)
            assertThat(updatedChild?.name).isEqualTo("child-renamed")
            assertThat(updatedChild?.parentId).isEqualTo(parent.id)
        }

    @Test
    fun setCurrentAccount_flow() =
        runTest {
            val account = AccountTestFixtures.traditional(id = "cur-id")
            store.addAccount(account)
            store.setCurrentAccount(account.id)

            assertThat(store.getCurrentAccountId()).isEqualTo(account.id)
            assertThat(store.currentAccount.first()?.id).isEqualTo(account.id)
        }

    @Test
    fun getAccountsByChain() =
        runTest {
            store.addAccount(
                AccountTestFixtures.traditional(id = "e1", chain = ChainType.ETH, address = "0x111")
            )
            store.addAccount(
                AccountTestFixtures.traditional(id = "e2", chain = ChainType.ETH, address = "0x222")
            )
            store.addAccount(
                AccountTestFixtures.traditional(id = "s1", chain = ChainType.SWTC, address = "j111")
            )

            assertThat(store.getAccountsByChain(ChainType.ETH).first()).hasSize(2)
            assertThat(store.getAccountsByChain(ChainType.SWTC).first()).hasSize(1)
        }

    @Test
    fun getSubAccountsOf() =
        runTest {
            val root = AccountTestFixtures.hdRoot(id = "root-id")
            val sub1 = AccountTestFixtures.hdSub(id = "sub-1", parentId = root.id, address = "0xaaa")
            val sub2 = AccountTestFixtures.hdSub(id = "sub-2", parentId = root.id, address = "0xbbb")
            store.addAccount(root)
            store.addAccount(sub1)
            store.addAccount(sub2)

            val subs = store.getSubAccountsOf(root.id).first()
            assertThat(subs).hasSize(2)
            assertThat(subs.map { it.id }).containsExactlyInAnyOrder("sub-1", "sub-2")
        }

    @Test
    fun getMaxIndexByChain() =
        runTest {
            val root = AccountTestFixtures.hdRoot(id = "root-id")
            store.addAccount(root)
            store.addAccount(AccountTestFixtures.hdSub(id = "s0", parentId = root.id, index = 0, address = "0xa0"))
            store.addAccount(AccountTestFixtures.hdSub(id = "s5", parentId = root.id, index = 5, address = "0xa5"))

            assertThat(store.getMaxIndexByChain(root.id, ChainType.ETH)).isEqualTo(5)
            assertThat(store.getMaxIndexByChain("missing", ChainType.ETH)).isEqualTo(-1)
        }

    @Test
    fun countSubAccountsByChain() =
        runTest {
            val root = AccountTestFixtures.hdRoot(id = "root-id")
            store.addAccount(root)
            store.addAccount(
                AccountTestFixtures.hdSub(id = "e1", parentId = root.id, chain = ChainType.ETH, address = "0xe1")
            )
            store.addAccount(
                AccountTestFixtures.hdSub(id = "e2", parentId = root.id, chain = ChainType.ETH, address = "0xe2")
            )
            store.addAccount(
                AccountTestFixtures.hdSub(id = "b1", parentId = root.id, chain = ChainType.BSC, address = "0xb1")
            )

            assertThat(store.countSubAccountsByChain(root.id, ChainType.ETH)).isEqualTo(2)
            assertThat(store.countSubAccountsByChain(root.id, ChainType.BSC)).isEqualTo(1)
        }

    @Test
    fun rootHDAccounts_and_traditionalAccounts_flows() =
        runTest {
            val root = AccountTestFixtures.hdRoot(id = "r1", address = "jRoot1")
            val sub = AccountTestFixtures.hdSub(id = "s1", parentId = root.id, address = "0xsub")
            val trad = AccountTestFixtures.traditional(id = "t1", address = "0xtrad")
            store.addAccount(root)
            store.addAccount(sub)
            store.addAccount(trad)

            assertThat(store.rootHDAccounts.first()).hasSize(1)
            assertThat(store.subHDAccounts.first()).hasSize(1)
            assertThat(store.traditionalAccounts.first()).hasSize(1)
        }

    @Test
    fun subHDAccounts_includesDerivedWithoutParentId() =
        runTest {
            val root = AccountTestFixtures.hdRoot(id = "r1", address = "jRoot1")
            val derived = AccountTestFixtures.hdDerivedWithoutParent(id = "d1", address = "0xderived", index = 2)
            store.addAccount(root)
            store.addAccount(derived)

            assertThat(store.rootHDAccounts.first().map { it.id }).containsExactly(root.id)
            assertThat(store.subHDAccounts.first().map { it.id }).containsExactly(derived.id)
            assertThat(derived.isSubHD()).isTrue()
        }

    @Test
    fun findRootAccountByAddress() =
        runTest {
            val root = AccountTestFixtures.hdRoot(id = "root-id", address = "jRootAddr")
            val sub = AccountTestFixtures.hdSub(id = "sub-id", parentId = root.id, address = "0xsub")
            store.addAccount(root)
            store.addAccount(sub)

            assertThat(store.findRootAccountByAddress("jRootAddr")?.id).isEqualTo(root.id)
            assertThat(store.findRootAccountByAddress("0xsub")).isNull()
        }

    @Test
    fun getSameAccountsCount() =
        runTest {
            val addr = "0x6a4f486f8f2e010c577afe8913886d977ba4b683"
            store.addAccount(AccountTestFixtures.traditional(id = "a1", address = addr, chain = ChainType.ETH))
            store.addAccount(AccountTestFixtures.traditional(id = "a2", address = addr, chain = ChainType.BSC))

            assertThat(store.getSameAccountsCount(addr)).isEqualTo(2)
        }

    @Test
    fun findNonRootAccount() =
        runTest {
            val root = AccountTestFixtures.hdRoot(id = "root-id", address = "jRoot")
            val sub = AccountTestFixtures.hdSub(id = "sub-id", parentId = root.id, address = "0xsub")
            val trad = AccountTestFixtures.traditional(id = "trad-id", address = "0xtrad")
            store.addAccount(root)
            store.addAccount(sub)
            store.addAccount(trad)

            assertThat(store.findNonRootAccount("jRoot", ChainType.SWTC)).isNull()
            assertThat(store.findNonRootAccount("0xsub", ChainType.ETH)?.id).isEqualTo(sub.id)
            assertThat(store.findNonRootAccount("0xtrad", ChainType.ETH)?.id).isEqualTo(trad.id)
        }

    @Test
    fun findNonRootAccount_hdDerivedWithoutParentId() =
        runTest {
            val derived = AccountTestFixtures.hdDerivedWithoutParent(address = "0xderived", index = 2)
            store.addAccount(derived)

            assertThat(store.findNonRootAccount("0xderived", ChainType.ETH)?.id).isEqualTo(derived.id)
            assertThat(store.findRootAccountByAddress("0xderived")).isNull()
        }

    @Test
    fun addAccount_duplicateId_throwsConstraintException() =
        runTest {
            val account = AccountTestFixtures.traditional(id = "dup-store-id")
            store.addAccount(account)

            assertThatThrownBy {
                runBlocking {
                    store.addAccount(account.copy(name = "duplicate"))
                }
            }.isInstanceOf(SQLiteConstraintException::class.java)
        }

    @Test
    fun currentAccount_isNull_whenNeverSet() =
        runTest {
            store.addAccount(AccountTestFixtures.traditional(id = "no-current"))

            assertThat(store.currentAccount.first()).isNull()
        }

    @Test
    fun currentAccount_isNull_whenCurrentPointsToMissingAccount() =
        runTest {
            val account = AccountTestFixtures.traditional(id = "orphan-current")
            store.addAccount(account)
            store.setCurrentAccount(account.id)

            testDb.accountDao.deleteById(account.id)

            assertThat(store.currentAccount.first()).isNull()
            assertThat(store.getCurrentAccountId()).isEqualTo(account.id)
        }

    @Test
    fun removeAccount_preservesCurrentWhenDeletingOther() =
        runTest {
            val current = AccountTestFixtures.traditional(id = "keep-current", address = "0xkeep")
            val other = AccountTestFixtures.traditional(id = "drop-other", address = "0xdrop")
            store.addAccount(current)
            store.addAccount(other)
            store.setCurrentAccount(current.id)

            store.removeAccount(other.id)

            assertThat(store.currentAccount.first()?.id).isEqualTo(current.id)
            assertThat(store.getCurrentAccountId()).isEqualTo(current.id)
            assertThat(store.findById(other.id)).isNull()
        }

    @Test
    fun clearAllAccounts() =
        runTest {
            val account = AccountTestFixtures.traditional()
            store.addAccount(account)
            store.setCurrentAccount(account.id)

            store.clearAllAccounts()

            assertThat(store.accounts.first()).isEmpty()
            assertThat(store.getCurrentAccountId()).isNull()
        }
}
