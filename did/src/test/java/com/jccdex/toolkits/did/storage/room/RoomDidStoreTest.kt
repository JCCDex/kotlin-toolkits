package com.jccdex.toolkits.did.storage.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jccdex.toolkits.did.model.DidEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomDidStoreTest {
    private lateinit var database: DidRoomDatabase
    private lateinit var store: RoomDidStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, DidRoomDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        store = RoomDidStore(database.didDao())
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) {
            database.close()
        }
    }

    @Test
    fun `upsert preserves existing row id`() = runTest {
        store.upsert(
            DidEntity(
                did = "did:test:1",
                doc = """{"version":1}""",
                updatedAt = 100L
            )
        )

        val before = store.get("did:test:1")
        store.upsert(
            DidEntity(
                id = 999L,
                did = "did:test:1",
                doc = """{"version":2}""",
                updatedAt = 200L
            )
        )

        val after = store.get("did:test:1")
        assertThat(after?.id).isEqualTo(before?.id)
        assertThat(after?.doc).isEqualTo("""{"version":2}""")
    }

    @Test
    fun `queries prefer latest updated document`() = runTest {
        database.didDao().insert(DidRoomEntity(id = 1L, did = "did:test:1", doc = """{"version":1}""", updatedAt = 100L))
        database.didDao().insert(DidRoomEntity(id = 2L, did = "did:test:1", doc = """{"version":2}""", updatedAt = 200L))
        database.didDao().insert(DidRoomEntity(id = 3L, did = "did:test:2", doc = """{"version":3}""", updatedAt = 150L))

        val selected = store.get("did:test:1")
        val all = store.observeAll().first()

        assertThat(selected?.doc).isEqualTo("""{"version":2}""")
        assertThat(all.map { it.did to it.doc }).containsExactly(
            "did:test:1" to """{"version":2}""",
            "did:test:2" to """{"version":3}""",
            "did:test:1" to """{"version":1}"""
        )
    }

    @Test
    fun `delete removes all rows for did`() = runTest {
        database.didDao().insert(DidRoomEntity(id = 1L, did = "did:test:1", doc = """{"version":1}""", updatedAt = 100L))
        database.didDao().insert(DidRoomEntity(id = 2L, did = "did:test:1", doc = """{"version":2}""", updatedAt = 200L))

        store.delete("did:test:1")

        assertThat(store.get("did:test:1")).isNull()
        assertThat(database.didDao().count()).isZero()
    }
}
