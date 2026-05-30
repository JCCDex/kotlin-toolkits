package com.jccdex.toolkits.did

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jccdex.toolkits.did.model.ChainType
import com.jccdex.toolkits.did.model.DidEntity
import com.jccdex.toolkits.did.model.WalletAccount
import com.jccdex.toolkits.did.port.IDidBridge
import com.jccdex.toolkits.did.sdk.DidSdk
import com.jccdex.toolkits.did.service.DidCoreService
import com.jccdex.toolkits.did.storage.room.DidRoomDatabase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DidSdkIntegrationTest {
    private var databaseName: String? = null

    @After
    fun tearDown() {
        val name = databaseName
        if (name != null) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            DidRoomDatabase.getInstance(context, name).close()
        }
    }

    @Test
    fun create_withContext_returnsUsableSdk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sdk = DidSdk.create(context)
        val account =
            WalletAccount(
                address = "jcreate",
                chain = ChainType.SWTC,
                publicKey = "pub"
            )

        assertEquals("did:swtc:jcreate", sdk.toDid(account))
    }

    @Test
    fun create_withContext_readsPersistedDocuments() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val did = "did:swtc:${System.nanoTime()}"
            val sdk = DidSdk.create(context)

            DidRoomDatabase
                .getInstance(context)
                .didDao()
                .insert(
                    com.jccdex.toolkits.did.storage.room.DidRoomEntity(
                        did = did,
                        doc = """{"created":"integration"}"""
                    )
                )

            assertThat(sdk.getDidDocument(did)?.doc).isEqualTo("""{"created":"integration"}""")
        }

    @Test
    fun create_withCustomStore_wiresCoreService() =
        runTest {
            val bridge = mockk<IDidBridge>(relaxed = true)
            val resolver =
                object : com.jccdex.toolkits.did.service.IDidResolver {
                    override suspend fun resolve(did: String): String = ""
                }
            val testDb = DidTestDatabase.inMemory(ApplicationProvider.getApplicationContext())
            try {
                val sdk = DidSdk.create(bridge, testDb.store, resolver)

                testDb.store.upsert(DidEntity(did = "did:test:custom", doc = """{"v":1}"""))

                assertEquals("""{"v":1}""", sdk.getDidDocument("did:test:custom")?.doc)
            } finally {
                testDb.close()
            }
        }

    @Test
    fun generateDid_returnsNullWhenCoreThrows() =
        runTest {
            val bridge = mockk<IDidBridge>(relaxed = true)
            val failingCore = mockk<DidCoreService>(relaxed = true)
            coEvery { failingCore.getDidDocument(any()) } throws RuntimeException("db down")

            val sdk = DidSdk(bridge, failingCore)

            assertNull(sdk.generateDid("did:ethr:0x123"))
        }

    @Test
    fun formatAddress_doesNotShortenEightCharacterAddresses() {
        val bridge = mockk<IDidBridge>(relaxed = true)
        val core = mockk<DidCoreService>(relaxed = true)
        val sdk = DidSdk(bridge, core)

        assertEquals("12345678", sdk.formatAddress("12345678"))
        assertEquals("0x12***5678", sdk.formatAddress("0x12345678"))
    }

    @Test
    fun getProfile_readsNicknameOnly() {
        val bridge = mockk<IDidBridge>(relaxed = true)
        val core = mockk<DidCoreService>(relaxed = true)
        val sdk = DidSdk(bridge, core)

        val profile =
            sdk.getProfile(
                """
                {
                  "service":[{"type":"Profile","serviceEndpoint":{"nickname":"only-nick"}}]
                }
                """.trimIndent()
            )

        assertEquals("only-nick", profile?.nickname)
        assertEquals("", profile?.preferredAvatar)
    }

    @Test
    fun getProfile_readsPreferredAvatarOnly() {
        val bridge = mockk<IDidBridge>(relaxed = true)
        val core = mockk<DidCoreService>(relaxed = true)
        val sdk = DidSdk(bridge, core)

        val profile =
            sdk.getProfile(
                """
                {
                  "service":[{"type":"Profile","serviceEndpoint":{"preferredAvatar":"cred-1"}}]
                }
                """.trimIndent()
            )

        assertEquals("", profile?.nickname)
        assertEquals("cred-1", profile?.preferredAvatar)
    }

    @Test
    fun toDid_rejectsUnsupportedChain() {
        val bridge = mockk<IDidBridge>(relaxed = true)
        val core = mockk<DidCoreService>(relaxed = true)
        val sdk = DidSdk(bridge, core)
        val chain = mockk<ChainType>(relaxed = true)
        every { chain.isEvmChain() } returns false
        every { chain.equals(ChainType.SWTC) } returns false
        every { chain.toString() } returns "FAKE"
        val wallet = mockk<WalletAccount>()
        every { wallet.address } returns "addr"
        every { wallet.chain } returns chain

        assertThatThrownBy { sdk.toDid(wallet) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unsupported chain type")
    }
}
