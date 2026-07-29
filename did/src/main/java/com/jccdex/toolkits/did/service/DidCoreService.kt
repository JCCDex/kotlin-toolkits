package com.jccdex.toolkits.did.service

import com.jccdex.toolkits.did.model.DidEntity
import com.jccdex.toolkits.did.store.IDidStore
import com.jccdex.toolkits.did.util.DidResolveUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class DidCoreService(
    private val store: IDidStore,
    private val resolver: IDidResolver
) {
    private val pendingDeleteUpdated = ConcurrentHashMap<String, String>()
    private val pendingCreateDids = ConcurrentHashMap.newKeySet<String>()
    private val pendingUpdateAvatar = ConcurrentHashMap<String, String>()
    private val pendingUpdateNickname = ConcurrentHashMap<String, String>()

    fun observeAll(): Flow<List<DidEntity>> = store.observeAll()

    fun observe(did: String): Flow<DidEntity?> = store.observe(did)

    suspend fun resolveAndSaveDid(did: String): String? {
        return withContext(Dispatchers.IO) {
            val localDoc = store.get(did)
            try {
                val chainDoc = resolver.resolve(did)

                if (DidResolveUtils.isMissingDidDocument(chainDoc)) {
                    return@withContext handleMissingChainDocument(did, localDoc)
                }

                if (chainDoc.isNotBlank()) {
                    if (localDoc == null) {
                        store.upsert(DidEntity(did = did, doc = chainDoc))
                        return@withContext chainDoc
                    }

                    val localUpdated = extractUpdated(localDoc.doc)
                    val chainUpdated = extractUpdated(chainDoc)

                    val deletedTimestamp = pendingDeleteUpdated[did]
                    if (chainUpdated != null && chainUpdated == deletedTimestamp) {
                        pendingDeleteUpdated.remove(did)
                        return@withContext localDoc.doc
                    }

                    val pendingAvatar = pendingUpdateAvatar[did]
                    if (pendingAvatar != null) {
                        val chainAvatar = readProfileField(chainDoc, "preferredAvatar")
                        if (chainAvatar != pendingAvatar) return@withContext localDoc.doc
                        pendingUpdateAvatar.remove(did)
                    }

                    val pendingNickname = pendingUpdateNickname[did]
                    if (pendingNickname != null) {
                        val chainNickname = readProfileField(chainDoc, "nickname")
                        if (chainNickname != pendingNickname) return@withContext localDoc.doc
                        pendingUpdateNickname.remove(did)
                    }

                    if (chainUpdated != null && (localUpdated == null || chainUpdated > localUpdated)) {
                        store.upsert(localDoc.copy(doc = chainDoc, updatedAt = System.currentTimeMillis()))
                        return@withContext chainDoc
                    }

                    return@withContext localDoc.doc
                }
            } catch (_: Exception) {
            }
            return@withContext null
        }
    }

    private suspend fun handleMissingChainDocument(
        did: String,
        localDoc: DidEntity?
    ): String? {
        if (pendingCreateDids.contains(did)) {
            pendingCreateDids.remove(did)
            return localDoc?.doc
        }
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
                pendingDeleteUpdated[did] = updated
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
            when (pendingType) {
                PendingType.CREATE -> pendingCreateDids.add(did)
                PendingType.AVATAR -> {
                    readProfileField(doc, "preferredAvatar")?.let { pendingUpdateAvatar[did] = it }
                }
                PendingType.NICKNAME -> {
                    readProfileField(doc, "nickname")?.let { pendingUpdateNickname[did] = it }
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
        } catch (_: Exception) {
            null
        }
    }

    private enum class PendingType {
        CREATE,
        AVATAR,
        NICKNAME
    }

    private fun readProfileField(
        doc: String,
        key: String
    ): String? {
        return try {
            val root = JSONObject(doc)
            val services = root.optJSONArray("service") ?: root.optJSONArray("services") ?: return null
            for (i in 0 until services.length()) {
                val service = services.optJSONObject(i) ?: continue
                if (service.optString("type") != "Profile") continue
                val endpoint = service.optJSONObject("serviceEndpoint") ?: continue
                val value = endpoint.optString(key, "")
                if (value.isNotBlank()) return value
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
