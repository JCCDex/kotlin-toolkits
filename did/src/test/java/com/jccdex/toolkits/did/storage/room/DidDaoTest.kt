package com.jccdex.toolkits.did.storage.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jccdex.toolkits.did.DidTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DidDaoTest {
    private lateinit var testDb: DidTestDatabase
    private lateinit var didDao: DidRoomDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = DidTestDatabase.inMemory(context)
        didDao = testDb.didDao
    }

    @After
    fun tearDown() {
        testDb.close()
    }

    @Test
    fun findAll_returnsAllRows() =
        runTest {
            didDao.insert(entity("did:a", """{"v":1}""", updatedAt = 100L))
            didDao.insert(entity("did:b", """{"v":2}""", updatedAt = 200L))

            val rows = didDao.findAll()

            assertThat(rows).hasSize(2)
            assertThat(rows.map { it.did }).containsExactlyInAnyOrder("did:a", "did:b")
        }

    @Test
    fun count_returnsRowTotal() =
        runTest {
            didDao.insert(entity("did:a", """{"v":1}"""))
            didDao.insert(entity("did:a", """{"v":2}"""))

            assertThat(didDao.count()).isEqualTo(2)
        }

    @Test
    fun findByDid_returnsLatestUpdatedRow() =
        runTest {
            didDao.insert(entity("did:a", """{"v":1}""", updatedAt = 100L))
            didDao.insert(entity("did:a", """{"v":2}""", updatedAt = 300L))
            didDao.insert(entity("did:a", """{"v":3}""", updatedAt = 200L))

            val latest = didDao.findByDid("did:a")

            assertThat(latest?.doc).isEqualTo("""{"v":2}""")
        }

    @Test
    fun observeByDid_emitsLatestRow() =
        runTest {
            didDao.insert(entity("did:a", """{"v":1}""", updatedAt = 100L))
            didDao.insert(entity("did:a", """{"v":2}""", updatedAt = 200L))

            val observed = didDao.observeByDid("did:a").first()

            assertThat(observed?.doc).isEqualTo("""{"v":2}""")
        }

    @Test
    fun insert_replaceOverwritesRowWithSamePrimaryKey() =
        runTest {
            val original = entity("did:a", """{"v":1}""", id = 1L)
            didDao.insert(original)
            didDao.insert(original.copy(doc = """{"v":2}""", updatedAt = 200L))

            assertThat(didDao.count()).isEqualTo(1)
            assertThat(didDao.findByDid("did:a")?.doc).isEqualTo("""{"v":2}""")
        }

    @Test
    fun deleteByDid_removesAllRowsForDid() =
        runTest {
            didDao.insert(entity("did:a", """{"v":1}"""))
            didDao.insert(entity("did:a", """{"v":2}"""))
            didDao.insert(entity("did:b", """{"v":3}"""))

            didDao.deleteByDid("did:a")

            assertThat(didDao.count()).isEqualTo(1)
            assertThat(didDao.findByDid("did:a")).isNull()
            assertThat(didDao.findByDid("did:b")).isNotNull
        }

    private fun entity(
        did: String,
        doc: String,
        id: Long = 0,
        updatedAt: Long = System.currentTimeMillis()
    ): DidRoomEntity = DidRoomEntity(id = id, did = did, doc = doc, updatedAt = updatedAt)
}
