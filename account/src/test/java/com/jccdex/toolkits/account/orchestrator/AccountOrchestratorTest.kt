package com.jccdex.toolkits.account.orchestrator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jccdex.toolkits.account.AccountTestDatabase
import com.jccdex.toolkits.account.AccountTestFixtures
import com.jccdex.toolkits.account.storage.room.UnknownChainCodeException
import com.jccdex.toolkits.account.store.IAccountStore
import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.Path
import com.jccdex.toolkits.vault.VaultAuthLockedException
import com.jccdex.toolkits.vault.VaultRepository
import com.jccdex.toolkits.vault.model.VaultPrivateKeyImport
import com.jccdex.toolkits.wallet.model.GenerateHDWalletResult
import com.jccdex.toolkits.wallet.model.Keypair
import com.jccdex.toolkits.wallet.model.Mnemonic
import com.jccdex.toolkits.wallet.model.SubWallet
import com.jccdex.toolkits.wallet.model.TraditionalDeriveResult
import com.jccdex.toolkits.wallet.sdk.WalletSdk
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
class AccountOrchestratorTest {
    private lateinit var testDb: AccountTestDatabase
    private lateinit var vault: VaultRepository
    private lateinit var orchestrator: AccountOrchestrator

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = AccountTestDatabase.inMemory(context)
        vault = mockk(relaxed = true)
        orchestrator = AccountOrchestrator(testDb.store, vault)
    }

    @After
    fun tearDown() {
        unmockkAll()
        testDb.close()
    }

    @Test
    fun importSingleAccount_persistsAccountAndVault() =
        runTest {
            coEvery { vault.importPrivateKey(any(), any()) } returns Unit

            val derived =
                TraditionalDeriveResult(
                    address = "0x6a4f486f8f2e010c577afe8913886d977ba4b683",
                    keypair = Keypair(privateKey = "priv", publicKey = "pub")
                )

            val result =
                orchestrator.importSingleAccount(
                    derived = derived,
                    chain = ChainType.ETH,
                    name = "test-eth",
                    isHD = false,
                    parentId = null
                )

            assertThat(result).isInstanceOf(AccountOperationResult.Success::class.java)
            val accountId = (result as AccountOperationResult.Success).value
            assertThat(testDb.store.findById(accountId)?.name).isEqualTo("test-eth")
            coVerify { vault.importPrivateKey(derived.address, any()) }
        }

    @Test
    fun importSingleAccount_returnsErrorWhenAddressExists() =
        runTest {
            coEvery { vault.importPrivateKey(any(), any()) } returns Unit
            val address = "0x6a4f486f8f2e010c577afe8913886d977ba4b683"
            val derived =
                TraditionalDeriveResult(
                    address = address,
                    keypair = Keypair(privateKey = "priv", publicKey = "pub")
                )
            orchestrator.importSingleAccount(derived, ChainType.ETH, "a", false, null)
            val second =
                orchestrator.importSingleAccount(
                    TraditionalDeriveResult(address, Keypair("p2", "pub2")),
                    ChainType.ETH,
                    "b",
                    false,
                    null
                )
            assertThat(second).isEqualTo(AccountOperationResult.Error(AccountOperationError.AddressAlreadyExists))
        }

    @Test
    fun importSingleAccount_withMnemonic_importsVaultMnemonic() =
        runTest {
            coEvery { vault.importMnemonic(any(), any(), any(), any(), any()) } returns Unit

            val derived =
                TraditionalDeriveResult(
                    address = "0xabc",
                    keypair = Keypair("priv", "pub"),
                    mnemonic =
                        Mnemonic(
                            "opinion anger hello tool program mind bundle front water elite increase exotic",
                            "english"
                        ),
                    path = Path(chain = ChainType.ETH.bip44Code)
                )

            val result = orchestrator.importSingleAccount(derived, ChainType.ETH, "mnemonic", false, null)

            assertThat(result).isInstanceOf(AccountOperationResult.Success::class.java)
            coVerify { vault.importMnemonic("0xabc", any(), any(), any(), "english") }
        }

    @Test
    fun importSingleAccount_withSecret_importsVaultSecret() =
        runTest {
            coEvery { vault.importSecret(any(), any(), any()) } returns Unit

            val derived =
                TraditionalDeriveResult(
                    address = "0xsecret",
                    keypair = Keypair("priv", "pub"),
                    secret = "top-secret"
                )

            val result = orchestrator.importSingleAccount(derived, ChainType.ETH, "secret", false, null)

            assertThat(result).isInstanceOf(AccountOperationResult.Success::class.java)
            coVerify { vault.importSecret("0xsecret", any(), any()) }
        }

    @Test
    fun importHdWallet_requiresPasswordWhenVaultEmpty() =
        runTest {
            coEvery { vault.hasPassword() } returns false

            val hd =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic words here for test only twelve",
                    address = "jRoot",
                    language = "english",
                    keypair = Keypair("priv", "pub"),
                    accounts = emptyList()
                )

            val result = orchestrator.importHdWallet(hd, "wallet", password = null)

            assertThat(result).isEqualTo(AccountOperationResult.Error(AccountOperationError.PasswordRequired))
        }

    @Test
    fun importHdWallet_initializesPasswordWhenVaultEmpty() =
        runTest {
            coEvery { vault.hasPassword() } returns false
            coEvery { vault.initializePassword(any()) } returns Unit
            coEvery { vault.importMnemonic(any(), any(), any(), any(), any()) } returns Unit
            coEvery { vault.importPrivateKeys(any()) } returns Unit

            val hd =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic words here for test only twelve",
                    address = "jRootInit",
                    language = "english",
                    keypair = Keypair("priv", "pub")
                )

            val result = orchestrator.importHdWallet(hd, "wallet", "pass".toByteArray())

            assertThat(result).isInstanceOf(AccountOperationResult.Success::class.java)
            coVerify { vault.initializePassword(any()) }
        }

    @Test
    fun importHdWallet_returnsErrorWhenRootExists() =
        runTest {
            coEvery { vault.hasPassword() } returns true
            coEvery { vault.importMnemonic(any(), any(), any(), any(), any()) } returns Unit
            coEvery { vault.importPrivateKeys(any()) } returns Unit

            val root = AccountTestFixtures.hdRoot(address = "jRoot")
            testDb.store.addAccount(root)

            val hd =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic words here for test only twelve",
                    address = "jRoot",
                    language = "english",
                    keypair = Keypair("priv", "pub")
                )

            val result = orchestrator.importHdWallet(hd, "wallet", "pass".toByteArray())

            assertThat(result).isEqualTo(AccountOperationResult.Error(AccountOperationError.AccountAlreadyExists))
        }

    @Test
    fun importHdWallet_clearExisting_doesNotClearWhenRootExists() =
        runTest {
            val root = AccountTestFixtures.hdRoot(address = "jRoot")
            testDb.store.addAccount(root)

            val hd =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic words here for test only twelve",
                    address = "jRoot",
                    language = "english",
                    keypair = Keypair("priv", "pub")
                )

            val result =
                orchestrator.importHdWallet(
                    hd,
                    "wallet",
                    "new-pass".toByteArray(),
                    clearExisting = true,
                    clearExistingPassword = "old-pass".toByteArray()
                )

            assertThat(result).isEqualTo(AccountOperationResult.Error(AccountOperationError.AccountAlreadyExists))
            coVerify(exactly = 0) { vault.clearAllData(any()) }
            assertThat(testDb.store.findRootAccountByAddress("jRoot")).isNotNull
        }

    @Test
    fun importHdWallet_persistsRootAndChildren() =
        runTest {
            coEvery { vault.hasPassword() } returns true
            coEvery { vault.importMnemonic(any(), any(), any(), any(), any()) } returns Unit
            coEvery { vault.importPrivateKeys(any()) } returns Unit

            val ethSub =
                SubWallet(
                    chain = ChainType.ETH.bip44Code,
                    address = "0xethchild",
                    path = Path(ChainType.ETH.bip44Code, index = 0),
                    keypair = Keypair("pk-eth", "pub-eth")
                )
            val hd =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic words here for test only twelve",
                    address = "jRootNew",
                    language = "english",
                    keypair = Keypair("root-priv", "root-pub"),
                    accounts = listOf(ethSub)
                )

            val result = orchestrator.importHdWallet(hd, "My HD", "pass".toByteArray())

            assertThat(result).isInstanceOf(AccountOperationResult.Success::class.java)
            val success = result as AccountOperationResult.Success
            assertThat(success.value.children).hasSize(1)
            assertThat(success.value.children[0].chain).isEqualTo(ChainType.ETH)
            assertThat(testDb.store.findRootAccountByAddress("jRootNew")).isNotNull
            assertThat(testDb.store.findByAddress("0xethchild", ChainType.ETH)).isNotNull
            coVerify { vault.importMnemonic("jRootNew", any(), any(), "m/44'/0'/0'/0/0", "english") }
            coVerify { vault.importPrivateKeys(any()) }
        }

    @Test
    fun importHdWallet_skipsDuplicateChildren() =
        runTest {
            coEvery { vault.hasPassword() } returns true
            coEvery { vault.importMnemonic(any(), any(), any(), any(), any()) } returns Unit
            coEvery { vault.importPrivateKeys(any()) } returns Unit
            val importedKeys = slot<MutableList<VaultPrivateKeyImport>>()

            val existingSub =
                SubWallet(
                    chain = ChainType.ETH.bip44Code,
                    address = "0xdup",
                    path = Path(ChainType.ETH.bip44Code, index = 0),
                    keypair = Keypair("pk-dup", "pub-dup")
                )
            val hd =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic words here for test only twelve",
                    address = "jRootDup",
                    language = "english",
                    keypair = Keypair("root-priv", "root-pub"),
                    accounts = listOf(existingSub)
                )

            testDb.store.addAccount(
                AccountTestFixtures.hdSub(
                    id = "existing-id",
                    parentId = "other-root",
                    address = "0xdup"
                )
            )

            val result = orchestrator.importHdWallet(hd, "My HD", "pass".toByteArray())

            assertThat(result).isInstanceOf(AccountOperationResult.Success::class.java)
            assertThat(testDb.store.findByAddress("0xdup", ChainType.ETH)).isNotNull
            coVerify { vault.importPrivateKeys(capture(importedKeys)) }
            // M-22A: dedup precedes keys assembly — the repeated child's key is NOT imported.
            assertThat(importedKeys.captured).hasSize(0)
        }

    @Test
    fun importHdWallet_clearExisting_wipesStoreAndVault() =
        runTest {
            coEvery { vault.hasPassword() } returns true andThen false andThen false
            coEvery { vault.clearAllData(any()) } returns mockk(relaxed = true)
            coEvery { vault.importMnemonic(any(), any(), any(), any(), any()) } returns Unit
            coEvery { vault.importPrivateKeys(any()) } returns Unit

            testDb.store.addAccount(AccountTestFixtures.traditional(id = "old"))

            val hd =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic words here for test only twelve",
                    address = "jFresh",
                    language = "english",
                    keypair = Keypair("priv", "pub")
                )

            orchestrator.importHdWallet(
                hd,
                "fresh",
                "new-pass".toByteArray(),
                clearExisting = true,
                clearExistingPassword = "old-pass".toByteArray()
            )

            coVerify { vault.clearAllData(match { it.contentEquals("old-pass".toByteArray()) }) }
            assertThat(testDb.store.accounts.first()).hasSize(1)
            assertThat(testDb.store.findRootAccountByAddress("jFresh")).isNotNull
        }

    @Test
    fun importHdWallet_clearExisting_requiresCurrentPasswordWhenVaultHasPassword() =
        runTest {
            coEvery { vault.hasPassword() } returns true
            testDb.store.addAccount(AccountTestFixtures.traditional(id = "old"))

            val hd =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic words here for test only twelve",
                    address = "jFresh",
                    language = "english",
                    keypair = Keypair("priv", "pub")
                )

            val result =
                orchestrator.importHdWallet(
                    hd,
                    "fresh",
                    "new-pass".toByteArray(),
                    clearExisting = true
                )

            assertThat(result).isEqualTo(
                AccountOperationResult.Error(AccountOperationError.PasswordRequiredForClear)
            )
            coVerify(exactly = 0) { vault.clearAllData(any()) }
            assertThat(testDb.store.findById("old")).isNotNull
        }

    @Test
    fun importHdWallet_clearExisting_rejectsWrongCurrentPassword() =
        runTest {
            coEvery { vault.hasPassword() } returns true
            coEvery { vault.clearAllData(any()) } throws IllegalArgumentException("Password is wrong")

            testDb.store.addAccount(AccountTestFixtures.traditional(id = "old"))

            val hd =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic words here for test only twelve",
                    address = "jFresh",
                    language = "english",
                    keypair = Keypair("priv", "pub")
                )

            val result =
                orchestrator.importHdWallet(
                    hd,
                    "fresh",
                    "new-pass".toByteArray(),
                    clearExisting = true,
                    clearExistingPassword = "wrong".toByteArray()
                )

            assertThat(result).isInstanceOf(AccountOperationResult.Error::class.java)
            assertThat((result as AccountOperationResult.Error).error)
                .isInstanceOf(AccountOperationError.WrongPassword::class.java)
            assertThat(testDb.store.findById("old")).isNotNull
        }

    @Test
    fun importSubAccount_returnsRootNotFound() =
        runTest {
            val derived =
                DerivedSubAccount(
                    address = "0xsub",
                    chain = ChainType.ETH,
                    path = Path(ChainType.ETH.bip44Code, index = 1),
                    rootAccountId = "missing-root",
                    publicKey = "pub"
                )

            val result = orchestrator.importSubAccount(derived, "sub")

            assertThat(result).isEqualTo(AccountOperationResult.Error(AccountOperationError.RootAccountNotFound))
        }

    @Test
    fun importSubAccount_persistsHdChild() =
        runTest {
            coEvery { vault.importPrivateKey(any(), any()) } returns Unit
            val root = AccountTestFixtures.hdRoot(id = "root-id", address = "jRoot")
            testDb.store.addAccount(root)

            val derived =
                DerivedSubAccount(
                    address = "0xnewsub",
                    chain = ChainType.ETH,
                    path = Path(ChainType.ETH.bip44Code, index = 1),
                    rootAccountId = root.id,
                    publicKey = "pub"
                )

            val result = orchestrator.importSubAccount(derived, "eth-sub")

            assertThat(result).isInstanceOf(AccountOperationResult.Success::class.java)
            val id = (result as AccountOperationResult.Success).value
            val saved = testDb.store.findById(id)
            assertThat(saved?.parentId).isEqualTo(root.id)
            assertThat(saved?.isSubHD()).isTrue()
        }

    @Test
    fun importSubAccount_doesNotPersistEmptyKeyToVault() =
        runTest {
            val root = AccountTestFixtures.hdRoot(id = "root-id2", address = "jRoot2")
            testDb.store.addAccount(root)

            val derived =
                DerivedSubAccount(
                    address = "0xsub-novault",
                    chain = ChainType.ETH,
                    path = Path(ChainType.ETH.bip44Code, index = 1),
                    rootAccountId = root.id,
                    publicKey = "pub"
                )

            val result = orchestrator.importSubAccount(derived, "sub")

            assertThat(result).isInstanceOf(AccountOperationResult.Success::class.java)
            val id = (result as AccountOperationResult.Success).value
            assertThat(testDb.store.findById(id)).isNotNull
            // Sub-account carries no real private key: it must NOT be written to vault, otherwise
            // the empty key would permanently block a later real-key import for this address.
            coVerify(exactly = 0) { vault.importPrivateKey(any(), any()) }
        }

    @Test
    fun removeAccount_wrongPassword() =
        runTest {
            val account = AccountTestFixtures.traditional(id = "rm-id")
            testDb.store.addAccount(account)
            coEvery { vault.verifyPassword(any()) } returns false

            val result = orchestrator.removeAccount(account.id, "wrong".toByteArray())

            assertThat(result).isEqualTo(AccountOperationResult.Error(AccountOperationError.WrongPassword()))
            assertThat(testDb.store.findById(account.id)).isNotNull
        }

    @Test
    fun removeAccount_returnsSuccessWhenAccountMissing() =
        runTest {
            coEvery { vault.verifyPassword(any()) } returns true

            val result = orchestrator.removeAccount("missing-id", "pass".toByteArray())

            assertThat(result).isEqualTo(AccountOperationResult.Success(Unit))
            coVerify(exactly = 0) { vault.removeAddress(any(), any()) }
        }

    @Test
    fun removeAccount_removesVaultWhenLastSameAddress() =
        runTest {
            val account = AccountTestFixtures.traditional(id = "rm-id", address = "0xonly")
            testDb.store.addAccount(account)
            val password = "pass".toByteArray()
            coEvery { vault.verifyPassword(any()) } returns true
            coEvery { vault.removeAddress(any(), any()) } returns mockk(relaxed = true)

            val result = orchestrator.removeAccount(account.id, password)

            assertThat(result).isInstanceOf(AccountOperationResult.Success::class.java)
            assertThat(testDb.store.findById(account.id)).isNull()
            coVerify { vault.removeAddress("0xonly", password) }
        }

    @Test
    fun removeAccount_keepsVaultWhenSharedAddress() =
        runTest {
            val addr = "0xshared"
            val eth = AccountTestFixtures.traditional(id = "eth-id", address = addr, chain = ChainType.ETH)
            val bsc = AccountTestFixtures.traditional(id = "bsc-id", address = addr, chain = ChainType.BSC)
            testDb.store.addAccount(eth)
            testDb.store.addAccount(bsc)
            val password = "pass".toByteArray()
            coEvery { vault.verifyPassword(any()) } returns true

            orchestrator.removeAccount(eth.id, password)

            assertThat(testDb.store.findById(eth.id)).isNull()
            assertThat(testDb.store.findById(bsc.id)).isNotNull
            coVerify(exactly = 0) { vault.removeAddress(any(), any()) }
        }

    @Test
    fun deriveSubAccount_autoIndexAndBumpOnCollision() =
        runTest {
            mockkObject(WalletSdk)
            val root = AccountTestFixtures.hdRoot(id = "root-id", address = "jRootDerive")
            val existing =
                AccountTestFixtures.hdSub(
                    id = "exist",
                    parentId = root.id,
                    address = "0xexisting",
                    index = 0
                )
            testDb.store.addAccount(root)
            testDb.store.addAccount(existing)
            coEvery { vault.getMnemonicUnlocked("jRootDerive") } returns "test mnemonic words".toByteArray()

            coEvery {
                WalletSdk.deriveChild(mnemonic = any(), chain = ChainType.ETH.bip44Code, index = 1)
            } returns
                SubWallet(
                    ChainType.ETH.bip44Code,
                    "0xexisting",
                    Path(ChainType.ETH.bip44Code, index = 1),
                    Keypair("pk1", "pub1")
                )
            coEvery {
                WalletSdk.deriveChild(mnemonic = any(), chain = ChainType.ETH.bip44Code, index = 2)
            } returns
                SubWallet(
                    ChainType.ETH.bip44Code,
                    "0xnewderived",
                    Path(ChainType.ETH.bip44Code, index = 2),
                    Keypair("pk2", "pub2")
                )

            val result = orchestrator.deriveSubAccount(ChainType.ETH, root.id)

            assertThat(result).isInstanceOf(AccountOperationResult.Success::class.java)
            val derived = (result as AccountOperationResult.Success).value
            assertThat(derived.address).isEqualTo("0xnewderived")
            assertThat(derived.path.index).isEqualTo(2)
        }

    @Test
    fun deriveSubAccount_usesSpecifiedIndex() =
        runTest {
            mockkObject(WalletSdk)
            val root = AccountTestFixtures.hdRoot(id = "root-id", address = "jRootIdx")
            testDb.store.addAccount(root)
            coEvery { vault.getMnemonicUnlocked("jRootIdx") } returns "mnemonic".toByteArray()
            coEvery {
                WalletSdk.deriveChild(mnemonic = any(), chain = ChainType.ETH.bip44Code, index = 3)
            } returns
                SubWallet(
                    ChainType.ETH.bip44Code,
                    "0xat3",
                    Path(ChainType.ETH.bip44Code, index = 3),
                    Keypair("pk", "pub")
                )

            val result = orchestrator.deriveSubAccount(ChainType.ETH, root.id, index = 3)

            assertThat(result).isInstanceOf(AccountOperationResult.Success::class.java)
            coVerify(exactly = 1) {
                WalletSdk.deriveChild(mnemonic = any(), chain = ChainType.ETH.bip44Code, index = 3)
            }
        }

    @Test
    fun deriveSubAccount_rootNotFound() =
        runTest {
            val result = orchestrator.deriveSubAccount(ChainType.ETH, "missing")

            assertThat(result).isEqualTo(AccountOperationResult.Error(AccountOperationError.RootAccountNotFound))
        }

    @Test
    fun deriveSubAccount_returnsFailureWhenWalletSdkThrows() =
        runTest {
            mockkObject(WalletSdk)
            val root = AccountTestFixtures.hdRoot(id = "root-id", address = "jRootFail")
            testDb.store.addAccount(root)
            coEvery { vault.getMnemonicUnlocked("jRootFail") } returns "mnemonic".toByteArray()
            coEvery {
                WalletSdk.deriveChild(mnemonic = any(), chain = ChainType.ETH.bip44Code, index = 1)
            } throws IllegalStateException("boom")

            val result = orchestrator.deriveSubAccount(ChainType.ETH, root.id)

            assertThat(result).isInstanceOf(AccountOperationResult.Error::class.java)
            val error = (result as AccountOperationResult.Error).error
            assertThat(error).isInstanceOf(AccountOperationError.Failure::class.java)
        }

    @Test
    fun clearWalletData_clearsStoreAndVault() =
        runTest {
            coEvery { vault.hasPassword() } returns true
            coEvery { vault.clearAllData(any()) } returns mockk(relaxed = true)
            testDb.store.addAccount(AccountTestFixtures.traditional())

            val result = orchestrator.clearWalletData("pass".toByteArray())

            assertThat(result).isInstanceOf(AccountOperationResult.Success::class.java)
            coVerify { vault.clearAllData(match { it.contentEquals("pass".toByteArray()) }) }
            assertThat(testDb.store.accounts.first()).isEmpty()
        }

    @Test
    fun clearWalletData_clearsWhenVaultHasNoPassword() =
        runTest {
            coEvery { vault.hasPassword() } returns false
            coEvery { vault.clearAllData(null) } returns mockk(relaxed = true)
            testDb.store.addAccount(AccountTestFixtures.traditional())

            val result = orchestrator.clearWalletData("ignored".toByteArray())

            assertThat(result).isInstanceOf(AccountOperationResult.Success::class.java)
            coVerify { vault.clearAllData(null) }
            assertThat(testDb.store.accounts.first()).isEmpty()
        }

    @Test
    fun clearWalletData_rejectsWrongPassword() =
        runTest {
            coEvery { vault.hasPassword() } returns true
            coEvery { vault.clearAllData(any()) } throws IllegalArgumentException("Password is wrong")
            testDb.store.addAccount(AccountTestFixtures.traditional(id = "keep"))

            val result = orchestrator.clearWalletData("wrong".toByteArray())

            assertThat(result).isInstanceOf(AccountOperationResult.Error::class.java)
            assertThat((result as AccountOperationResult.Error).error)
                .isInstanceOf(AccountOperationError.WrongPassword::class.java)
            assertThat(testDb.store.findById("keep")).isNotNull
        }

    // ── M-18A: full duplicate check by address (+chain) across account types ──

    @Test
    fun importSingleAccount_rejectsWhenHdRootWithSameAddressExists() =
        runTest {
            val address = "0xSharedAddr"
            testDb.store.addAccount(AccountTestFixtures.hdRoot(address = address))

            val derived =
                TraditionalDeriveResult(
                    address = address,
                    keypair = Keypair("priv", "pub")
                )
            val result =
                orchestrator.importSingleAccount(
                    derived = derived,
                    chain = ChainType.SWTC,
                    name = "trad",
                    isHD = false,
                    parentId = null
                )

            assertThat(result)
                .isEqualTo(AccountOperationResult.Error(AccountOperationError.AddressAlreadyExists))
        }

    @Test
    fun importHdWallet_rejectsWhenTraditionalAccountWithSameAddressExists() =
        runTest {
            val address = "0xSharedAddr"
            testDb.store.addAccount(
                AccountTestFixtures.traditional(address = address, chain = ChainType.SWTC)
            )

            val hd =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic words here for test only twelve",
                    address = address,
                    language = "english",
                    keypair = Keypair("priv", "pub")
                )
            val result = orchestrator.importHdWallet(hd, "wallet", "pass".toByteArray())

            assertThat(result)
                .isEqualTo(AccountOperationResult.Error(AccountOperationError.AccountAlreadyExists))
        }

    // ── M-21A: vault auth lock is surfaced as a typed domain error ──

    @Test
    fun runOperation_returnsVaultLockedWhenVaultAuthLocked() =
        runTest {
            coEvery { vault.hasPassword() } returns true
            coEvery { vault.clearAllData(any()) } throws VaultAuthLockedException(5000)

            val hd =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic words here for test only twelve",
                    address = "jFresh",
                    language = "english",
                    keypair = Keypair("priv", "pub")
                )
            val result =
                orchestrator.importHdWallet(
                    hd,
                    "wallet",
                    "pass".toByteArray(),
                    clearExisting = true,
                    clearExistingPassword = "oldpass".toByteArray()
                )

            assertThat(result)
                .isEqualTo(AccountOperationResult.Error(AccountOperationError.VaultLocked(5000)))
        }

    // ── M-13A: compensating rollback + orphan reconciliation ──

    @Test
    fun importHdWallet_compensatesVaultImportWhenStoreCommitFails() =
        runTest {
            val store = mockk<IAccountStore>(relaxed = true)
            val orch = AccountOrchestrator(store, vault)
            every { vault.isUnlocked } returns true
            coEvery { vault.hasPassword() } returns true
            coEvery { vault.importMnemonic(any(), any(), any(), any(), any()) } returns Unit
            coEvery { vault.importPrivateKeys(any()) } returns Unit
            coEvery { store.findByAddress(any(), any()) } returns null
            coEvery { store.findNonRootAccount(any(), any()) } returns null
            coEvery { store.addAccounts(any()) } throws IllegalStateException("store commit failed")

            val hd =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic words here for test only twelve",
                    address = "jRootComp",
                    language = "english",
                    keypair = Keypair("root-priv", "root-pub"),
                    accounts =
                        listOf(
                            SubWallet(
                                chain = ChainType.ETH.bip44Code,
                                address = "0xchild",
                                path = Path(ChainType.ETH.bip44Code, index = 0),
                                keypair = Keypair("child-priv", "child-pub")
                            )
                        )
                )

            val result = orch.importHdWallet(hd, "My HD", "pass".toByteArray())

            assertThat(result).isInstanceOf(AccountOperationResult.Error::class.java)
            // M-13A: unlocked-session rollback (password buffer may already be wiped).
            coVerify { vault.removeAddressUnlocked("jRootComp") }
            coVerify { vault.removeAddressUnlocked("0xchild") }
            coVerify(exactly = 0) { vault.removeAddress(any(), any()) }
        }

    @Test
    fun importHdWallet_compensatesWithUnlockedRollbackWhenPasswordNull() =
        runTest {
            // Additive HD import: vault already has a password, caller passes password=null.
            // Rollback must still run via the unlocked session.
            val store = mockk<IAccountStore>(relaxed = true)
            val orch = AccountOrchestrator(store, vault)
            every { vault.isUnlocked } returns true
            coEvery { vault.hasPassword() } returns true
            coEvery { vault.importMnemonic(any(), any(), any(), any(), any()) } returns Unit
            coEvery { vault.importPrivateKeys(any()) } returns Unit
            coEvery { store.findByAddress(any(), any()) } returns null
            coEvery { store.findNonRootAccount(any(), any()) } returns null
            coEvery { store.addAccounts(any()) } throws IllegalStateException("store commit failed")

            val hd =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic words here for test only twelve",
                    address = "jRootAdditive",
                    language = "english",
                    keypair = Keypair("root-priv", "root-pub"),
                    accounts = emptyList()
                )

            val result = orch.importHdWallet(hd, "My HD", password = null)

            assertThat(result).isInstanceOf(AccountOperationResult.Error::class.java)
            coVerify { vault.removeAddressUnlocked("jRootAdditive") }
            coVerify(exactly = 0) { vault.removeAddress(any(), any()) }
        }

    @Test
    fun importHdWallet_skipsRollbackWhenVaultLocked() =
        runTest {
            val store = mockk<IAccountStore>(relaxed = true)
            val orch = AccountOrchestrator(store, vault)
            every { vault.isUnlocked } returns false
            coEvery { vault.hasPassword() } returns true
            coEvery { vault.importMnemonic(any(), any(), any(), any(), any()) } returns Unit
            coEvery { vault.importPrivateKeys(any()) } returns Unit
            coEvery { store.findByAddress(any(), any()) } returns null
            coEvery { store.findNonRootAccount(any(), any()) } returns null
            coEvery { store.addAccounts(any()) } throws IllegalStateException("store commit failed")

            val hd =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic words here for test only twelve",
                    address = "jRootLocked",
                    language = "english",
                    keypair = Keypair("root-priv", "root-pub"),
                    accounts = emptyList()
                )

            val result = orch.importHdWallet(hd, "My HD", "pass".toByteArray())

            assertThat(result).isInstanceOf(AccountOperationResult.Error::class.java)
            coVerify(exactly = 0) { vault.removeAddressUnlocked(any()) }
            coVerify(exactly = 0) { vault.removeAddress(any(), any()) }
        }

    @Test
    fun importHdWallet_compensatesAfterInitializePasswordWipesCallerBuffer() =
        runTest {
            // New-wallet path: initializePassword wipes the caller's password array; rollback must
            // still succeed via the unlocked session (not removeAddress with a zeroed buffer).
            val store = mockk<IAccountStore>(relaxed = true)
            val orch = AccountOrchestrator(store, vault)
            val password = "pass".toByteArray()
            every { vault.isUnlocked } returns true
            coEvery { vault.hasPassword() } returns false
            coEvery { vault.initializePassword(any()) } answers {
                firstArg<ByteArray>().fill(0)
            }
            coEvery { vault.importMnemonic(any(), any(), any(), any(), any()) } returns Unit
            coEvery { vault.importPrivateKeys(any()) } returns Unit
            coEvery { store.findByAddress(any(), any()) } returns null
            coEvery { store.findNonRootAccount(any(), any()) } returns null
            coEvery { store.addAccounts(any()) } throws IllegalStateException("store commit failed")

            val hd =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic words here for test only twelve",
                    address = "jRootWipe",
                    language = "english",
                    keypair = Keypair("root-priv", "root-pub"),
                    accounts =
                        listOf(
                            SubWallet(
                                chain = ChainType.ETH.bip44Code,
                                address = "0xchildWipe",
                                path = Path(ChainType.ETH.bip44Code, index = 0),
                                keypair = Keypair("child-priv", "child-pub")
                            )
                        )
                )

            val result = orch.importHdWallet(hd, "My HD", password)

            assertThat(result).isInstanceOf(AccountOperationResult.Error::class.java)
            assertThat(password.all { it == 0.toByte() }).isTrue()
            coVerify { vault.removeAddressUnlocked("jRootWipe") }
            coVerify { vault.removeAddressUnlocked("0xchildWipe") }
            coVerify(exactly = 0) { vault.removeAddress(any(), any()) }
        }

    @Test
    fun listOrphanKeys_returnsVaultKeysWithoutStoreRecords() =
        runTest {
            testDb.store.addAccount(
                AccountTestFixtures.hdSub(
                    id = "existing",
                    parentId = "root",
                    address = "0xknown"
                )
            )
            coEvery { vault.listAccounts() } returns listOf("0xknown", "0xorphan")

            val orphans = orchestrator.listOrphanKeys()

            assertThat(orphans).containsExactly("0xorphan")
        }

    @Test
    fun listOrphanKeys_usesRawAddresses_evenWhenStoreHasUnknownChainRow() =
        runTest {
            val store = mockk<IAccountStore>(relaxed = true)
            val orch = AccountOrchestrator(store, vault)
            // A corrupt row makes toWalletAccount throw — listOrphanKeys must not collect it
            // (M-13A + M-15A: it compares raw addresses via listAllAddresses).
            every { store.accounts } returns flow { throw UnknownChainCodeException(-1L) }
            coEvery { store.listAllAddresses() } returns listOf("0xknown")
            coEvery { vault.listAccounts() } returns listOf("0xknown", "0xorphan")

            val orphans = orch.listOrphanKeys()

            assertThat(orphans).containsExactly("0xorphan")
        }

    // ── M-17A: clearWalletData must not wipe the caller's password array ──

    @Test
    fun clearWalletData_doesNotWipeCallerPasswordArray() =
        runTest {
            coEvery { vault.hasPassword() } returns true
            coEvery { vault.clearAllData(any()) } returns mockk(relaxed = true)
            val password = "secret".toByteArray()
            val captured = slot<ByteArray>()

            val result = orchestrator.clearWalletData(password)

            assertThat(result).isEqualTo(AccountOperationResult.Success(Unit))
            coVerify { vault.clearAllData(capture(captured)) }
            // M-17A: a copy is passed, so the caller's array is not the instance vault wipes.
            assertThat(captured.captured).isNotSameAs(password)
        }

    @Test
    fun importHdWallet_clearExisting_doesNotWipeClearExistingPasswordArray() =
        runTest {
            coEvery { vault.hasPassword() } returns true andThen false andThen false
            coEvery { vault.clearAllData(any()) } returns mockk(relaxed = true)
            coEvery { vault.importMnemonic(any(), any(), any(), any(), any()) } returns Unit
            coEvery { vault.importPrivateKeys(any()) } returns Unit

            val clearPassword = "old-pass".toByteArray()
            val captured = slot<ByteArray>()
            val hd =
                GenerateHDWalletResult(
                    mnemonic = "mnemonic words here for test only twelve",
                    address = "jNewAddress",
                    language = "english",
                    keypair = Keypair("priv", "pub")
                )

            val result =
                orchestrator.importHdWallet(
                    hd,
                    "wallet",
                    "new-pass".toByteArray(),
                    clearExisting = true,
                    clearExistingPassword = clearPassword
                )

            assertThat(result).isInstanceOf(AccountOperationResult.Success::class.java)
            coVerify { vault.clearAllData(capture(captured)) }
            assertThat(captured.captured).isNotSameAs(clearPassword)
            assertThat(captured.captured.contentEquals(clearPassword)).isTrue()
        }
}
