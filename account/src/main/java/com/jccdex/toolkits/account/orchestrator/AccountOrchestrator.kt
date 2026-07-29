package com.jccdex.toolkits.account.orchestrator

import com.jccdex.toolkits.account.store.IAccountStore
import com.jccdex.toolkits.core.model.ChainType
import com.jccdex.toolkits.core.model.Path
import com.jccdex.toolkits.core.model.WalletAccount
import com.jccdex.toolkits.vault.VaultRepository
import com.jccdex.toolkits.vault.model.VaultPrivateKeyImport
import com.jccdex.toolkits.wallet.model.GenerateHDWalletResult
import com.jccdex.toolkits.wallet.model.TraditionalDeriveResult
import com.jccdex.toolkits.wallet.sdk.WalletSdk
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.jccdex.toolkits.wallet.model.Path as WalletPath

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
            if (store.findNonRootAccount(address, chain) != null) {
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
                    path = derived.path?.toCorePath(),
                    publicKey = derived.keypair.publicKey
                )
            store.addAccount(walletAccount)
            AccountOperationResult.Success(walletAccount.id)
        }

    suspend fun importHdWallet(
        hdResult: GenerateHDWalletResult,
        name: String,
        password: ByteArray?,
        clearExisting: Boolean = false
    ): AccountOperationResult<ImportHdWalletResult> =
        runOperation {
            if (clearExisting) {
                store.clearAllAccounts()
                vault.clearAllData()
            }

            if (store.findRootAccountByAddress(hdResult.address) != null) {
                return@runOperation AccountOperationResult.Error(AccountOperationError.AccountAlreadyExists)
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

            for (sub in hdResult.accounts) {
                val chainType = ChainType.fromBip44Code(sub.chain) ?: continue
                keys.add(VaultPrivateKeyImport(sub.address, sub.keypair.privateKey.toByteArray()))

                if (store.findNonRootAccount(sub.address, chainType) != null) {
                    continue
                }

                val child =
                    WalletAccount(
                        address = sub.address,
                        chain = chainType,
                        name = "${chainType.label}-HD",
                        isHD = true,
                        parentId = rootAccount.id,
                        path = sub.path.toCorePath(),
                        publicKey = sub.keypair.publicKey
                    )
                accounts.add(child)
                childIds.add(HdChildAccountId(chainType, child.id))
            }

            vault.importPrivateKeys(keys)
            store.addAccounts(accounts)

            AccountOperationResult.Success(
                ImportHdWalletResult(
                    rootAccountId = rootAccount.id,
                    children = childIds
                )
            )
        }

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
                        com.jccdex.toolkits.wallet.model.Keypair(
                            privateKey = "",
                            publicKey = derived.publicKey
                        ),
                    path = derived.path.toWalletPath()
                ),
            chain = derived.chain,
            name = name,
            isHD = true,
            parentId = derived.rootAccountId
        )
    }

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

                mnemonic = vault.getMnemonicInternal(rootAccount.address)

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
                        path = subWallet.path.toCorePath(),
                        rootAccountId = rootAccount.id,
                        publicKey = subWallet.keypair.publicKey
                    )
                )
            } catch (e: Exception) {
                AccountOperationResult.Error(AccountOperationError.Failure(e))
            } finally {
                mnemonic?.fill(0)
            }
        }

    suspend fun clearWalletData() {
        store.clearAllAccounts()
        vault.clearAllData()
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
                vault.importPrivateKey(derived.address, keypair.privateKey.toByteArray())
            }
        }
    }

    private inline fun <T> runOperation(block: () -> AccountOperationResult<T>): AccountOperationResult<T> =
        try {
            block()
        } catch (e: Exception) {
            AccountOperationResult.Error(AccountOperationError.Failure(e))
        }

    private fun WalletPath.toCorePath(): Path =
        Path(
            chain = chain,
            account = account,
            change = change,
            index = index
        )

    private fun Path.toWalletPath(): WalletPath =
        WalletPath(
            chain = chain,
            account = account,
            change = change,
            index = index
        )
}
