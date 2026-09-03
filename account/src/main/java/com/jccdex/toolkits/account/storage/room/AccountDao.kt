package com.jccdex.toolkits.account.storage.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jccdex.toolkits.core.model.AccountClassification
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts")
    fun getAllAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts")
    suspend fun getAllAccountsSync(): List<AccountEntity>

    // M-13A: raw addresses without chain mapping — usable even with unknown-chain rows (M-15A).
    @Query("SELECT address FROM accounts")
    suspend fun getAllAddresses(): List<String>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getAccountById(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun getAccountByIdFlow(id: String): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts WHERE address = :address COLLATE NOCASE AND chain = :chain")
    suspend fun getAccountByAddressAndChain(
        address: String,
        chain: Long
    ): AccountEntity?

    @Query("SELECT * FROM accounts WHERE address = :address COLLATE NOCASE LIMIT 1")
    suspend fun getAccountByAddress(address: String): AccountEntity?

    @Query(
        "SELECT * FROM accounts WHERE address = :address COLLATE NOCASE " +
            "AND ${AccountClassification.SQL_IS_ROOT_HD} LIMIT 1"
    )
    suspend fun getRootAccountByAddress(address: String): AccountEntity?

    @Query(
        "SELECT * FROM accounts WHERE address = :address COLLATE NOCASE AND chain = :chain " +
            "AND ${AccountClassification.SQL_IS_NON_ROOT} LIMIT 1"
    )
    suspend fun getNonRootAccount(
        address: String,
        chain: Long
    ): AccountEntity?

    @Query("SELECT * FROM accounts WHERE chain = :chain")
    fun getAccountsByChain(chain: Long): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE ${AccountClassification.SQL_IS_ROOT_HD}")
    fun getRootHDAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE ${AccountClassification.SQL_IS_SUB_HD}")
    fun getSubHDAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE isHD = 0")
    fun getTraditionalAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE parentId = :parentId")
    fun getSubAccountsOf(parentId: String): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE parentId = :parentId AND chain = :chain")
    suspend fun getSubAccountsByChain(
        parentId: String,
        chain: Long
    ): List<AccountEntity>

    @Query("SELECT MAX(pathIndex) FROM accounts WHERE parentId = :parentId AND chain = :chain")
    suspend fun getMaxIndexByChain(
        parentId: String,
        chain: Long
    ): Int?

    @Query("SELECT COUNT(*) FROM accounts WHERE parentId = :parentId AND chain = :chain")
    suspend fun countSubAccountsByChain(
        parentId: String,
        chain: Long
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(accounts: List<AccountEntity>)

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE accounts SET name = :name WHERE id = :id")
    suspend fun updateName(
        id: String,
        name: String
    )

    @Query("UPDATE accounts SET name = :name WHERE address = :address COLLATE NOCASE")
    suspend fun updateNameByAddress(
        address: String,
        name: String
    )

    @Query("UPDATE accounts SET publicKey = :publicKey WHERE id = :id")
    suspend fun updatePublicKey(
        id: String,
        publicKey: String
    )

    @Query("UPDATE accounts SET parentId = :parentId WHERE id = :id")
    suspend fun updateParentId(
        id: String,
        parentId: String
    )

    @Query("SELECT COUNT(*) FROM accounts WHERE address = :address COLLATE NOCASE")
    suspend fun countAccounts(address: String): Int

    @Query("DELETE FROM accounts")
    suspend fun deleteAllAccounts()

    @Query("DELETE FROM current_account")
    suspend fun deleteCurrentAccount()
}
