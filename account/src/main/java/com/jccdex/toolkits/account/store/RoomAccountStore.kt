package com.jccdex.toolkits.account.store

import com.jccdex.toolkits.account.storage.room.AccountDao
import com.jccdex.toolkits.account.storage.room.AccountEntity
import com.jccdex.toolkits.account.storage.room.CurrentAccountDao
import com.jccdex.toolkits.account.storage.room.CurrentAccountEntity
import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.WalletAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
class RoomAccountStore(
    private val accountDao: AccountDao,
    private val currentAccountDao: CurrentAccountDao
) : IAccountStore {
    override val accounts: Flow<List<WalletAccount>> =
        accountDao
            .getAllAccounts()
            .map { entities -> entities.map { it.toWalletAccount() } }

    override val currentAccount: Flow<WalletAccount?> =
        currentAccountDao
            .getCurrentAccountId()
            .flatMapLatest { currentId ->
                if (currentId == null) {
                    flowOf(null)
                } else {
                    accountDao.getAccountByIdFlow(currentId).map { it?.toWalletAccount() }
                }
            }

    override val rootHDAccounts: Flow<List<WalletAccount>> =
        accountDao
            .getRootHDAccounts()
            .map { entities -> entities.map { it.toWalletAccount() } }

    override val subHDAccounts: Flow<List<WalletAccount>> =
        accountDao
            .getSubHDAccounts()
            .map { entities -> entities.map { it.toWalletAccount() } }

    override val traditionalAccounts: Flow<List<WalletAccount>> =
        accountDao
            .getTraditionalAccounts()
            .map { entities -> entities.map { it.toWalletAccount() } }

    override fun getAccountsByChain(chain: ChainType): Flow<List<WalletAccount>> =
        accountDao
            .getAccountsByChain(chain.bip44Code)
            .map { entities -> entities.map { it.toWalletAccount() } }

    override suspend fun addAccount(account: WalletAccount) {
        accountDao.insert(AccountEntity.fromWalletAccount(account))
    }

    override suspend fun addAccounts(accounts: List<WalletAccount>) {
        accountDao.insertAll(accounts.map { AccountEntity.fromWalletAccount(it) })
    }

    override suspend fun removeAccount(accountId: String) {
        accountDao.deleteById(accountId)
        currentAccountDao.clearIfCurrent(accountId)
    }

    override suspend fun setCurrentAccount(accountId: String) {
        accountDao.getAccountById(accountId) ?: throw NoSuchElementException("Account not found: $accountId")
        currentAccountDao.setCurrentAccount(CurrentAccountEntity(accountId = accountId))
    }

    override suspend fun updateAccountName(
        accountId: String,
        name: String
    ) {
        accountDao.updateName(accountId, name)
    }

    override suspend fun updateAccountNameByAddress(
        address: String,
        name: String
    ) {
        accountDao.updateNameByAddress(address, name)
    }

    override suspend fun updatePublicKey(
        accountId: String,
        publicKey: String
    ) {
        accountDao.updatePublicKey(accountId, publicKey)
    }

    override suspend fun updateParentId(
        accountId: String,
        parentId: String
    ) {
        accountDao.updateParentId(accountId, parentId)
    }

    // M-13A: raw addresses without chain mapping — orphan reconciliation stays usable even when a
    // row carries an unknown chain code (which would make toWalletAccount throw, M-15A).
    override suspend fun listAllAddresses(): List<String> = accountDao.getAllAddresses()

    override suspend fun findByAddress(
        address: String,
        chain: ChainType
    ): WalletAccount? = accountDao.getAccountByAddressAndChain(address, chain.bip44Code)?.toWalletAccount()

    override suspend fun findByAddress(address: String): WalletAccount? =
        accountDao.getAccountByAddress(
            address
        )?.toWalletAccount()

    override suspend fun findRootAccountByAddress(address: String): WalletAccount? =
        accountDao.getRootAccountByAddress(
            address
        )?.toWalletAccount()

    override suspend fun findNonRootAccount(
        address: String,
        chain: ChainType
    ): WalletAccount? = accountDao.getNonRootAccount(address, chain.bip44Code)?.toWalletAccount()

    override suspend fun findById(id: String): WalletAccount? = accountDao.getAccountById(id)?.toWalletAccount()

    override fun getSubAccountsOf(parentId: String): Flow<List<WalletAccount>> =
        accountDao
            .getSubAccountsOf(parentId)
            .map { entities -> entities.map { it.toWalletAccount() } }

    override suspend fun getMaxIndexByChain(
        parentId: String,
        chain: ChainType
    ): Int = accountDao.getMaxIndexByChain(parentId, chain.bip44Code) ?: -1

    override suspend fun countSubAccountsByChain(
        parentId: String,
        chain: ChainType
    ): Int = accountDao.countSubAccountsByChain(parentId, chain.bip44Code)

    override suspend fun getCurrentAccountId(): String? = currentAccountDao.getCurrentAccountIdSync()

    override suspend fun getSameAccountsCount(address: String): Int = accountDao.countAccounts(address)

    override suspend fun clearAllAccounts() {
        accountDao.deleteAllAccounts()
        currentAccountDao.deleteAll()
    }
}
