package com.jccdex.toolkits.did.storage.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DidRoomDao {
    @Query("SELECT * FROM did_documents WHERE did = :did ORDER BY updatedAt DESC, id DESC LIMIT 1")
    fun observeByDid(did: String): Flow<DidRoomEntity?>

    @Query("SELECT * FROM did_documents WHERE did = :did ORDER BY updatedAt DESC, id DESC LIMIT 1")
    suspend fun findByDid(did: String): DidRoomEntity?

    @Query("SELECT * FROM did_documents ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<DidRoomEntity>>

    @Query("SELECT * FROM did_documents ORDER BY updatedAt DESC, id DESC")
    suspend fun findAll(): List<DidRoomEntity>

    @Query("SELECT COUNT(*) FROM did_documents")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DidRoomEntity)

    @Query("DELETE FROM did_documents WHERE did = :did")
    suspend fun deleteByDid(did: String)
}
