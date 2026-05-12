package com.jccdex.toolkits.did.service

import com.jccdex.toolkits.did.model.DidEntity
import com.jccdex.toolkits.did.store.DidStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DidCoreServiceTest {
    private class MemoryDidStore : DidStore {
        private val allState = MutableStateFlow<List<DidEntity>>(emptyList())
        private val items = linkedMapOf<String, DidEntity>()

        override fun observeAll(): Flow<List<DidEntity>> = allState.asStateFlow()

        override fun observe(did: String): Flow<DidEntity?> = allState.map { list -> list.firstOrNull { it.did == did } }

        override suspend fun get(did: String): DidEntity? = items[did]

        override suspend fun upsert(entity: DidEntity) {
            items[entity.did] = entity
            allState.value = items.values.toList()
        }

        override suspend fun delete(did: String) {
            items.remove(did)
            allState.value = items.values.toList()
        }
    }

    private class StaticResolver(
        private var value: String
    ) : DidResolver {
        override suspend fun resolve(did: String): String = value
    }

    @Test
    fun `saveNewCreatedDid upserts entity`() = runTest {
        val store = MemoryDidStore()
        val service = DidCoreService(store, StaticResolver("""{}"""))

        service.saveNewCreatedDid("did:test:1", """{"did":"did:test:1"}""")

        assertEquals("""{"did":"did:test:1"}""", store.get("did:test:1")?.doc)
    }

    @Test
    fun `resolveAndSaveDid keeps local doc when chain returns empty and pending create exists`() = runTest {
        val store = MemoryDidStore()
        val resolver = StaticResolver("{}")
        val service = DidCoreService(store, resolver)
        val local = """{"did":"did:test:1"}"""

        service.saveNewCreatedDid("did:test:1", local)
        val result = service.resolveAndSaveDid("did:test:1")

        assertEquals(local, result)
        assertEquals(local, store.get("did:test:1")?.doc)
    }

    @Test
    fun `resolveAndSaveDid updates store when chain doc is newer`() = runTest {
        val store = MemoryDidStore()
        val resolver = StaticResolver("""{"did":"did:test:1","updated":"2025-01-01T00:00:00Z"}""")
        val service = DidCoreService(store, resolver)
        val local = """{"did":"did:test:1","updated":"2024-01-01T00:00:00Z"}"""

        store.upsert(DidEntity(did = "did:test:1", doc = local))

        val result = service.resolveAndSaveDid("did:test:1")

        assertEquals("""{"did":"did:test:1","updated":"2024-01-01T00:00:00Z"}""", result)
        assertEquals("""{"did":"did:test:1","updated":"2024-01-01T00:00:00Z"}""", store.get("did:test:1")?.doc)
    }

    @Test
    fun `deleteDidDocument deletes local record`() = runTest {
        val store = MemoryDidStore()
        val service = DidCoreService(store, StaticResolver("""{}"""))

        store.upsert(DidEntity(did = "did:test:1", doc = """{"updated":"2024-01-01T00:00:00Z"}"""))
        service.deleteDidDocument("did:test:1", """{"updated":"2024-01-01T00:00:00Z"}""")

        assertNull(store.get("did:test:1"))
    }
}
