package com.jccdex.toolkits.account.storage.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CurrentAccountDao {
    @Query("SELECT accountId FROM current_account WHERE id = 1")
    fun getCurrentAccountId(): Flow<String?>

    @Query("SELECT accountId FROM current_account WHERE id = 1")
    suspend fun getCurrentAccountIdSync(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCurrentAccount(entity: CurrentAccountEntity)

    @Query("DELETE FROM current_account WHERE accountId = :accountId")
    suspend fun clearIfCurrent(accountId: String)

    @Query("DELETE FROM current_account")
    suspend fun deleteAll()
}
