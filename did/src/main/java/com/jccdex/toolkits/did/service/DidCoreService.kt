package com.jccdex.toolkits.did.service

import android.util.Log
import com.jccdex.toolkits.did.model.DidEntity
import com.jccdex.toolkits.did.store.IDidStore
import com.jccdex.toolkits.did.util.DidDocumentReader
import com.jccdex.toolkits.did.util.DidResolveUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class DidCoreService(
    private val store: IDidStore,
    private val resolver: IDidResolver,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    /** Grace period during which a freshly created DID missing on-chain is kept locally (H-DID2). */
    private companion object {
        const val CREATE_GRACE_PERIOD_MS = 5 * 60_000L

        // L-8: Stale entry cleanup threshold (1 hour)
        const val STALE_ENTRY_THRESHOLD_MS = 60 * 60_000L
        const val TAG = "DidCoreService"
    }

    // L-8: Pending entries with timestamp for stale cleanup
    private data class PendingEntry(val value: String, val createdAt: Long)

    private val pendingDeleteUpdated = ConcurrentHashMap<String, PendingEntry>()
    private val pendingCreateDids = ConcurrentHashMap<String, Long>()
    private val pendingUpdateAvatar = ConcurrentHashMap<String, PendingEntry>()
    private val pendingUpdateNickname = ConcurrentHashMap<String, PendingEntry>()

    fun observeAll(): Flow<List<DidEntity>> = store.observeAll()

    fun observe(did: String): Flow<DidEntity?> = store.observe(did)

    suspend fun resolveAndSaveDid(did: String): String? {
        return withContext(Dispatchers.IO) {
            // L-8: Periodic cleanup of stale pending entries
            cleanupStaleEntries()

            val localDoc = store.get(did)
            try {
                val chainDoc = resolver.resolve(did)

                if (DidResolveUtils.isMissingDidDocument(chainDoc)) {
                    Log.w("DidCoreService", "resolveAndSaveDid: chain document considered missing for $did")
                    return@withContext handleMissingChainDocument(did, localDoc)
                }

                if (chainDoc.isNotBlank()) {
                    val chainUpdated = extractUpdated(chainDoc)
                    val deletedTimestamp = pendingDeleteUpdated[did]?.value

                    // H-DID3: during delete confirmation (chain still carries the version we deleted),
                    // never backfill it — keep the local deleted state.
                    if (chainUpdated != null && chainUpdated == deletedTimestamp) {
                        pendingDeleteUpdated.remove(did)
                        return@withContext null
                    }

                    if (localDoc == null) {
                        store.upsert(DidEntity(did = did, doc = chainDoc))
                        return@withContext chainDoc
                    }

                    val localUpdated = extractUpdated(localDoc.doc)

                    val pendingAvatar = pendingUpdateAvatar[did]?.value
                    if (pendingAvatar != null) {
                        val chainAvatar = DidDocumentReader.readProfileField(chainDoc, "preferredAvatar")
                        if (chainAvatar != pendingAvatar) return@withContext localDoc.doc
                        pendingUpdateAvatar.remove(did)
                    }

                    val pendingNickname = pendingUpdateNickname[did]?.value
                    if (pendingNickname != null) {
                        val chainNickname = DidDocumentReader.readProfileField(chainDoc, "nickname")
                        if (chainNickname != pendingNickname) return@withContext localDoc.doc
                        pendingUpdateNickname.remove(did)
                    }

                    // L-12: Compare timestamps as Instant, not strings. Fallback to string comparison if parse fails.
                    if (chainUpdated != null &&
                        (localUpdated == null || isTimestampAfter(chainUpdated, localUpdated))
                    ) {
                        store.upsert(localDoc.copy(doc = chainDoc, updatedAt = System.currentTimeMillis()))
                        return@withContext chainDoc
                    }

                    return@withContext localDoc.doc
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "resolveAndSaveDid failed for $did", e)
            }
            return@withContext null
        }
    }

    private suspend fun handleMissingChainDocument(
        did: String,
        localDoc: DidEntity?
    ): String? {
        val createdAt = pendingCreateDids[did]
        if (createdAt != null && clock() - createdAt < CREATE_GRACE_PERIOD_MS) {
            // Chain propagation still in progress: keep the local doc, don't delete.
            return localDoc?.doc
        }
        pendingCreateDids.remove(did)
        store.delete(did)
        return null
    }

    suspend fun getDidDocument(did: String): DidEntity? = store.get(did)

    suspend fun deleteDidDocument(
        did: String,
        deletedDoc: String? = null
    ) {
        deletedDoc?.let { doc ->
            extractUpdated(doc)?.let { updated ->
                pendingDeleteUpdated[did] = PendingEntry(updated, clock())
            }
        }
        store.delete(did)
    }

    suspend fun saveNewCreatedDid(
        did: String,
        doc: String
    ) {
        saveDocumentWithPending(did, doc, PendingType.CREATE)
    }

    suspend fun saveNewAvatarDid(
        did: String,
        doc: String
    ) {
        saveDocumentWithPending(did, doc, PendingType.AVATAR)
    }

    suspend fun saveDidDocument(
        did: String,
        doc: String
    ) {
        withContext(Dispatchers.IO) {
            store.upsert(DidEntity(did = did, doc = doc, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun saveNewNicknameDid(
        did: String,
        doc: String
    ) {
        saveDocumentWithPending(did, doc, PendingType.NICKNAME)
    }

    private suspend fun saveDocumentWithPending(
        did: String,
        doc: String,
        pendingType: PendingType
    ) {
        withContext(Dispatchers.IO) {
            val now = clock()
            when (pendingType) {
                PendingType.CREATE -> pendingCreateDids[did] = now
                PendingType.AVATAR -> {
                    DidDocumentReader.readProfileField(doc, "preferredAvatar")?.let {
                        pendingUpdateAvatar[did] = PendingEntry(it, now)
                    }
                }
                PendingType.NICKNAME -> {
                    DidDocumentReader.readProfileField(doc, "nickname")?.let {
                        pendingUpdateNickname[did] = PendingEntry(it, now)
                    }
                }
            }
            val entity = DidEntity(did = did, doc = doc, updatedAt = System.currentTimeMillis())
            store.upsert(entity)
        }
    }

    private fun extractUpdated(doc: String): String? {
        return try {
            val json = JSONObject(doc)
            json.optString("updated", "").ifBlank { null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "extractUpdated failed", e)
            null
        }
    }

    private enum class PendingType {
        CREATE,
        AVATAR,
        NICKNAME
    }

    // L-12: Compare timestamps as Instant, fallback to string comparison if parse fails.
    private fun isTimestampAfter(
        timestamp1: String,
        timestamp2: String
    ): Boolean {
        return try {
            val instant1 = Instant.parse(timestamp1)
            val instant2 = Instant.parse(timestamp2)
            instant1.isAfter(instant2)
        } catch (_: Exception) {
            timestamp1 > timestamp2
        }
    }

    // L-8: Clean up stale pending entries older than STALE_ENTRY_THRESHOLD_MS
    private fun cleanupStaleEntries() {
        val now = clock()
        val threshold = STALE_ENTRY_THRESHOLD_MS

        pendingCreateDids.entries.removeIf { now - it.value > threshold }
        pendingDeleteUpdated.entries.removeIf { now - it.value.createdAt > threshold }
        pendingUpdateAvatar.entries.removeIf { now - it.value.createdAt > threshold }
        pendingUpdateNickname.entries.removeIf { now - it.value.createdAt > threshold }
    }
}
