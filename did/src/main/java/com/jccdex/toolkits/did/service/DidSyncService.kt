package com.jccdex.toolkits.did.service

import android.util.Log
import com.jccdex.toolkits.did.model.DidSyncEntry
import com.jccdex.toolkits.did.model.DidSyncResult
import com.jccdex.toolkits.did.model.WalletAccount
import com.jccdex.toolkits.did.sdk.DidSdk
import kotlinx.coroutines.CancellationException
import java.util.Locale

class DidSyncService(
    private val didSdk: DidSdk
) {
    suspend fun syncAccounts(accounts: List<WalletAccount>): DidSyncResult {
        if (accounts.isEmpty()) return DidSyncResult(emptyList())

        var failedCount = 0
        val entries =
            buildList {
                for (account in accounts) {
                    try {
                        val did = didSdk.toDid(account)
                        if (did.isBlank()) continue

                        val document = didSdk.resolveDid(did)
                        if (document.isNullOrBlank()) continue

                        add(
                            DidSyncEntry(
                                did = did,
                                addressLower = account.address.lowercase(Locale.ROOT),
                                document = document,
                                nickname = didSdk.nickname(document)
                            )
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        // M-DID7: per-account isolation — a corrupt account (e.g. non-40-hex EVM
                        // address) must not abort the whole sync batch; record and continue.
                        failedCount++
                        // Log.w wrapped so plain-JVM tests (android.util.Log not mocked) still pass.
                        runCatching { Log.w(TAG, "Did sync failed for account ${account.address}: ${t.message}") }
                    }
                }
            }

        return DidSyncResult(entries, failedCount = failedCount)
    }

    companion object {
        private const val TAG = "DidSyncService"
    }
}
