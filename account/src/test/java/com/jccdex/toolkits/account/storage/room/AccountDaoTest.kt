package com.jccdex.toolkits.account.storage.room

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.jccdex.toolkits.account.AccountTestDatabase
import com.jccdex.toolkits.account.AccountTestFixtures
import com.jccdex.toolkits.core.model.ChainType
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
class AccountDaoTest {
    private lateinit var testDb: AccountTestDatabase
    private lateinit var accountDao: AccountDao
    private lateinit var currentAccountDao: CurrentAccountDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = AccountTestDatabase.inMemory(context)
        accountDao = testDb.accountDao
        currentAccountDao = testDb.currentAccountDao
    }

    @After
    fun tearDown() {
        testDb.close()
    }

    @Test
    fun getAllAccountsSync_returnsPersistedRows() =
        runTest {
            val first = AccountEntity.fromWalletAccount(AccountTestFixtures.traditional(id = "sync-1"))
            val second = AccountEntity.fromWalletAccount(AccountTestFixtures.traditional(id = "sync-2", address = "0x2"))
            accountDao.insert(first)
            accountDao.insert(second)

            val rows = accountDao.getAllAccountsSync()

            assertThat(rows.map { it.id }).containsExactlyInAnyOrder("sync-1", "sync-2")
        }

    @Test
    fun getSubAccountsByChain_filtersByParentAndChain() =
        runTest {
            val root = AccountTestFixtures.hdRoot(id = "parent")
            val ethSub =
                AccountTestFixtures.hdSub(
                    id = "eth-sub",
                    parentId = root.id,
                    chain = ChainType.ETH,
                    address = "0xeth"
                )
            val bscSub =
                AccountTestFixtures.hdSub(
                    id = "bsc-sub",
                    parentId = root.id,
                    chain = ChainType.BSC,
                    address = "0xbsc"
                )
            accountDao.insert(AccountEntity.fromWalletAccount(root))
            accountDao.insert(AccountEntity.fromWalletAccount(ethSub))
            accountDao.insert(AccountEntity.fromWalletAccount(bscSub))

            val ethRows = accountDao.getSubAccountsByChain(root.id, ChainType.ETH.bip44Code)

            assertThat(ethRows.map { it.id }).containsExactly("eth-sub")
        }

    @Test
    fun update_and_delete_entityMethods() =
        runTest {
            val entity = AccountEntity.fromWalletAccount(AccountTestFixtures.traditional(id = "entity-op", name = "old"))
            accountDao.insert(entity)

            accountDao.update(entity.copy(name = "new"))
            assertThat(accountDao.getAccountById("entity-op")?.name).isEqualTo("new")

            accountDao.delete(entity.copy(name = "new"))
            assertThat(accountDao.getAccountById("entity-op")).isNull()
        }

    @Test
    fun deleteCurrentAccount_clearsCurrentSelection() =
        runTest {
            val account = AccountTestFixtures.traditional(id = "current-row")
            accountDao.insert(AccountEntity.fromWalletAccount(account))
            currentAccountDao.setCurrentAccount(CurrentAccountEntity(accountId = account.id))

            accountDao.deleteCurrentAccount()

            assertThat(currentAccountDao.getCurrentAccountIdSync()).isNull()
        }

    @Test
    fun insert_duplicateId_throwsConstraintException() =
        runTest {
            val entity = AccountEntity.fromWalletAccount(AccountTestFixtures.traditional(id = "dup-id"))
            accountDao.insert(entity)

            assertThatThrownBy {
                runBlocking {
                    accountDao.insert(entity.copy(name = "other-name"))
                }
            }.isInstanceOf(SQLiteConstraintException::class.java)
        }
}
