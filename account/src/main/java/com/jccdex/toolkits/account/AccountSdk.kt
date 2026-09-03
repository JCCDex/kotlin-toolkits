package com.jccdex.toolkits.account

import android.content.Context
import com.jccdex.toolkits.account.orchestrator.AccountOrchestrator
import com.jccdex.toolkits.account.storage.room.AccountRoomDatabase
import com.jccdex.toolkits.account.store.IAccountStore
import com.jccdex.toolkits.account.store.RoomAccountStore
import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.WalletAccount
import com.jccdex.toolkits.vault.VaultRepository
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

class AccountSdk internal constructor(
    private val store: IAccountStore
) {
    // M-19A: share one AccountOrchestrator (and its Mutex) per vault — a fresh instance per call
    // would bypass the serialization protecting deriveSubAccount/removeAccount.
    private val orchestrators = ConcurrentHashMap<VaultRepository, AccountOrchestrator>()

    fun orchestrator(vaultRepository: VaultRepository): AccountOrchestrator =
        orchestrators.getOrPut(vaultRepository) { AccountOrchestrator(store, vaultRepository) }

    /**
     * M-13A: reconciliation — vault keys with no store account record (orphans from a crash or
     * partial write). Non-destructive; hosts can surface or clean these up.
     */
    suspend fun listOrphanKeys(vaultRepository: VaultRepository): List<String> =
        orchestrator(vaultRepository).listOrphanKeys()

    val accounts: Flow<List<WalletAccount>> get() = store.accounts

    val currentAccount: Flow<WalletAccount?> get() = store.currentAccount

    val rootHDAccounts: Flow<List<WalletAccount>> get() = store.rootHDAccounts

    val subHDAccounts: Flow<List<WalletAccount>> get() = store.subHDAccounts

    val traditionalAccounts: Flow<List<WalletAccount>> get() = store.traditionalAccounts

    fun getAccountsByChain(chain: ChainType): Flow<List<WalletAccount>> = store.getAccountsByChain(chain)

    suspend fun addAccount(account: WalletAccount) = store.addAccount(account)

    suspend fun addAccounts(accounts: List<WalletAccount>) = store.addAccounts(accounts)

    suspend fun removeAccount(accountId: String) = store.removeAccount(accountId)

    suspend fun setCurrentAccount(accountId: String) = store.setCurrentAccount(accountId)

    suspend fun updateAccountName(
        accountId: String,
        name: String
    ) = store.updateAccountName(accountId, name)

    suspend fun updateAccountNameByAddress(
        address: String,
        name: String
    ) = store.updateAccountNameByAddress(address, name)

    suspend fun updatePublicKey(
        accountId: String,
        publicKey: String
    ) = store.updatePublicKey(accountId, publicKey)

    suspend fun updateParentId(
        accountId: String,
        parentId: String
    ) = store.updateParentId(accountId, parentId)

    suspend fun findByAddress(
        address: String,
        chain: ChainType
    ): WalletAccount? = store.findByAddress(address, chain)

    suspend fun findByAddress(address: String): WalletAccount? = store.findByAddress(address)

    suspend fun findRootAccountByAddress(address: String): WalletAccount? = store.findRootAccountByAddress(address)

    suspend fun findNonRootAccount(
        address: String,
        chain: ChainType
    ): WalletAccount? = store.findNonRootAccount(address, chain)

    suspend fun findById(id: String): WalletAccount? = store.findById(id)

    fun getSubAccountsOf(parentId: String): Flow<List<WalletAccount>> = store.getSubAccountsOf(parentId)

    suspend fun getMaxIndexByChain(
        parentId: String,
        chain: ChainType
    ): Int = store.getMaxIndexByChain(parentId, chain)

    suspend fun countSubAccountsByChain(
        parentId: String,
        chain: ChainType
    ): Int = store.countSubAccountsByChain(parentId, chain)

    suspend fun getCurrentAccountId(): String? = store.getCurrentAccountId()

    suspend fun getSameAccountsCount(address: String): Int = store.getSameAccountsCount(address)

    suspend fun clearAllAccounts() = store.clearAllAccounts()

    companion object {
        @Volatile
        private var instance: AccountSdk? = null

        fun get(context: Context): AccountSdk {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                return create(context).also { instance = it }
            }
        }

        fun create(context: Context): AccountSdk {
            val appContext = context.applicationContext
            val database = AccountRoomDatabase.getInstance(appContext)
            val store =
                RoomAccountStore(
                    accountDao = database.accountDao(),
                    currentAccountDao = database.currentAccountDao()
                )
            return AccountSdk(store)
        }

        fun createForTest(store: IAccountStore): AccountSdk = AccountSdk(store)

        fun resetForTest() {
            instance = null
        }
    }
}
