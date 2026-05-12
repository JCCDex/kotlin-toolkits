package com.jccdex.toolkits.did.storage.room

import com.jccdex.toolkits.did.model.DidEntity
import com.jccdex.toolkits.did.store.DidStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomDidStore(
    private val didDao: DidRoomDao
) : DidStore {
    override fun observeAll(): Flow<List<DidEntity>> = didDao.observeAll().map { entities -> entities.map { it.toCore() } }

    override fun observe(did: String): Flow<DidEntity?> = didDao.observeByDid(did).map { it?.toCore() }

    override suspend fun get(did: String): DidEntity? = didDao.findByDid(did)?.toCore()

    override suspend fun upsert(entity: DidEntity) {
        val existing = didDao.findByDid(entity.did)
        didDao.insert(entity.toRoom(existing?.id ?: entity.id))
    }

    override suspend fun delete(did: String) {
        didDao.deleteByDid(did)
    }

    private fun DidRoomEntity.toCore(): DidEntity =
        DidEntity(
            id = id,
            did = did,
            doc = doc,
            updatedAt = updatedAt
        )

    private fun DidEntity.toRoom(existingId: Long? = null): DidRoomEntity =
        DidRoomEntity(
            id = existingId ?: id,
            did = did,
            doc = doc,
            updatedAt = updatedAt
        )
}
