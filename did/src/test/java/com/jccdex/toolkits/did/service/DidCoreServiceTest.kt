package com.jccdex.toolkits.did.service

import com.jccdex.toolkits.did.model.DidEntity
import com.jccdex.toolkits.did.store.IDidStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DidCoreServiceTest {
    private class MemoryDidStore : IDidStore {
        private val allState = MutableStateFlow<List<DidEntity>>(emptyList())
        private val items = linkedMapOf<String, DidEntity>()

        override fun observeAll(): Flow<List<DidEntity>> = allState.asStateFlow()

        override fun observe(did: String): Flow<DidEntity?> =
            allState.map { list -> list.firstOrNull { it.did == did } }

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
    ) : IDidResolver {
        override suspend fun resolve(did: String): String = value
    }

    @Test
    fun `saveNewCreatedDid upserts entity`() =
        runTest {
            val store = MemoryDidStore()
            val service = DidCoreService(store, StaticResolver("""{}"""))

            service.saveNewCreatedDid("did:test:1", """{"did":"did:test:1"}""")

            assertEquals("""{"did":"did:test:1"}""", store.get("did:test:1")?.doc)
        }

    @Test
    fun `resolveAndSaveDid keeps local doc when chain returns empty and pending create exists`() =
        runTest {
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
    fun `resolveAndSaveDid updates store when chain doc is newer`() =
        runTest {
            val store = MemoryDidStore()
            val resolver = StaticResolver("""{"did":"did:test:1","updated":"2025-01-01T00:00:00Z"}""")
            val service = DidCoreService(store, resolver)
            val local = """{"did":"did:test:1","updated":"2024-01-01T00:00:00Z"}"""

            store.upsert(DidEntity(did = "did:test:1", doc = local))

            val result = service.resolveAndSaveDid("did:test:1")

            // Chain is newer → store is updated to the chain doc (with Robolectric, JSONObject is real).
            assertEquals("""{"did":"did:test:1","updated":"2025-01-01T00:00:00Z"}""", result)
            assertEquals("""{"did":"did:test:1","updated":"2025-01-01T00:00:00Z"}""", store.get("did:test:1")?.doc)
        }

    @Test
    fun `deleteDidDocument deletes local record`() =
        runTest {
            val store = MemoryDidStore()
            val service = DidCoreService(store, StaticResolver("""{}"""))

            store.upsert(DidEntity(did = "did:test:1", doc = """{"updated":"2024-01-01T00:00:00Z"}"""))
            service.deleteDidDocument("did:test:1", """{"updated":"2024-01-01T00:00:00Z"}""")

            assertNull(store.get("did:test:1"))
        }

    @Test
    fun `resolveAndSaveDid returns null and deletes when chain is empty without pending create`() =
        runTest {
            val store = MemoryDidStore()
            val resolver = StaticResolver("{}")
            val service = DidCoreService(store, resolver)

            store.upsert(DidEntity(did = "did:test:1", doc = """{"did":"did:test:1"}"""))

            val result = service.resolveAndSaveDid("did:test:1")

            assertNull(result)
            assertNull(store.get("did:test:1"))
        }

    @Test
    fun `resolveAndSaveDid keeps local doc when chain doc is blank`() =
        runTest {
            val store = MemoryDidStore()
            val resolver = StaticResolver("   ")
            val service = DidCoreService(store, resolver)
            val local = """{"did":"did:test:1"}"""

            store.upsert(DidEntity(did = "did:test:1", doc = local))

            val result = service.resolveAndSaveDid("did:test:1")

            assertNull(result)
            assertEquals(local, store.get("did:test:1")?.doc)
        }

    @Test
    fun `saveNewNicknameDid tracks pending nickname update`() =
        runTest {
            val store = MemoryDidStore()
            val resolver = StaticResolver("""{"service":[{"type":"Profile","serviceEndpoint":{"nickname":"alice"}}]}""")
            val service = DidCoreService(store, resolver)
            val doc = """{"service":[{"type":"Profile","serviceEndpoint":{"nickname":"alice"}}]}"""

            service.saveNewNicknameDid("did:test:1", doc)
            val result = service.resolveAndSaveDid("did:test:1")

            assertEquals(doc, result)
            assertEquals(doc, store.get("did:test:1")?.doc)
        }

    @Test
    fun `saveNewAvatarDid tracks pending avatar update and resolve keeps local doc when mismatch`() =
        runTest {
            val store = MemoryDidStore()
            val resolver =
                StaticResolver("""{"service":[{"type":"Profile","serviceEndpoint":{"preferredAvatar":"cred-2"}}]}""")
            val service = DidCoreService(store, resolver)
            val local = """{"service":[{"type":"Profile","serviceEndpoint":{"preferredAvatar":"cred-1"}}]}"""
            store.upsert(DidEntity(did = "did:test:1", doc = local))

            service.saveNewAvatarDid("did:test:1", local)
            val result = service.resolveAndSaveDid("did:test:1")

            assertEquals(local, result)
            assertEquals(local, store.get("did:test:1")?.doc)
        }

    @Test
    fun `resolveAndSaveDid upserts when local is missing and chain has document`() =
        runTest {
            val store = MemoryDidStore()
            val chainDoc = """{"did":"did:test:1","updated":"2025-02-01T00:00:00Z"}"""
            val service = DidCoreService(store, StaticResolver(chainDoc))

            val result = service.resolveAndSaveDid("did:test:1")

            assertEquals(chainDoc, result)
            assertEquals(chainDoc, store.get("did:test:1")?.doc)
        }

    @Test
    fun `resolveAndSaveDid keeps deleted state when pending delete timestamp matches chain`() =
        runTest {
            val store = MemoryDidStore()
            val updated = "2025-01-01T00:00:00Z"
            val local = """{"updated":"$updated"}"""
            val service = DidCoreService(store, StaticResolver("""{"updated":"$updated"}"""))
            store.upsert(DidEntity(did = "did:test:1", doc = local))
            service.deleteDidDocument("did:test:1", local)

            // Local is gone and chain still returns the deleted version → must NOT be revived (H-DID3).
            val result = service.resolveAndSaveDid("did:test:1")

            assertNull(result)
            assertNull(store.get("did:test:1"))
        }

    @Test
    fun `resolveAndSaveDid keeps local on repeated misses within grace period`() =
        runTest {
            val store = MemoryDidStore()
            val service = DidCoreService(store, StaticResolver("{}"))
            val local = """{"did":"did:test:1"}"""
            service.saveNewCreatedDid("did:test:1", local)

            // Two resolves while chain still missing: both must keep the local doc (H-DID2).
            val r1 = service.resolveAndSaveDid("did:test:1")
            val r2 = service.resolveAndSaveDid("did:test:1")

            assertEquals(local, r1)
            assertEquals(local, r2)
            assertEquals(local, store.get("did:test:1")?.doc)
        }

    @Test
    fun `resolveAndSaveDid deletes local after grace period expires`() =
        runTest {
            val store = MemoryDidStore()
            var now = 1_000L
            val service = DidCoreService(store, StaticResolver("{}"), clock = { now })
            val local = """{"did":"did:test:1"}"""
            service.saveNewCreatedDid("did:test:1", local)

            now += 5 * 60_000L + 1
            val result = service.resolveAndSaveDid("did:test:1")

            assertNull(result)
            assertNull(store.get("did:test:1"))
        }

    @Test
    fun `resolveAndSaveDid deletes local when resolver returns bridge null sentinel`() =
        runTest {
            val store = MemoryDidStore()
            val service = DidCoreService(store, StaticResolver("null"))
            store.upsert(DidEntity(did = "did:test:1", doc = """{"id":"did:test:1"}"""))

            assertNull(service.resolveAndSaveDid("did:test:1"))
            assertNull(store.get("did:test:1"))
        }

    @Test
    fun `resolveAndSaveDid returns null when resolver throws`() =
        runTest {
            val store = MemoryDidStore()
            val service =
                DidCoreService(
                    store,
                    object : IDidResolver {
                        override suspend fun resolve(did: String): String = error("network down")
                    }
                )
            store.upsert(DidEntity(did = "did:test:1", doc = """{"did":"did:test:1"}"""))

            assertNull(service.resolveAndSaveDid("did:test:1"))
        }

    @Test
    fun `resolveAndSaveDid clears pending nickname when chain matches`() =
        runTest {
            val store = MemoryDidStore()
            val doc =
                """
                {
                  "service":[{"type":"Profile","serviceEndpoint":{"nickname":"bob"}}],
                  "updated":"2025-01-01T00:00:00Z"
                }
                """.trimIndent()
            val service = DidCoreService(store, StaticResolver(doc))

            service.saveNewNicknameDid("did:test:1", doc)
            val result = service.resolveAndSaveDid("did:test:1")

            assertEquals(doc, result)
        }

    @Test
    fun `saveNewAvatarDid tracks pending avatar update`() =
        runTest {
            val store = MemoryDidStore()
            val resolver =
                StaticResolver("""{"service":[{"type":"Profile","serviceEndpoint":{"preferredAvatar":"cred-1"}}]}""")
            val service = DidCoreService(store, resolver)
            val doc = """{"service":[{"type":"Profile","serviceEndpoint":{"preferredAvatar":"cred-1"}}]}"""

            service.saveNewAvatarDid("did:test:1", doc)
            val result = service.resolveAndSaveDid("did:test:1")

            assertEquals(doc, result)
            assertEquals(doc, store.get("did:test:1")?.doc)
        }

    // L-8: Stale entry cleanup tests
    @Test
    fun `cleanupStaleEntries removes expired pending create entries`() =
        runTest {
            val store = MemoryDidStore()
            var now = 0L
            val service = DidCoreService(store, StaticResolver("{}"), clock = { now })

            // Create a pending entry at time 0
            service.saveNewCreatedDid("did:test:1", """{"did":"did:test:1"}""")

            // Advance time beyond threshold (1 hour)
            now = 61 * 60_000L

            // Trigger cleanup via resolveAndSaveDid
            service.resolveAndSaveDid("did:test:1")

            // Pending entry should be cleaned up, local doc deleted
            assertNull(store.get("did:test:1"))
        }

    @Test
    fun `cleanupStaleEntries keeps fresh pending entries`() =
        runTest {
            val store = MemoryDidStore()
            var now = 0L
            val service = DidCoreService(store, StaticResolver("{}"), clock = { now })

            // Create a pending entry at time 0
            service.saveNewCreatedDid("did:test:1", """{"did":"did:test:1"}""")

            // Advance time but stay within grace period (3 minutes < 5 min grace)
            now = 3 * 60_000L

            // Trigger cleanup via resolveAndSaveDid
            val result = service.resolveAndSaveDid("did:test:1")

            // Fresh pending entry should be kept, local doc preserved
            assertEquals("""{"did":"did:test:1"}""", result)
        }

    @Test
    fun `cleanupStaleEntries removes expired pending avatar entries`() =
        runTest {
            val store = MemoryDidStore()
            var now = 0L
            val service =
                DidCoreService(
                    store,
                    StaticResolver(
                        """{"service":[{"type":"Profile","serviceEndpoint":{"preferredAvatar":"cred-1"}}]}"""
                    ),
                    clock = { now }
                )
            val doc = """{"service":[{"type":"Profile","serviceEndpoint":{"preferredAvatar":"cred-1"}}]}"""

            // Create a pending avatar entry at time 0
            service.saveNewAvatarDid("did:test:1", doc)

            // Advance time beyond threshold (1 hour)
            now = 61 * 60_000L

            // Resolve should work normally (pending entry cleaned but chain has match)
            val result = service.resolveAndSaveDid("did:test:1")

            assertEquals(doc, result)
        }
}
