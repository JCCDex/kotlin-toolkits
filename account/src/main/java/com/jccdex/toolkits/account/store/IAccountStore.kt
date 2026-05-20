package com.jccdex.toolkits.account.store

import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.WalletAccount
import kotlinx.coroutines.flow.Flow

interface IAccountStore {
    val accounts: Flow<List<WalletAccount>>

    val currentAccount: Flow<WalletAccount?>

    val rootHDAccounts: Flow<List<WalletAccount>>

    val subHDAccounts: Flow<List<WalletAccount>>

    val traditionalAccounts: Flow<List<WalletAccount>>

    fun getAccountsByChain(chain: ChainType): Flow<List<WalletAccount>>

    suspend fun addAccount(account: WalletAccount)

    suspend fun addAccounts(accounts: List<WalletAccount>)

    suspend fun removeAccount(accountId: String)

    suspend fun setCurrentAccount(accountId: String)

    suspend fun updateAccountName(
        accountId: String,
        name: String
    )

    suspend fun updateAccountNameByAddress(
        address: String,
        name: String
    )

    suspend fun updatePublicKey(
        accountId: String,
        publicKey: String
    )

    suspend fun updateParentId(
        accountId: String,
        parentId: String
    )

    suspend fun findByAddress(
        address: String,
        chain: ChainType
    ): WalletAccount?

    suspend fun findByAddress(address: String): WalletAccount?

    suspend fun findRootAccountByAddress(address: String): WalletAccount?

    suspend fun findNonRootAccount(
        address: String,
        chain: ChainType
    ): WalletAccount?

    suspend fun findById(id: String): WalletAccount?

    fun getSubAccountsOf(parentId: String): Flow<List<WalletAccount>>

    suspend fun getMaxIndexByChain(
        parentId: String,
        chain: ChainType
    ): Int

    suspend fun countSubAccountsByChain(
        parentId: String,
        chain: ChainType
    ): Int

    suspend fun getCurrentAccountId(): String?

    suspend fun getSameAccountsCount(address: String): Int

    suspend fun clearAllAccounts()
}
