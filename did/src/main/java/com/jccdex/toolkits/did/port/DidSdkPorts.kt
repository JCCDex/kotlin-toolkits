package com.jccdex.toolkits.did.port

import com.jccdex.toolkits.did.model.DidEntity
import com.jccdex.toolkits.did.model.Nft
import com.jccdex.toolkits.did.service.DidResolver
import com.jccdex.toolkits.did.store.DidStore

interface DidJsBridge {
    suspend fun call(method: String, params: String? = null): String
    suspend fun <T> callAs(method: String, params: String? = null, clazz: Class<T>): T
}

interface DidChainGateway : DidJsBridge

interface DidDocumentStore : DidStore

interface DidDocumentResolver : DidResolver

interface DidAvatarResolver {
    suspend fun resolveSwtcAvatar(vc: String): Nft?
    suspend fun resolveEthrAvatar(vc: String): Nft?
}

interface DidDocumentRepository {
    suspend fun get(did: String): DidEntity?
    suspend fun saveCreated(did: String, doc: String)
    suspend fun saveNickname(did: String, doc: String)
    suspend fun saveAvatar(did: String, doc: String)
    suspend fun delete(did: String, deletedDoc: String? = null)
    suspend fun resolveAndSave(did: String): String?
}
