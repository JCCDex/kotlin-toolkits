package com.jccdex.toolkits.vault

import android.app.Application
import android.content.Context
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.jccdex.toolkits.vault.model.VaultPrivateKeyImport
import com.jccdex.toolkits.vault.util.wipe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale.getDefault
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class VaultRepositoryTest {
    private lateinit var appContext: Context
    private lateinit var vault: VaultRepository

    companion object {
        private var cleanedUp = false
    }

    @Before
    fun setup() {
        appContext = ApplicationProvider.getApplicationContext()

        if (!cleanedUp) {
            cleanedUp = true
            appContext.dataStoreFile("vault.pb").delete()
            VaultRepository::class.java.getDeclaredField("instance").apply {
                isAccessible = true
                set(null, null)
            }
        }
        vault = VaultRepository.get(appContext)

        mockkStatic("com.jccdex.toolkits.vault.util.WipeKt")
        every { any<CharArray>().wipe() } answers { }
        every { any<ByteArray>().wipe() } answers { }
    }

    fun clearRecord() {
        clearAllMocks(answers = false)
    }

    @After
    fun tearDown() {
        unmockkAll()
        val f = appContext.dataStoreFile("vault.pb")
        if (f.exists()) f.delete()
    }

    @Test
    fun test_00_initVault() {
        val r1 = VaultRepository.get(appContext)
        val r2 = VaultRepository.get(appContext)
        assertNotNull(r1)
        assertSame(r1, r2)
    }

    @Test
    fun test_01_initAndVerify() =
        runTest {
            val password = "123456789ab@][".toByteArray()
            Assertions.assertThat(vault.verifyPassword(password)).isFalse
            verify(exactly = 1) { password.wipe() }
            verify(exactly = 1) { any<ByteArray>().wipe() }
            clearRecord()

            vault.initializePassword(password)
            verify(exactly = 1) { password.wipe() }
            verify(exactly = 2) { any<ByteArray>().wipe() }
            clearRecord()

            Assertions.assertThat(vault.verifyPassword(password)).isTrue
            verify(exactly = 1) { password.wipe() }
            verify(exactly = 2) { any<ByteArray>().wipe() }
            clearRecord()

            val newPassword = "1234".toByteArray()
            vault.initializePassword(newPassword)
            verify(exactly = 1) { newPassword.wipe() }
            verify(exactly = 1) { any<ByteArray>().wipe() }
            clearRecord()

            Assertions.assertThat(vault.verifyPassword(password)).isTrue
            verify(exactly = 1) { password.wipe() }
            verify(exactly = 2) { any<ByteArray>().wipe() }
            clearRecord()

            Assertions.assertThat(vault.verifyPassword(newPassword)).isFalse
            verify(exactly = 1) { newPassword.wipe() }
            verify(exactly = 2) { any<ByteArray>().wipe() }
        }

    @Test
    fun test_02_importMnemonic() =
        runTest {
            val mnemonic =
                "evolve paddle gun glance swap clarify shoe youth sweet air change chunk".toByteArray()
            val privateKey =
                "48EF9848FB097FFD086E38B9EF54606E17CC77FBC89B158E270B8D0B13A45417".toByteArray()
            val address = "0x6db849ed4ce8fe95044bffbfe4d291af34b4445d".uppercase()
            vault.importMnemonic(address, mnemonic, privateKey)
            verify(exactly = 4) { any<ByteArray>().wipe() }
            clearRecord()

            val password = "123456789ab@][".toByteArray()
            val derivedPrivateKey = vault.getPrivateKey(address, password)
            Assertions.assertThat(derivedPrivateKey).isEqualTo(privateKey)
            verify(exactly = 1) { password.wipe() }
            verify(exactly = 3) { any<ByteArray>().wipe() }

            val lang = vault.getMnemonicLanguage(address)
            Assertions.assertThat(lang).isEqualTo("english")

            clearRecord()

            val derivedMnemonic = vault.getMnemonic(address, password)
            Assertions.assertThat(derivedMnemonic).isEqualTo(mnemonic)
            verify(exactly = 1) { password.wipe() }
            verify(exactly = 3) { any<ByteArray>().wipe() }
            clearRecord()

            vault.importMnemonic(address.lowercase(), "a".toByteArray(), "b".toByteArray())
            verify(exactly = 2) { any<ByteArray>().wipe() }
            Assertions
                .assertThat(vault.getPrivateKey(address.lowercase(), password))
                .isEqualTo(privateKey)
            Assertions.assertThat(vault.getMnemonic(address.lowercase(), password)).isEqualTo(mnemonic)
            clearRecord()

            val wrongPassword = "1".toByteArray()
            val ex =
                assertFailsWith<IllegalArgumentException> {
                    vault.getPrivateKey(address, wrongPassword)
                }
            assert(ex.message?.contains("Password is wrong") == true)
            verify(exactly = 1) { wrongPassword.wipe() }
            clearRecord()

            val ex1 =
                assertFailsWith<IllegalArgumentException> {
                    vault.getMnemonic(address, wrongPassword)
                }
            assert(ex1.message?.contains("Password is wrong") == true)
            verify(exactly = 1) { wrongPassword.wipe() }
        }

    @Test
    fun test_02_1_importChineseMnemonic() =
        runTest {
            val chineseMnemonic = "贯 致 拌 龄 片 题 桑 耗 及 同 巨 级".toByteArray()
            val privateKey =
                "00CBB12FC77B8CFCE7ECB30428E4C2095D317CB285F143C147305C7580DE067367".toByteArray()
            val address = "jN2NEAiZpNYHYbFUdZbkCpEcxDTWBJ6AvA".uppercase()

            vault.importMnemonic(
                address = address,
                mnemonic = chineseMnemonic,
                privateKey = privateKey,
                pathPrefix = "m/44'/315'/0'/0/0",
                language = "chinese_simplified",
            )
            verify(exactly = 4) { any<ByteArray>().wipe() }
            clearRecord()

            val password = "123456789ab@][".toByteArray()

            val derivedPrivateKey = vault.getPrivateKey(address, password)
            Assertions.assertThat(derivedPrivateKey).isEqualTo(privateKey)
            clearRecord()

            val derivedMnemonic = vault.getMnemonic(address, password)
            Assertions.assertThat(derivedMnemonic).isEqualTo(chineseMnemonic)
            clearRecord()

            vault.importMnemonic(
                address = address.lowercase(),
                mnemonic = "测试".toByteArray(),
                privateKey = "test".toByteArray(),
                language = "chinese_simplified",
            )
            verify(exactly = 2) { any<ByteArray>().wipe() }
            Assertions
                .assertThat(vault.getMnemonic(address.lowercase(), password))
                .isEqualTo(chineseMnemonic)
        }

    @Test
    fun test_02_2_importChineseMnemonicDeriveSubAccount() =
        runTest {
            val chineseMnemonic = "贯 致 拌 龄 片 题 桑 耗 及 同 巨 级".toByteArray()
            val privateKey1 =
                "00403D510E3864CAA16F00BE92782F130B3F4215369C281B963682E268BC0DF309".toByteArray()
            val address1 = "0xed789a614c3844f4f67d333608530d62303c97c6".uppercase()

            vault.importMnemonic(
                address = address1,
                mnemonic = chineseMnemonic,
                privateKey = privateKey1,
                pathPrefix = "m/44'/60'/0'/0/0",
                language = "chinese_simplified",
            )
            verify(exactly = 4) { any<ByteArray>().wipe() }
            clearRecord()

            val lang = vault.getMnemonicLanguage(address1)

            Assertions.assertThat(lang).isEqualTo("chinese_simplified")

            val password = "123456789ab@][".toByteArray()

            val derivedPrivateKey = vault.getPrivateKey(address1, password)
            Assertions.assertThat(derivedPrivateKey).isEqualTo(privateKey1)

            val derivedMnemonic = vault.getMnemonic(address1, password)
            Assertions.assertThat(derivedMnemonic).isEqualTo(chineseMnemonic)
        }

    @Test
    fun test_03_importPrivateKey() =
        runTest {
            val privateKey =
                "000E92D1F81827F19D1D1EF46AE4608DD5F5AD658ED973BABE631D279BFC4B0FF3"
            val address = "jHbAZMCmdN6865dfFWMp6Gi8zQEnEePFot".uppercase()
            vault.importPrivateKey(address, privateKey.toByteArray())
            verify(exactly = 2) { any<ByteArray>().wipe() }
            clearRecord()

            val password = "123456789ab@][".toByteArray()
            val derivedPrivateKey = vault.getPrivateKey(address, password)
            Assertions.assertThat(derivedPrivateKey).isEqualTo(privateKey.toByteArray())
            verify(exactly = 1) { password.wipe() }
            verify(exactly = 3) { any<ByteArray>().wipe() }
            clearRecord()

            val ex =
                assertFailsWith<IllegalArgumentException> {
                    vault.getMnemonic(address, password)
                }
            assert(ex.message?.contains("Mnemonic is not exist") == true)
            verify(exactly = 1) { password.wipe() }
            clearRecord()

            val ex1 =
                assertFailsWith<IllegalArgumentException> {
                    vault.getPrivateKey(address.substring(1), password)
                }
            assert(ex1.message?.contains("Private key is not exist") == true)
            verify(exactly = 1) { password.wipe() }

            clearRecord()

            val address1 = "0x2995c1376a852e4040caf9dbae2c765e24c37a15"
            val privateKey1 = "ca6dbabef201dce8458f29b2290fef4cb80df3e16fef96347c3c250a883e4486"
            val privateKey2 = "8fef3bc906ea19f0348cb44bca851f5459b61e32c5cae445220e2f7066db36d8"
            val address2 = "0x5edccedfe9952f5b828937b325bd1f132aa09f60"
            val keys = mutableListOf<VaultPrivateKeyImport>()
            keys.add(VaultPrivateKeyImport(address1, privateKey1.toByteArray()))
            keys.add(VaultPrivateKeyImport(address1.uppercase(getDefault()), privateKey1.toByteArray()))
            keys.add(VaultPrivateKeyImport(address, privateKey.toByteArray()))
            keys.add(VaultPrivateKeyImport(address2, privateKey2.toByteArray()))

            vault.importPrivateKeys(keys)

            val size = vault.listAccounts().size
            assert(size == 6)
            assert(vault.addressInKeys(address1))
            assert(vault.addressInKeys(address2))
            verify(exactly = 5) { any<ByteArray>().wipe() }
            Assertions.assertThat(vault.getPrivateKey(address1, password)).isEqualTo(privateKey1.toByteArray())
            Assertions.assertThat(vault.getPrivateKey(address2, password)).isEqualTo(privateKey2.toByteArray())

            vault.removeAddress(address1, password)
            vault.removeAddress(address2, password)
        }

    @Test
    fun test_04_importSecret() =
        runTest {
            val privateKey =
                "c626df52d7e76721aaae04cf5ce188e53f73369afc8767b1889e2b0cbd599766".toByteArray()
            val address = "jfM4jTun3Tts29qCnKDoubtWQ3pvapiUbiU".uppercase()
            val secret = "ss6wQ9MMxHwwuzWJXEep5Xc2cfDKj".toByteArray()
            vault.importSecret(address, privateKey, secret)
            verify(exactly = 4) { any<ByteArray>().wipe() }
            clearRecord()

            val password = "123456789ab@][".toByteArray()
            val derivedPrivateKey = vault.getPrivateKey(address, password)
            Assertions.assertThat(derivedPrivateKey).isEqualTo(privateKey)
            verify(exactly = 1) { password.wipe() }
            verify(exactly = 3) { any<ByteArray>().wipe() }
            clearRecord()

            val derivedMnemonic = vault.getSecret(address, password)
            Assertions.assertThat(derivedMnemonic).isEqualTo(secret)
            verify(exactly = 1) { password.wipe() }
            verify(exactly = 3) { any<ByteArray>().wipe() }
            clearRecord()

            vault.importSecret(address.lowercase(), "a".toByteArray(), "b".toByteArray())
            verify(exactly = 2) { any<ByteArray>().wipe() }
            Assertions
                .assertThat(vault.getPrivateKey(address.lowercase(), password))
                .isEqualTo(privateKey)
            Assertions.assertThat(vault.getSecret(address.lowercase(), password)).isEqualTo(secret)
            clearRecord()

            val wrongPassword = "1".toByteArray()

            val ex1 =
                assertFailsWith<IllegalArgumentException> {
                    vault.getSecret(address, wrongPassword)
                }
            assert(ex1.message?.contains("Password is wrong") == true)
            verify(exactly = 1) { wrongPassword.wipe() }
        }

    @Test
    fun test_05_changePassword() =
        runTest {
            val oldPassword = "123456789ab@][".toByteArray()
            val newPassword = "1234".toByteArray()

            val mnemonic =
                "evolve paddle gun glance swap clarify shoe youth sweet air change chunk".toByteArray()
            val privateKey =
                "48EF9848FB097FFD086E38B9EF54606E17CC77FBC89B158E270B8D0B13A45417".toByteArray()
            val address = "0x6db849ed4ce8fe95044bffbfe4d291af34b4445d".uppercase()

            val privateKey1 =
                "000E92D1F81827F19D1D1EF46AE4608DD5F5AD658ED973BABE631D279BFC4B0FF3".toByteArray()
            val address1 = "jHbAZMCmdN6865dfFWMp6Gi8zQEnEePFot".uppercase()

            val address2 = "jfM4jTun3Tts29qCnKDoubtWQ3pvapiUbiU".uppercase()
            val secret = "ss6wQ9MMxHwwuzWJXEep5Xc2cfDKj".toByteArray()

            val chineseMnemonic = "贯 致 拌 龄 片 题 桑 耗 及 同 巨 级".toByteArray()
            val chineseAddress = "jN2NEAiZpNYHYbFUdZbkCpEcxDTWBJ6AvA".uppercase()
            val chineseAddress1 = "0xed789a614c3844f4f67d333608530d62303c97c6".uppercase()

            vault.changePassword(oldPassword, newPassword)
            verify(exactly = 1) { oldPassword.wipe() }
            verify(exactly = 1) { newPassword.wipe() }

            Assertions
                .assertThat(vault.getPrivateKey(address.lowercase(), newPassword))
                .isEqualTo(privateKey)
            Assertions
                .assertThat(vault.getMnemonic(address.lowercase(), newPassword))
                .isEqualTo(mnemonic)
            Assertions
                .assertThat(vault.getPrivateKey(address1.lowercase(), newPassword))
                .isEqualTo(privateKey1)
            Assertions
                .assertThat(vault.getSecret(address2.lowercase(), newPassword))
                .isEqualTo(secret)

            Assertions
                .assertThat(vault.getMnemonic(chineseAddress.lowercase(), newPassword))
                .isEqualTo(chineseMnemonic)
            Assertions
                .assertThat(vault.getMnemonic(chineseAddress1.lowercase(), newPassword))
                .isEqualTo(chineseMnemonic)
            clearRecord()

            val ex =
                assertFailsWith<IllegalArgumentException> {
                    vault.changePassword(oldPassword, newPassword)
                }
            assert(ex.message?.contains("Password is wrong") == true)
            verify(exactly = 1) { oldPassword.wipe() }
            verify(exactly = 1) { newPassword.wipe() }
            verify(exactly = 3) { any<ByteArray>().wipe() }
        }

    @Test
    fun test_06_removeAddress() =
        runTest {
            val password = "1234".toByteArray()

            val address = "0x6db849ed4ce8fe95044bffbfe4d291af34b4445d".uppercase()
            val address1 = "jHbAZMCmdN6865dfFWMp6Gi8zQEnEePFot".uppercase()
            val address2 = "jfM4jTun3Tts29qCnKDoubtWQ3pvapiUbiU".uppercase()
            val chineseAddress = "jN2NEAiZpNYHYbFUdZbkCpEcxDTWBJ6AvA".uppercase()
            val chineseAddress1 = "0xed789a614c3844f4f67d333608530d62303c97c6".uppercase()

            val wallets = vault.listAccounts()
            Assertions.assertThat(wallets).containsExactlyInAnyOrder(
                address,
                chineseAddress,
                chineseAddress1,
                address1,
                address2,
            )

            vault.removeAddress(address.lowercase(), password)
            Assertions
                .assertThat(vault.listAccounts())
                .containsExactlyInAnyOrder(
                    chineseAddress,
                    chineseAddress1,
                    address1,
                    address2,
                )
            verify(exactly = 1) { password.wipe() }
            verify(exactly = 2) { any<ByteArray>().wipe() }

            vault.removeAddress(chineseAddress.lowercase(), password)
            vault.removeAddress(chineseAddress1.lowercase(), password)
            vault.removeAddress(address1.lowercase(), password)
            vault.removeAddress(address2.lowercase(), password)
            Assertions.assertThat(vault.listAccounts().size).isEqualTo(0)

            val ex =
                assertFailsWith<IllegalArgumentException> {
                    vault.getMnemonic(address, password)
                }
            assert(ex.message?.contains("Mnemonic is not exist") == true)

            val ex1 =
                assertFailsWith<IllegalArgumentException> {
                    vault.getPrivateKey(address, password)
                }
            assert(ex1.message?.contains("Private key is not exist") == true)

            val ex3 =
                assertFailsWith<IllegalArgumentException> {
                    vault.getSecret(address2, password)
                }
            assert(ex3.message?.contains("Secret is not exist") == true)

            val ex4 =
                assertFailsWith<IllegalArgumentException> {
                    vault.getMnemonic(chineseAddress, password)
                }
            assert(ex4.message?.contains("Mnemonic is not exist") == true)

            val ex2 =
                assertFailsWith<IllegalArgumentException> {
                    vault.removeAddress(address1.lowercase(), "1".toByteArray())
                }
            assert(ex2.message?.contains("Password is wrong") == true)
        }

    @Test
    fun test_07_listAccounts_and_hasPassword() =
        runTest {
            val password = "vault-pass".toByteArray()
            val address = "0xabc123".uppercase()
            val privateKey = "deadbeef".toByteArray()

            vault.initializePassword(password)
            vault.importPrivateKey(address, privateKey)

            Assertions.assertThat(vault.hasPassword()).isTrue()
            Assertions.assertThat(vault.listAccounts()).contains(address)
        }

    @Test
    fun test_08_singleton_instance_is_cached() {
        val first = VaultRepository.get(appContext)
        val second = VaultRepository.get(appContext)

        Assertions.assertThat(first).isSameAs(second)
    }
}
