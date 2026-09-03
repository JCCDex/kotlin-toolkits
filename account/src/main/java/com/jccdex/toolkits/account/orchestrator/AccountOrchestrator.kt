package com.jccdex.toolkits.account.orchestrator

import com.jccdex.toolkits.account.store.IAccountStore
import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.Path
import com.jccdex.toolkits.core.model.WalletAccount
import com.jccdex.toolkits.core.security.wipe
import com.jccdex.toolkits.vault.VaultAuthLockedException
import com.jccdex.toolkits.vault.VaultRepository
import com.jccdex.toolkits.vault.model.VaultPrivateKeyImport
import com.jccdex.toolkits.wallet.model.GenerateHDWalletResult
import com.jccdex.toolkits.wallet.model.Keypair
import com.jccdex.toolkits.wallet.model.TraditionalDeriveResult
import com.jccdex.toolkits.wallet.sdk.WalletSdk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

class AccountOrchestrator(
    private val store: IAccountStore,
    private val vault: VaultRepository
) {
    private val mutex = Mutex()

    suspend fun importSingleAccount(
        derived: TraditionalDeriveResult,
        chain: ChainType,
        name: String,
        isHD: Boolean,
        parentId: String?
    ): AccountOperationResult<String> =
        runOperation {
            val address = derived.address
            // M-18A: full existence check — any account type (incl. HD root) with this address+chain.
            if (store.findByAddress(address, chain) != null) {
                return@runOperation AccountOperationResult.Error(AccountOperationError.AddressAlreadyExists)
            }

            persistVaultMaterial(derived)

            val walletAccount =
                WalletAccount(
                    address = address,
                    chain = chain,
                    name = name,
                    isHD = isHD,
                    parentId = parentId,
                    path = derived.path,
                    publicKey = derived.keypair.publicKey
                )
            store.addAccount(walletAccount)
            AccountOperationResult.Success(walletAccount.id)
        }

    /**
     * @param password Business / new password used to initialize vault when empty.
     *   Must be a **distinct** [ByteArray] from [clearExistingPassword]: vault verify/clear
     *   wipes the clear-password array in place (H-R5). Passing the same reference for both
     *   will leave [password] zeroed before [initializePassword].
     * @param clearExistingPassword **Current** vault password required when [clearExisting] and
     *   vault already has a password. Must not be confused with [password] (e.g. reset UI new password).
     *   Do not reuse the same [ByteArray] instance as [password].
     * @param clearExisting If true, irreversibly wipes the current vault and account table **before**
     *   importing. Only honored after the duplicate check passes; an already-imported root address is
     *   rejected with [AccountOperationError.AccountAlreadyExists] and no data is cleared.
     */
    suspend fun importHdWallet(
        hdResult: GenerateHDWalletResult,
        name: String,
        password: ByteArray?,
        clearExisting: Boolean = false,
        clearExistingPassword: ByteArray? = null
    ): AccountOperationResult<ImportHdWalletResult> =
        runOperation {
            // Dedupe must run before any clearing: with clearExisting=true the account table is
            // wiped below, so an already-imported root address would otherwise be silently re-imported
            // (and the existing wallet irreversibly destroyed) with no error reported.
            // M-18A: full existence check — the HD root is imported on SWTC, so catch any SWTC account
            // (traditional or HD root) already using this address.
            if (store.findByAddress(hdResult.address, ChainType.SWTC) != null) {
                return@runOperation AccountOperationResult.Error(AccountOperationError.AccountAlreadyExists)
            }

            if (clearExisting) {
                if (vault.hasPassword()) {
                    val pwd =
                        clearExistingPassword
                            ?: return@runOperation AccountOperationResult.Error(
                                AccountOperationError.PasswordRequiredForClear
                            )
                    try {
                        vault.clearAllData(pwd.copyOf())
                    } catch (_: IllegalArgumentException) {
                        return@runOperation AccountOperationResult.Error(AccountOperationError.WrongPassword())
                    }
                } else {
                    vault.clearAllData()
                }
                store.clearAllAccounts()
            }

            if (!vault.hasPassword()) {
                if (password == null) {
                    return@runOperation AccountOperationResult.Error(AccountOperationError.PasswordRequired)
                }
                vault.initializePassword(password)
            }

            val rootPath = Path(chain = 0, account = 0, change = 0, index = 0)

            vault.importMnemonic(
                address = hdResult.address,
                mnemonic = hdResult.mnemonic.toByteArray(),
                privateKey = hdResult.keypair.privateKey.toByteArray(),
                pathPrefix = rootPath.toString(),
                language = hdResult.language
            )

            val rootAccount =
                WalletAccount(
                    address = hdResult.address,
                    chain = ChainType.SWTC,
                    name = name,
                    isHD = true,
                    parentId = null,
                    path = rootPath,
                    publicKey = hdResult.keypair.publicKey
                )
            val accounts = mutableListOf(rootAccount)
            val childIds = mutableListOf<HdChildAccountId>()
            val keys = mutableListOf<VaultPrivateKeyImport>()

            try {
                for (sub in hdResult.accounts) {
                    val chainType = ChainType.fromBip44Code(sub.chain) ?: continue
                    // M-22A: dedup before assembling keys — do not import keys for repeated sub-accounts
                    // the code has already decided to skip (semantic consistency/hygiene).
                    if (store.findNonRootAccount(sub.address, chainType) != null) {
                        continue
                    }

                    keys.add(VaultPrivateKeyImport(sub.address, sub.keypair.privateKey.toByteArray()))

                    val child =
                        WalletAccount(
                            address = sub.address,
                            chain = chainType,
                            name = "${chainType.label}-HD",
                            isHD = true,
                            parentId = rootAccount.id,
                            path = sub.path,
                            publicKey = sub.keypair.publicKey
                        )
                    accounts.add(child)
                    childIds.add(HdChildAccountId(chainType, child.id))
                }

                vault.importPrivateKeys(keys)
                store.addAccounts(accounts)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // M-13A: compensating rollback — vault writes (root mnemonic/keys) may have succeeded
                // but the store commit (or a child dedup lookup) failed; remove the imported root and
                // child keys so they don't linger as orphans.
                // Prefer removeAddressUnlocked: initializePassword / importMnemonic leave the session
                // unlocked and wipe the caller's password buffer, so removeAddress(password) cannot
                // authenticate. Additive imports (password == null) also rely on the unlocked session.
                // If the vault is locked, rollback is skipped (orphan detectable via listOrphanKeys);
                // a process-kill inside this window remains the documented limitation.
                rollbackVaultImport(hdResult.address, keys)
                throw t
            }

            AccountOperationResult.Success(
                ImportHdWalletResult(
                    rootAccountId = rootAccount.id,
                    children = childIds
                )
            )
        }

    /**
     * M-13A: best-effort removal of the root mnemonic/keys written by a failed HD import.
     * Only the addresses imported in THIS call are removed (post-M-22A, [importedKeys] excludes
     * repeated children already present in the store); the M-18A root pre-check guarantees the root
     * was newly written here. Failures are swallowed — rollback is best-effort.
     */
    private suspend fun rollbackVaultImport(
        rootAddress: String,
        importedKeys: List<VaultPrivateKeyImport>
    ) {
        if (!vault.isUnlocked) return
        val addresses = importedKeys.map { it.address } + rootAddress
        addresses.forEach { address ->
            runCatching { vault.removeAddressUnlocked(address) }
        }
    }

    /**
     * M-13A: reconciliation — returns addresses that hold a vault key but have no store account
     * record (orphan keys left by a crash or partial write). Non-destructive; hosts can surface
     * these or clean them up.
     */
    suspend fun listOrphanKeys(): List<String> {
        // M-13A + M-15A: compare against RAW store addresses (no toWalletAccount mapping) so the
        // diagnostic stays usable even when the store holds an unknown-chain row.
        val storeAddresses = store.listAllAddresses().map { it.lowercase(Locale.ROOT) }.toSet()
        return vault.listAccounts().filter { it.lowercase(Locale.ROOT) !in storeAddresses }
    }

    /**
     * Imports a derived HD sub-account into the account store. No vault key is persisted: the
     * sub-account private key is meant to be derived from the root mnemonic at signing time
     * (not yet implemented — sub-account signing is currently unavailable). Writing an empty key
     * to vault would permanently lock the address, so vault persistence is skipped for it.
     */
    suspend fun importSubAccount(
        derived: DerivedSubAccount,
        name: String
    ): AccountOperationResult<String> {
        if (store.findById(derived.rootAccountId) == null) {
            return AccountOperationResult.Error(AccountOperationError.RootAccountNotFound)
        }
        return importSingleAccount(
            derived =
                TraditionalDeriveResult(
                    address = derived.address,
                    keypair =
                        Keypair(
                            privateKey = "",
                            publicKey = derived.publicKey
                        ),
                    path = derived.path
                ),
            chain = derived.chain,
            name = name,
            isHD = true,
            parentId = derived.rootAccountId
        )
    }

    /**
     * Removes [accountId] after verifying [password].
     *
     * Password is always checked first (M-14). If the account is already gone, returns
     * [AccountOperationResult.Success] (idempotent delete) rather than a not-found error.
     */
    suspend fun removeAccount(
        accountId: String,
        password: ByteArray
    ): AccountOperationResult<Unit> =
        runOperation {
            if (!vault.verifyPassword(password.copyOf())) {
                return@runOperation AccountOperationResult.Error(AccountOperationError.WrongPassword())
            }

            val account = store.findById(accountId) ?: return@runOperation AccountOperationResult.Success(Unit)

            val count = store.getSameAccountsCount(account.address)
            store.removeAccount(account.id)
            if (count == 1) {
                vault.removeAddress(account.address, password)
            }
            AccountOperationResult.Success(Unit)
        }

    suspend fun deriveSubAccount(
        chain: ChainType,
        rootAccountId: String,
        index: Int? = null
    ): AccountOperationResult<DerivedSubAccount> =
        mutex.withLock {
            var mnemonic: ByteArray? = null
            try {
                val rootAccount =
                    store.findById(rootAccountId)
                        ?: return@withLock AccountOperationResult.Error(AccountOperationError.RootAccountNotFound)

                mnemonic = vault.getMnemonicUnlocked(rootAccount.address)

                var deriveIndex = index ?: (store.getMaxIndexByChain(rootAccount.id, chain) + 1)
                var subWallet =
                    WalletSdk.deriveChild(
                        mnemonic = mnemonic.toString(Charsets.UTF_8),
                        chain = chain.bip44Code,
                        index = deriveIndex
                    )

                while (index == null && store.findNonRootAccount(subWallet.address, chain) != null) {
                    deriveIndex += 1
                    subWallet =
                        WalletSdk.deriveChild(
                            mnemonic = mnemonic.toString(Charsets.UTF_8),
                            chain = chain.bip44Code,
                            index = deriveIndex
                        )
                }

                vault.importPrivateKey(
                    address = subWallet.address,
                    privateKey = subWallet.keypair.privateKey.toByteArray()
                )

                AccountOperationResult.Success(
                    DerivedSubAccount(
                        address = subWallet.address,
                        chain = chain,
                        path = subWallet.path,
                        rootAccountId = rootAccount.id,
                        publicKey = subWallet.keypair.publicKey
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultAuthLockedException) {
                AccountOperationResult.Error(AccountOperationError.VaultLocked(e.remainingMs))
            } catch (e: Exception) {
                AccountOperationResult.Error(AccountOperationError.Failure(e))
            } finally {
                mnemonic?.wipe()
            }
        }

    /**
     * Clears vault and accounts.
     * When the vault already has a password, [password] must be the **current** vault password.
     * When the vault has **no** password yet, [password] is ignored and data is cleared directly (M-R5).
     */
    suspend fun clearWalletData(password: ByteArray): AccountOperationResult<Unit> =
        runOperation {
            try {
                if (vault.hasPassword()) {
                    // M-17A: pass a copy so vault's wipe (H-R5) does not zero the caller's array.
                    vault.clearAllData(password.copyOf())
                } else {
                    vault.clearAllData()
                }
            } catch (_: IllegalArgumentException) {
                return@runOperation AccountOperationResult.Error(AccountOperationError.WrongPassword())
            }
            store.clearAllAccounts()
            AccountOperationResult.Success(Unit)
        }

    private suspend fun persistVaultMaterial(derived: TraditionalDeriveResult) {
        val keypair = derived.keypair
        val mnemonic = derived.mnemonic
        val secret = derived.secret

        when {
            mnemonic != null -> {
                vault.importMnemonic(
                    address = derived.address,
                    mnemonic = mnemonic.value.toByteArray(),
                    privateKey = keypair.privateKey.toByteArray(),
                    language = mnemonic.language,
                    pathPrefix = derived.path?.toString() ?: ""
                )
            }
            secret != null -> {
                vault.importSecret(
                    derived.address,
                    keypair.privateKey.toByteArray(),
                    secret.toByteArray()
                )
            }
            else -> {
                // Sub-accounts are derived from the root mnemonic at signing time and carry no real
                // private key; importing an empty key would permanently lock the address in vault
                // (addressInKeys short-circuit blocks later real-key imports). Skip the vault write
                // so a genuine private key can be imported for the address later.
                if (keypair.privateKey.isNotEmpty()) {
                    vault.importPrivateKey(derived.address, keypair.privateKey.toByteArray())
                }
            }
        }
    }

    private inline fun <T> runOperation(block: () -> AccountOperationResult<T>): AccountOperationResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultAuthLockedException) {
            // M-21A: typed path for "vault locked" so callers can show the lock countdown
            // instead of a generic Failure.
            AccountOperationResult.Error(AccountOperationError.VaultLocked(e.remainingMs))
        } catch (e: Exception) {
            AccountOperationResult.Error(AccountOperationError.Failure(e))
        }
}
