package com.jccdex.toolkits.did.store

import com.jccdex.toolkits.did.model.DidEntity
import kotlinx.coroutines.flow.Flow

interface IDidStore {
    fun observeAll(): Flow<List<DidEntity>>

    fun observe(did: String): Flow<DidEntity?>

    suspend fun get(did: String): DidEntity?

    suspend fun upsert(entity: DidEntity)

    suspend fun delete(did: String)
}
