package com.jccdex.toolkits.did.service

import com.jccdex.toolkits.did.model.DidSyncEntry
import com.jccdex.toolkits.did.model.DidSyncResult
import com.jccdex.toolkits.did.model.WalletAccount
import com.jccdex.toolkits.did.sdk.DidSdk

class DidSyncService(
    private val didSdk: DidSdk
) {
    suspend fun syncAccounts(accounts: List<WalletAccount>): DidSyncResult {
        if (accounts.isEmpty()) return DidSyncResult(emptyList())

        val entries =
            buildList {
                for (account in accounts) {
                    val did = didSdk.toDid(account)
                    if (did.isBlank()) continue

                    val document = runCatching { didSdk.resolveDid(did) }.getOrNull()
                    if (document.isNullOrBlank()) continue

                    add(
                        DidSyncEntry(
                            did = did,
                            addressLower = account.address.lowercase(),
                            document = document,
                            nickname = didSdk.nickname(document)
                        )
                    )
                }
            }

        return DidSyncResult(entries)
    }
}
