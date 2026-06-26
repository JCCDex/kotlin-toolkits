package com.jccdex.toolkits.did.sdk

import android.content.Context
import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.jccdex.toolkits.did.model.ChainType
import com.jccdex.toolkits.did.model.CredentialVerificationResult
import com.jccdex.toolkits.did.model.Did
import com.jccdex.toolkits.did.model.DidAvatarCredential
import com.jccdex.toolkits.did.model.DidEntity
import com.jccdex.toolkits.did.model.DidStatResult
import com.jccdex.toolkits.did.model.DidWriteResult
import com.jccdex.toolkits.did.model.GenerateBase58PKResult
import com.jccdex.toolkits.did.model.GranteeCredentialUpdateResult
import com.jccdex.toolkits.did.model.Nft
import com.jccdex.toolkits.did.model.Profile
import com.jccdex.toolkits.did.model.ProfileVC
import com.jccdex.toolkits.did.model.PublishDidResult
import com.jccdex.toolkits.did.model.QueryVcidResult
import com.jccdex.toolkits.did.model.UnifiedNftCredentialData
import com.jccdex.toolkits.did.model.VerificationMethod
import com.jccdex.toolkits.did.model.WalletAccount
import com.jccdex.toolkits.did.port.DidAvatarAsset
import com.jccdex.toolkits.did.port.IDidAvatarCredentialSource
import com.jccdex.toolkits.did.port.IDidAvatarResolver
import com.jccdex.toolkits.did.port.IDidBridge
import com.jccdex.toolkits.did.service.DidCoreService
import com.jccdex.toolkits.did.service.IDidResolver
import com.jccdex.toolkits.did.storage.room.DidRoomDatabase
import com.jccdex.toolkits.did.storage.room.RoomDidStore
import com.jccdex.toolkits.did.store.IDidStore
import com.jccdex.toolkits.did.util.ChecksumUtils
import com.jccdex.toolkits.did.util.DidCredentialHelper
import com.jccdex.toolkits.did.util.DidResolveUtils
import com.jccdex.toolkits.nft.NftSdk
import com.jccdex.toolkits.nft.model.AvatarCandidate
import com.jccdex.toolkits.nft.model.CredentialImageRequest
import com.jccdex.toolkits.nft.model.EthTokenUriResolver
import com.jccdex.toolkits.nft.model.NftMetadataFields
import com.jccdex.toolkits.nft.model.ResolvedCredentialImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.jccdex.toolkits.nft.model.Nft as NftSdkNft

class DidSdk internal constructor(
    private val bridge: IDidBridge,
    private val core: DidCoreService,
    private val avatarResolver: IDidAvatarResolver? = null,
    private val avatarCredentialSource: IDidAvatarCredentialSource? = null,
    private val nftSdk: NftSdk? = null
) {
    fun toDid(wallet: WalletAccount?): String {
        if (wallet == null) return ""
        return when {
            wallet.chain.isEvmChain() -> "did:ethr:${ChecksumUtils.toChecksumAddress(wallet.address)}"
            wallet.chain == ChainType.SWTC -> "did:swtc:${wallet.address}"
            else -> error("Unsupported chain type: ${wallet.chain}")
        }
    }

    fun formatAddress(address: String): String =
        if (address.length <= 8) address else address.substring(0, 4) + "***" + address.takeLast(4)

    fun nickname(doc: String): String = getProfile(doc)?.nickname.orEmpty()

    fun observeDidDocument(did: String): Flow<DidEntity?> = core.observe(did)

    fun observeAllDidDocuments(): Flow<List<DidEntity>> = core.observeAll()

    suspend fun getDidDocument(did: String): DidEntity? = core.getDidDocument(did)

    fun getProfile(doc: String): Profile? {
        val nickname = readProfileField(doc, "nickname")
        val preferredAvatar = readProfileField(doc, "preferredAvatar")
        return if (nickname != null || preferredAvatar != null) {
            Profile(
                nickname = nickname ?: "",
                preferredAvatar = preferredAvatar ?: ""
            )
        } else {
            null
        }
    }

    suspend fun generateDid(did: String): Did? =
        withContext(Dispatchers.Default) {
            try {
                val entity = core.getDidDocument(did) ?: return@withContext null
                entity.toDid(did)
            } catch (e: Exception) {
                Log.e("DidSdk", "generateDid error", e)
                null
            }
        }

    suspend fun generateProfileVC(did: String): ProfileVC? =
        withContext(Dispatchers.Default) {
            try {
                val entity = core.getDidDocument(did) ?: return@withContext null
                val profile = getProfile(entity.doc)
                val credentials = readJsonArray(entity.doc, "credentials")
                var nft: Nft? = null

                if (profile != null) {
                    val vc = findCredentialById(credentials, profile.preferredAvatar)?.toString()
                    if (!vc.isNullOrBlank()) {
                        nft = generateAvatarNft(vc)
                    }
                }

                nft?.let {
                    if (!it.hasLocal && it.uri.isNotBlank()) {
                        nftSdk?.fetchAndCacheNftMeta(it.contract, it.tokenId, it.uri)
                    }
                }

                ProfileVC(
                    nickname = profile?.nickname ?: "",
                    bio = "",
                    createdTime = nft?.issuanceDate ?: "",
                    nft = nft
                )
            } catch (e: Exception) {
                Log.e("DidSdk", "generateProfileVC error", e)
                null
            }
        }

    private suspend fun generateAvatarNft(vc: String): Nft? =
        if (isSwtcAvatarVc(
                vc
            )
        ) {
            generateSwtcNft(vc)
        } else {
            generateEthrNft(vc)
        }

    /**
     * Route avatar VC resolution by NFT standard / subject fields, not owner DID chain.
     * Aligns with did_DApp `identity.vue` (`credentialSubject.standard`).
     */
    internal fun isSwtcAvatarVc(vc: String): Boolean {
        when (readString(vc, "credentialSubject.standard")?.lowercase()) {
            SWTC_NFT_STANDARD -> return true
            EVM_NFT_STANDARD -> return false
        }
        val nftIssuer = readString(vc, "credentialSubject.nftIssuer").orEmpty()
        val contractAddress = readString(vc, "credentialSubject.contractAddress").orEmpty()
        return nftIssuer.isNotBlank() && contractAddress.isBlank()
    }

    suspend fun generateSwtcNft(vc: String): Nft? {
        avatarResolver?.resolveSwtcAvatar(vc)?.let { return it }
        nftSdk?.resolveSwtcAvatar(vc)?.let { return it.toDidNft() }
        return buildSwtcNft(vc)
    }

    private suspend fun buildSwtcNft(vc: String): Nft? {
        val tokenId = readString(vc, "credentialSubject.tokenId") ?: ""
        val nftIssuer = readString(vc, "credentialSubject.nftIssuer") ?: ""
        val tokenName = readString(vc, "credentialSubject.tokenName") ?: ""
        val issuance = readString(vc, "issuanceDate") ?: ""
        return Nft(
            contract = nftIssuer,
            tokenId = tokenId,
            name = tokenName,
            uri = "",
            image = null,
            hasLocal = false,
            issuanceDate = issuance,
            chainId = null
        )
    }

    suspend fun generateEthrNft(vc: String): Nft? {
        avatarResolver?.resolveEthrAvatar(vc)?.let { return it }
        nftSdk?.resolveEthrAvatar(vc)?.let { return it.toDidNft() }
        return buildEthrNft(vc)
    }

    suspend fun getAvatarNftCredentials(account: WalletAccount): List<DidAvatarCredential> {
        val ownerDid = toDid(account)
        if (ownerDid.isBlank()) return emptyList()

        val sourceCandidates =
            avatarCredentialSource?.getAvatarCandidates(account)
                ?: nftSdk?.getAvatarCandidates(account)?.map { it.toDidAvatarAsset() }
                ?: emptyList()
        return sourceCandidates.map { asset ->
            buildAvatarCredential(ownerDid, asset)
        }
    }

    suspend fun resolveCredentialImage(
        imageUrl: String?,
        metadataUri: String?
    ): String? = nftSdk?.resolveCredentialImage(imageUrl, metadataUri)

    suspend fun resolveCredentialImage(request: CredentialImageRequest): ResolvedCredentialImage? =
        nftSdk?.resolveCredentialImage(request)

    suspend fun resolveCredentialImages(requests: List<CredentialImageRequest>): List<ResolvedCredentialImage?> =
        nftSdk?.resolveCredentialImages(requests).orEmpty()

    suspend fun fetchResolvedMetadataImage(metadataUrl: String): String? =
        nftSdk?.fetchResolvedMetadataImage(metadataUrl)

    fun normalizeAssetUrl(
        rawUrl: String?,
        baseUrl: String? = null
    ): String? = nftSdk?.normalizeAssetUrl(rawUrl, baseUrl)

    fun extractResolvedMetadataImageUrl(
        metadataBody: String,
        metadataUri: String
    ): String? = nftSdk?.extractResolvedMetadataImageUrl(metadataBody, metadataUri)

    fun isSupportedRemoteAssetUrl(url: String?): Boolean = nftSdk?.isSupportedRemoteAssetUrl(url) == true

    fun extractSwtcMetadataUri(tokenInfosPayload: String?): String? = nftSdk?.extractSwtcMetadataUri(tokenInfosPayload)

    suspend fun fetchMetadataFields(metadataUri: String): NftMetadataFields? = nftSdk?.fetchMetadataFields(metadataUri)

    suspend fun ensureSwtcCredentialMetadata(vc: String) {
        nftSdk?.ensureSwtcCredentialMetadata(vc)
    }

    private suspend fun buildEthrNft(vc: String): Nft? {
        val tokenId = readString(vc, "credentialSubject.tokenId") ?: ""
        val contract = readString(vc, "credentialSubject.contractAddress") ?: ""
        val chainId = readElement(vc, "credentialSubject.chainId")?.asLong ?: 0L
        val issuance = readString(vc, "issuanceDate") ?: ""
        return try {
            Nft(
                contract = contract,
                tokenId = tokenId,
                name = "",
                uri = "",
                image = null,
                hasLocal = false,
                issuanceDate = issuance,
                chainId = chainId
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * did_getBase58PublicKey：用私钥生成 base58 公钥与验证方法类型。
     */
    suspend fun didGenerateBase58PublicKey(privateKey: String): GenerateBase58PKResult =
        withContext(Dispatchers.IO) {
            bridge.callAs(
                "generatePublicKeyBase58",
                JSONObject().apply { put("privateKey", privateKey) }.toString(),
                GenerateBase58PKResult::class.java
            )
        }

    /**
     * did_issueCredential 的钱包侧实现。
     *
     * [payload] 为 DApp 通过 @jccdex/did issueVC sign 回调传来的对象 JSON，含
     * credential / keyDoc / compactProof / issuerObject / addSuiteContext / type。
     * 这里补上 [privateKey] 后交由 JS 桥跑完整 issueCredential，返回签名后的 VC（JSON 字符串）。
     */
    suspend fun signCredentialForDApp(
        privateKey: String,
        payload: String
    ): String =
        withContext(Dispatchers.IO) {
            val params =
                JSONObject(payload).apply { put("privateKey", privateKey) }
            bridge.call("signCredential", params.toString())
        }

    /**
     * ipfs_getPublicKey：返回压缩 secp256k1 公钥（hex）。
     */
    suspend fun ipfsGetPublicKey(privateKey: String): String =
        withContext(Dispatchers.IO) {
            bridge.call(
                "ipfsGetPublicKey",
                JSONObject().apply { put("privateKey", privateKey) }.toString()
            )
        }

    /**
     * ipfs_personalSign：对带 IPFS 前缀的消息做 SHA-256 后 secp256k1 签名，返回 DER(hex)。
     * [data] 为原始消息字节。
     */
    suspend fun ipfsPersonalSign(
        privateKey: String,
        data: IntArray
    ): String =
        withContext(Dispatchers.IO) {
            val arr = JSONArray()
            data.forEach { arr.put(it) }
            bridge.call(
                "ipfsPersonalSign",
                JSONObject().apply {
                    put("privateKey", privateKey)
                    put("data", arr)
                }.toString()
            )
        }

    suspend fun uploadInitialDidDoc(
        privateKey: String,
        did: String,
        nickname: String = ""
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val result =
                    bridge.callAs(
                        "generatePublicKeyBase58",
                        JSONObject().apply { put("privateKey", privateKey) }.toString(),
                        GenerateBase58PKResult::class.java
                    )
                val previousCid =
                    try {
                        bridge.callAs(
                            "didStat",
                            JSONObject().apply { put("did", did) }.toString(),
                            DidStatResult::class.java
                        ).cid.orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                val didDoc =
                    JSONObject().apply {
                        put("version", "1.0.0")
                        put(
                            "verificationMethods",
                            JSONArray().apply {
                                put(
                                    JSONObject().apply {
                                        put("id", "$did#key-1")
                                        put("type", result.type)
                                        put("controller", did)
                                        put("publicKeyBase58", result.publicKeyBase58)
                                    }
                                )
                            }
                        )
                        put("assertionMethods", JSONArray().apply { put("$did#key-1") })
                        put("authentications", JSONArray().apply { put("$did#key-1") })
                        put(
                            "services",
                            JSONArray().apply {
                                put(
                                    JSONObject().apply {
                                        put("id", "$did#profile")
                                        put("type", "Profile")
                                        put(
                                            "serviceEndpoint",
                                            JSONObject().apply {
                                                put("nickname", nickname)
                                                put("preferredAvatar", "")
                                            }
                                        )
                                    }
                                )
                                put(
                                    JSONObject().apply {
                                        put("id", "$did#ipfs-storage")
                                        put("type", "IpfsStorage")
                                        put(
                                            "serviceEndpoint",
                                            JSONObject().apply {
                                                put(
                                                    "ipns",
                                                    "ipns://k2k4r8ntjlp1cmgped39eq1fi4yze6fsr8og1kcmjhamgs3ubwkfldei"
                                                )
                                                if (previousCid.isNotBlank()) {
                                                    put(
                                                        "previousCid",
                                                        previousCid
                                                    )
                                                }
                                            }
                                        )
                                    }
                                )
                            }
                        )
                        put("credentials", JSONArray())
                        put("did", did)
                    }

                val didDocJson =
                    ensureCredentialsArrayInDidDocument(
                        bridge.call("generateDidDoc", didDoc.toString())
                    )
                val res =
                    bridge.callAs(
                        "publishDid",
                        JSONObject().apply {
                            put("did", did)
                            put("privateKey", privateKey)
                            put("didDocument", didDocJson)
                        }.toString(),
                        PublishDidResult::class.java
                    )
                if (res.code == "0") {
                    core.saveNewCreatedDid(did, didDocJson)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e("DidSdk", "Error uploading initial DID doc", e)
                false
            }
        }

    suspend fun updateDidNickname(
        privateKey: String,
        did: String,
        nickname: String,
        currentDoc: String
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val doc = resolveBaseDoc(did, currentDoc) ?: return@withContext false
                val json = JSONObject(doc)
                val services = readServices(json)
                val previousCid = readDidStatCid(did)
                val updatedServices = JSONArray()
                for (i in 0 until services.length()) {
                    val service = services.getJSONObject(i)
                    when (service.optString("type")) {
                        "Profile" -> {
                            updatedServices.put(
                                JSONObject().apply {
                                    put("id", "$did#profile")
                                    put("type", "Profile")
                                    put(
                                        "serviceEndpoint",
                                        JSONObject().apply {
                                            put("nickname", nickname)
                                            put(
                                                "preferredAvatar",
                                                readProfileField(doc, "preferredAvatar").orEmpty()
                                            )
                                        }
                                    )
                                }
                            )
                        }

                        "IpfsStorage" -> {
                            val endpoint = service.optJSONObject("serviceEndpoint") ?: JSONObject()
                            if (previousCid.isNotBlank()) endpoint.put("previousCid", previousCid)
                            updatedServices.put(
                                JSONObject().apply {
                                    put("id", "$did#ipfs-storage")
                                    put("type", "IpfsStorage")
                                    put("serviceEndpoint", endpoint)
                                }
                            )
                        }

                        else -> updatedServices.put(service)
                    }
                }
                json.put("service", updatedServices)
                json.put("updated", Instant.now().toString())
                json.remove("did")
                val res = publishDid(did, privateKey, json.toString())
                if (res.code == "0") {
                    core.saveNewNicknameDid(did, json.toString())
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e("DidSdk", "Error updating DID nickname", e)
                false
            }
        }

    suspend fun updateDidAvatar(
        privateKey: String,
        did: String,
        currentDoc: String,
        selectedAvatar: DidAvatarCredential
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val doc = resolveBaseDoc(did, currentDoc) ?: return@withContext false
                val vcJson = generateAvatarVc(privateKey, did, selectedAvatar)
                val json = JSONObject(doc)
                val services = readServices(json)
                val previousCid = readDidStatCid(did)
                val updatedServices = JSONArray()
                for (i in 0 until services.length()) {
                    val service = services.getJSONObject(i)
                    when (service.optString("type")) {
                        "Profile" -> {
                            updatedServices.put(
                                JSONObject().apply {
                                    put("id", "$did#profile")
                                    put("type", "Profile")
                                    put(
                                        "serviceEndpoint",
                                        JSONObject().apply {
                                            put(
                                                "nickname",
                                                readProfileField(doc, "nickname").orEmpty()
                                            )
                                            put("preferredAvatar", selectedAvatar.credentialId)
                                        }
                                    )
                                }
                            )
                        }

                        "IpfsStorage" -> {
                            val endpoint = service.optJSONObject("serviceEndpoint") ?: JSONObject()
                            if (previousCid.isNotBlank()) endpoint.put("previousCid", previousCid)
                            updatedServices.put(
                                JSONObject().apply {
                                    put("id", "$did#ipfs-storage")
                                    put("type", "IpfsStorage")
                                    put("serviceEndpoint", endpoint)
                                }
                            )
                        }

                        else -> updatedServices.put(service)
                    }
                }
                val credentials = readJsonArray(json.toString(), "credentials")
                val updatedCredentials = JSONArray()
                for (i in 0 until credentials.length()) {
                    val cred = credentials.getJSONObject(i)
                    if (!cred.optString("id").equals(selectedAvatar.credentialId, true)) {
                        updatedCredentials.put(cred)
                    }
                }
                updatedCredentials.put(JSONObject(vcJson))
                json.put("service", updatedServices)
                json.put("credentials", updatedCredentials)
                json.put("updated", Instant.now().toString())
                json.remove("did")
                val res = publishDid(did, privateKey, json.toString())
                if (res.code == "0") {
                    core.saveNewAvatarDid(did, json.toString())
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e("DidSdk", "Error updating DID avatar", e)
                false
            }
        }

    suspend fun publishDidDelete(
        privateKey: String,
        did: String
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val res = publishDid(did, privateKey, JSONObject().toString())
                if (res.code == "0") {
                    val doc = core.getDidDocument(did)?.doc
                    core.deleteDidDocument(did, doc)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e("DidSdk", "Error publishing delete DID", e)
                false
            }
        }

    suspend fun resolveDid(did: String): String? = core.resolveAndSaveDid(did)

    fun readCredentials(doc: String): List<String> {
        val credentials = DidCredentialHelper.readCredentials(doc)
        return buildList {
            for (index in 0 until credentials.length()) {
                credentials.optJSONObject(index)?.toString()?.let(::add)
            }
        }
    }

    /**
     * Add a signed NFT credential to the owner DID document and publish to IPFS.
     * Supports self ownership VC and others usage-authorization VC (did_DApp parity).
     */
    suspend fun addCredentialToDid(
        privateKey: String,
        did: String,
        currentDoc: String,
        credentialData: UnifiedNftCredentialData
    ): DidWriteResult =
        withContext(Dispatchers.IO) {
            try {
                require(credentialData.ownerDid == did) { "ownerDid must match did" }
                DidCredentialHelper.validateCredentialData(credentialData)
                val doc =
                    resolveBaseDoc(did, currentDoc) ?: return@withContext DidWriteResult(false)
                val vcId = DidCredentialHelper.generateVcId(credentialData)
                val credentials = DidCredentialHelper.readCredentials(doc)
                if (DidCredentialHelper.findCredentialIndex(credentials, vcId) >= 0) {
                    return@withContext DidWriteResult(success = true, didDocument = doc)
                }

                val vcJson = generateNftVc(privateKey, did, credentialData)
                val json = JSONObject(doc)
                val updatedCredentials = JSONArray()
                for (index in 0 until credentials.length()) {
                    updatedCredentials.put(credentials.getJSONObject(index))
                }
                updatedCredentials.put(JSONObject(vcJson))
                json.put("credentials", updatedCredentials)
                json.put("updated", Instant.now().toString())
                applyPreviousCid(json, did)
                json.remove("did")

                val res = publishDid(did, privateKey, json.toString())
                if (res.code == "0") {
                    core.saveDidDocument(did, json.toString())
                    DidWriteResult(success = true, didDocument = json.toString())
                } else {
                    DidWriteResult(success = false)
                }
            } catch (e: Exception) {
                Log.e("DidSdk", "Error adding credential to DID", e)
                DidWriteResult(success = false)
            }
        }

    /**
     * Remove a credential from the owner DID document and publish to IPFS.
     * Clears [Profile.preferredAvatar] when the deleted VC is the current avatar.
     */
    suspend fun deleteCredentialFromDid(
        privateKey: String,
        did: String,
        currentDoc: String,
        credentialId: String
    ): DidWriteResult =
        withContext(Dispatchers.IO) {
            try {
                require(credentialId.isNotBlank()) { "credentialId is required" }
                val doc =
                    resolveBaseDoc(did, currentDoc) ?: return@withContext DidWriteResult(false)
                val credentials = DidCredentialHelper.readCredentials(doc)
                val targetIndex = DidCredentialHelper.findCredentialIndex(credentials, credentialId)
                if (targetIndex < 0) {
                    return@withContext DidWriteResult(success = false)
                }

                val json = JSONObject(doc)
                val updatedCredentials = JSONArray()
                for (index in 0 until credentials.length()) {
                    if (index == targetIndex) continue
                    updatedCredentials.put(credentials.getJSONObject(index))
                }
                json.put("credentials", updatedCredentials)
                json.put(
                    "service",
                    DidCredentialHelper.clearPreferredAvatarIfMatches(
                        readServices(json),
                        credentialId
                    )
                )
                json.put("updated", Instant.now().toString())
                applyPreviousCid(json, did)
                json.remove("did")

                val res = publishDid(did, privateKey, json.toString())
                if (res.code == "0") {
                    core.saveDidDocument(did, json.toString())
                    DidWriteResult(success = true, didDocument = json.toString())
                } else {
                    DidWriteResult(success = false)
                }
            } catch (e: Exception) {
                Log.e("DidSdk", "Error deleting credential from DID", e)
                DidWriteResult(success = false)
            }
        }

    /**
     * Resolve the owner DID from [vcid], locate the credential, and verify it.
     * Mirrors did_DApp [queryAndValidateVcid].
     */
    suspend fun queryAndValidateVcid(vcid: String): QueryVcidResult =
        withContext(Dispatchers.IO) {
            try {
                if (vcid.isBlank()) {
                    return@withContext QueryVcidResult(isValid = false, credential = null)
                }
                val ownerDid = DidCredentialHelper.ownerDidFromCredentialId(vcid)
                if (ownerDid.isBlank()) {
                    return@withContext QueryVcidResult(isValid = false, credential = null)
                }
                val ownerDoc =
                    resolveOwnerDidDocument(ownerDid)
                        ?: return@withContext QueryVcidResult(isValid = false, credential = null)
                val credentials = DidCredentialHelper.readCredentials(ownerDoc)
                val matchedIndex = DidCredentialHelper.findCredentialIndex(credentials, vcid)
                if (matchedIndex < 0) {
                    return@withContext QueryVcidResult(isValid = false, credential = null)
                }
                val credentialJson = credentials.getJSONObject(matchedIndex).toString()
                val verifyResult = verifyCredential(credentialJson)
                QueryVcidResult(
                    isValid = verifyResult.verified,
                    credential = credentialJson
                )
            } catch (e: Exception) {
                Log.e("DidSdk", "Error querying VCID", e)
                QueryVcidResult(isValid = false, credential = null)
            }
        }

    /**
     * Merge a validated credential into the current DID document and publish.
     * Mirrors did_DApp [bindVcidToDid].
     */
    suspend fun bindVcidToDid(
        privateKey: String,
        did: String,
        currentDoc: String,
        credentialJson: String
    ): DidWriteResult =
        withContext(Dispatchers.IO) {
            try {
                require(credentialJson.isNotBlank()) { "credentialJson is required" }
                val incoming = JSONObject(credentialJson)
                val credentialId = incoming.optString("id")
                require(credentialId.isNotBlank()) { "credential id is required" }

                val doc =
                    resolveBaseDoc(did, currentDoc) ?: return@withContext DidWriteResult(false)
                val json = JSONObject(doc)
                val credentials = DidCredentialHelper.readCredentials(doc)
                val updatedCredentials = JSONArray()
                var replaced = false
                for (index in 0 until credentials.length()) {
                    val existing = credentials.getJSONObject(index)
                    if (existing.optString("id").equals(credentialId, ignoreCase = true)) {
                        updatedCredentials.put(incoming)
                        replaced = true
                    } else {
                        updatedCredentials.put(existing)
                    }
                }
                if (!replaced) {
                    updatedCredentials.put(incoming)
                }
                json.put("credentials", updatedCredentials)
                json.put("updated", Instant.now().toString())
                applyPreviousCid(json, did)
                json.remove("did")

                val res = publishDid(did, privateKey, json.toString())
                if (res.code == "0") {
                    core.saveDidDocument(did, json.toString())
                    DidWriteResult(success = true, didDocument = json.toString())
                } else {
                    DidWriteResult(success = false)
                }
            } catch (e: Exception) {
                Log.e("DidSdk", "Error binding VCID to DID", e)
                DidWriteResult(success = false)
            }
        }

    /**
     * Update [Profile.preferredAvatar] without issuing a new VC.
     * Mirrors did_DApp [updateUserProfile] when only avatar is changed.
     */
    suspend fun updatePreferredAvatar(
        privateKey: String,
        did: String,
        currentDoc: String,
        credentialId: String
    ): DidWriteResult =
        withContext(Dispatchers.IO) {
            try {
                require(credentialId.isNotBlank()) { "credentialId is required" }
                val doc =
                    resolveBaseDoc(did, currentDoc) ?: return@withContext DidWriteResult(false)
                val json = JSONObject(doc)
                val services = readServices(json)
                val updatedServices = JSONArray()
                for (index in 0 until services.length()) {
                    val service = services.getJSONObject(index)
                    when (service.optString("type")) {
                        "Profile" -> {
                            updatedServices.put(
                                JSONObject().apply {
                                    put("id", "$did#profile")
                                    put("type", "Profile")
                                    put(
                                        "serviceEndpoint",
                                        JSONObject().apply {
                                            put(
                                                "nickname",
                                                readProfileField(doc, "nickname").orEmpty()
                                            )
                                            put("preferredAvatar", credentialId)
                                        }
                                    )
                                }
                            )
                        }

                        else -> updatedServices.put(service)
                    }
                }
                json.put("service", updatedServices)
                json.put("updated", Instant.now().toString())
                applyPreviousCid(json, did)
                json.remove("did")

                val res = publishDid(did, privateKey, json.toString())
                if (res.code == "0") {
                    core.saveNewAvatarDid(did, json.toString())
                    DidWriteResult(success = true, didDocument = json.toString())
                } else {
                    DidWriteResult(success = false)
                }
            } catch (e: Exception) {
                Log.e("DidSdk", "Error updating preferred avatar", e)
                DidWriteResult(success = false)
            }
        }

    /**
     * Verify a VC signature and validity. Mirrors did_DApp [verifyCredential].
     */
    suspend fun verifyCredential(credentialJson: String): CredentialVerificationResult =
        withContext(Dispatchers.IO) {
            if (credentialJson.isBlank()) {
                throw IllegalArgumentException("Credential JSON is empty")
            }

            val credential = JSONObject(credentialJson)
            val expirationDate = credential.optString("expirationDate")
            if (expirationDate.isNotBlank()) {
                runCatching { Instant.parse(expirationDate) }
                    .getOrNull()
                    ?.takeIf { it.isBefore(Instant.now()) }
                    ?.let { return@withContext CredentialVerificationResult(verified = false) }
            }

            if (DidCredentialHelper.credentialIncludesType(
                    credentialJson,
                    DidCredentialHelper.VC_TYPE_USAGE_AUTHORIZATION
                )
            ) {
                if (checkGranteeCredentialUpdate(credentialJson).isUpdate) {
                    return@withContext CredentialVerificationResult(verified = false)
                }
            }

            val resultJson =
                bridge.call(
                    "verifyCredential",
                    JSONObject().apply { put("credential", credentialJson) }.toString()
                )
            val result = JSONObject(resultJson)
            CredentialVerificationResult(
                verified = result.optBoolean("verified", false),
                results = result.opt("results")?.toString()
            )
        }

    /**
     * Detect whether a grantee usage-authorization VC has been revoked or superseded on chain.
     */
    suspend fun checkGranteeCredentialUpdate(credentialJson: String): GranteeCredentialUpdateResult =
        withContext(Dispatchers.IO) {
            try {
                val credential = JSONObject(credentialJson)
                val credentialId = credential.optString("id")
                val ownerDid = DidCredentialHelper.ownerDidFromCredentialId(credentialId)
                if (ownerDid.isBlank()) {
                    return@withContext GranteeCredentialUpdateResult(
                        isUpdate = true,
                        credential = null
                    )
                }

                val ownerDoc =
                    resolveOwnerDidDocument(ownerDid)
                        ?: return@withContext GranteeCredentialUpdateResult(
                            isUpdate = true,
                            credential = null,
                            fetchFailed = true
                        )
                val credentials = DidCredentialHelper.readCredentials(ownerDoc)
                val matchedIndex =
                    DidCredentialHelper.findCredentialIndex(credentials, credentialId)
                if (matchedIndex < 0) {
                    return@withContext GranteeCredentialUpdateResult(
                        isUpdate = true,
                        credential = null
                    )
                }

                val matched = credentials.getJSONObject(matchedIndex)
                val originalSubject = credential.optJSONObject("credentialSubject")
                val matchedSubject = matched.optJSONObject("credentialSubject")
                if (originalSubject?.optString("id") != matchedSubject?.optString("id")) {
                    return@withContext GranteeCredentialUpdateResult(
                        isUpdate = true,
                        credential = matched.toString()
                    )
                }
                if (matched.optString("expirationDate") != credential.optString("expirationDate")) {
                    return@withContext GranteeCredentialUpdateResult(
                        isUpdate = true,
                        credential = matched.toString()
                    )
                }
                GranteeCredentialUpdateResult(isUpdate = false, credential = matched.toString())
            } catch (e: Exception) {
                Log.e("DidSdk", "Error checking grantee credential update", e)
                GranteeCredentialUpdateResult(isUpdate = true, credential = null, fetchFailed = true)
            }
        }

    private suspend fun publishDid(
        did: String,
        privateKey: String,
        didDocument: String
    ): PublishDidResult =
        bridge.callAs(
            "publishDid",
            JSONObject().apply {
                put("did", did)
                put("privateKey", privateKey)
                put("didDocument", didDocument)
            }.toString(),
            PublishDidResult::class.java
        )

    private suspend fun generateAvatarVc(
        privateKey: String,
        did: String,
        selectedAvatar: DidAvatarCredential
    ): String =
        generateNftVc(
            privateKey = privateKey,
            ownerDid = did,
            credentialData = DidCredentialHelper.fromAvatarCredential(did, selectedAvatar)
        )

    private suspend fun generateNftVc(
        privateKey: String,
        ownerDid: String,
        credentialData: UnifiedNftCredentialData
    ): String =
        bridge.call(
            "generateVC",
            buildGenerateVcParams(privateKey, ownerDid, credentialData).toString()
        )

    internal fun buildGenerateAvatarVcParams(
        privateKey: String,
        did: String,
        selectedAvatar: DidAvatarCredential
    ): JSONObject =
        buildGenerateVcParams(
            privateKey = privateKey,
            ownerDid = did,
            credentialData = DidCredentialHelper.fromAvatarCredential(did, selectedAvatar)
        ).apply {
            put("id", selectedAvatar.credentialId)
        }

    internal fun buildGenerateVcParams(
        privateKey: String,
        ownerDid: String,
        credentialData: UnifiedNftCredentialData
    ): JSONObject {
        DidCredentialHelper.validateCredentialData(credentialData)
        return JSONObject().apply {
            put("id", DidCredentialHelper.generateVcId(credentialData))
            put("types", JSONArray(DidCredentialHelper.vcTypesFor(credentialData)))
            put("subject", DidCredentialHelper.buildNftSubject(credentialData))
            put("privateKey", privateKey)
            put("address", ownerDid.substringAfterLast(':'))
            put("did", ownerDid)
            put(
                "expirationDate",
                Instant.now().plusMillis(credentialData.expirationDurationMs).toString()
            )
            put("contextType", DidCredentialHelper.contextTypeFor(credentialData))
        }
    }

    private suspend fun resolveOwnerDidDocument(ownerDid: String): String? {
        core.getDidDocument(ownerDid)?.doc?.takeUnless { DidResolveUtils.isMissingDidDocument(it) }
            ?.let { return it }
        val chainDoc =
            runCatching {
                bridge.call(
                    "didResolve",
                    JSONObject().apply { put("did", ownerDid) }.toString()
                )
            }.getOrNull()
        return chainDoc?.takeUnless { DidResolveUtils.isMissingDidDocument(it) }
    }

    private suspend fun applyPreviousCid(
        json: JSONObject,
        did: String
    ) {
        val previousCid = readDidStatCid(did)
        if (previousCid.isBlank()) return
        val services = readServices(json)
        val updatedServices = JSONArray()
        for (index in 0 until services.length()) {
            val service = services.getJSONObject(index)
            if (service.optString("type") == "IpfsStorage") {
                val endpoint = service.optJSONObject("serviceEndpoint") ?: JSONObject()
                endpoint.put("previousCid", previousCid)
                updatedServices.put(
                    JSONObject().apply {
                        put("id", service.optString("id", "$did#ipfs-storage"))
                        put("type", "IpfsStorage")
                        put("serviceEndpoint", endpoint)
                    }
                )
            } else {
                updatedServices.put(service)
            }
        }
        json.put("service", updatedServices)
    }

    private suspend fun readDidStatCid(did: String): String =
        try {
            bridge.callAs(
                "didStat",
                JSONObject().apply { put("did", did) }.toString(),
                DidStatResult::class.java
            ).cid.orEmpty()
        } catch (_: Exception) {
            ""
        }

    private suspend fun resolveBaseDoc(
        did: String,
        currentDoc: String
    ): String? {
        if (currentDoc.isNotBlank()) return currentDoc

        val chainDoc =
            runCatching {
                bridge.call(
                    "didResolve",
                    JSONObject().apply { put("did", did) }.toString()
                )
            }.getOrNull()

        if (!chainDoc.isNullOrBlank() && !DidResolveUtils.isMissingDidDocument(chainDoc)) {
            return chainDoc
        }

        return core.getDidDocument(did)?.doc
    }

    private fun readServices(json: JSONObject): JSONArray =
        json.optJSONArray("service") ?: json.optJSONArray("services") ?: JSONArray()

    private fun readProfileField(
        doc: String,
        key: String
    ): String? {
        return try {
            val root = JSONObject(doc)
            val services = readServices(root)
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

    private fun readJsonArray(
        doc: String,
        key: String
    ): JSONArray =
        try {
            val root = JSONObject(doc)
            root.optJSONArray(key) ?: root.optJSONArray(
                when (key) {
                    "service" -> "services"
                    "verificationMethod" -> "verificationMethods"
                    else -> key
                }
            ) ?: JSONArray()
        } catch (_: Exception) {
            JSONArray()
        }

    private fun readString(
        doc: String,
        path: String
    ): String? = readElement(doc, path)?.takeIf { !it.isJsonNull }?.asString

    private fun readElement(
        doc: String,
        path: String
    ): JsonElement? {
        return try {
            val cleaned = path.removePrefix("$.")
            var current: JsonElement = JsonParser.parseString(doc)
            if (cleaned.isBlank()) return current
            for (part in cleaned.split('.')) {
                if (!current.isJsonObject) return null
                current = current.asJsonObject.get(part) ?: return null
            }
            current
        } catch (_: Exception) {
            null
        }
    }

    private fun readString(
        doc: String,
        path: String,
        defaultValue: String
    ): String = readString(doc, path) ?: defaultValue

    private fun DidEntity.toDid(did: String): Did {
        val created = readString(doc, "created").orEmpty()
        val updated = readString(doc, "updated").orEmpty()
        val verificationMethods =
            readJsonArray(doc, "verificationMethod").let { array ->
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        add(
                            VerificationMethod(
                                id = item.optString("id"),
                                controller = item.optString("controller"),
                                type = item.optString("type"),
                                publicKeyBase58 = item.optString("publicKeyBase58"),
                                isSelf = item.optString("controller").equals(did, true)
                            )
                        )
                    }
                }
            }

        return Did(
            id = did,
            created = formatUtc(created),
            updated = formatUtc(updated),
            verificationMethods = verificationMethods
        )
    }

    /**
     * Initial DID documents must always expose `credentials` as an empty array.
     * The JS `generateDidDoc` bridge may omit the field when no credentials are added.
     */
    internal fun ensureCredentialsArrayInDidDocument(didDocJson: String): String {
        val json = JSONObject(didDocJson)
        if (json.optJSONArray("credentials") == null) {
            json.put("credentials", JSONArray())
        }
        return json.toString()
    }

    private fun findCredentialById(
        credentials: JSONArray,
        id: String
    ): JSONObject? {
        for (i in 0 until credentials.length()) {
            val item = credentials.optJSONObject(i) ?: continue
            if (item.optString("id").equals(id, true)) return item
        }
        return null
    }

    private fun formatUtc(utc: String): String {
        if (utc.isBlank()) return ""
        return try {
            val instant = Instant.parse(utc)
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("Asia/Shanghai"))
                .format(instant)
        } catch (_: Exception) {
            utc
        }
    }

    private fun isSwtcDid(id: String): Boolean = id.startsWith("did:swtc")

    private fun isEthrDid(id: String): Boolean = id.startsWith("did:ethr")

    /**
     * Aligns with did_DApp `generateVCId`:
     * - EVM: `{ownerDID}#nft-{contractAddress}-{tokenId}-{granteeDID}`
     * - SWTC: `{ownerDID}#nft-{tokenName}-{nftIssuer}-{tokenId}-{granteeDID}`
     */
    internal fun buildAvatarCredentialId(
        ownerDid: String,
        asset: DidAvatarAsset,
        granteeDid: String = ownerDid
    ): String =
        if (asset.isSwtc) {
            val tokenNameClean = asset.tokenName.orEmpty().replace("\\s+".toRegex(), "")
            "$ownerDid#nft-$tokenNameClean-${asset.issuer.orEmpty()}-${asset.tokenId}-$granteeDid"
        } else {
            val checksumContract =
                asset.contract?.let { runCatching { ChecksumUtils.toChecksumAddress(it) }.getOrNull() }
                    .orEmpty()
            "$ownerDid#nft-$checksumContract-${asset.tokenId}-$granteeDid"
        }

    private fun buildAvatarCredential(
        ownerDid: String,
        asset: DidAvatarAsset
    ): DidAvatarCredential {
        val credentialId = buildAvatarCredentialId(ownerDid, asset)
        return if (asset.isSwtc) {
            DidAvatarCredential(
                credentialId = credentialId,
                image = asset.image,
                name = asset.name,
                contract = asset.issuer,
                tokenId = asset.tokenId,
                issuer = asset.issuer,
                tokenName = asset.tokenName,
                chainId = null,
                isSwtc = true,
                ownerDid = ownerDid
            )
        } else {
            val checksumContract =
                asset.contract?.let { runCatching { ChecksumUtils.toChecksumAddress(it) }.getOrNull() }
                    .orEmpty()
            DidAvatarCredential(
                credentialId = credentialId,
                image = asset.image,
                name = asset.name,
                contract = checksumContract,
                tokenId = asset.tokenId,
                issuer = checksumContract,
                tokenName = asset.tokenName,
                chainId = asset.chainId,
                isSwtc = false,
                ownerDid = ownerDid
            )
        }
    }

    companion object {
        private const val SWTC_NFT_STANDARD = "jingtumnft"
        private const val EVM_NFT_STANDARD = "erc-721"

        fun create(
            context: Context,
            avatarResolver: IDidAvatarResolver? = null,
            avatarCredentialSource: IDidAvatarCredentialSource? = null,
            databaseName: String = DidRoomDatabase.DEFAULT_DATABASE_NAME,
            ethTokenUriResolver: EthTokenUriResolver? = null
        ): DidSdk {
            val runtime = AndroidDidWebRuntime(context)
            val store = RoomDidStore(DidRoomDatabase.getInstance(context, databaseName).didDao())
            val core = DidCoreService(store, runtime)
            val nftSdk = NftSdk.create(context, ethTokenUriResolver = ethTokenUriResolver)
            return DidSdk(
                bridge = runtime,
                core = core,
                avatarResolver = avatarResolver,
                avatarCredentialSource = avatarCredentialSource,
                nftSdk = nftSdk
            )
        }

        fun create(
            bridge: IDidBridge,
            store: IDidStore,
            resolver: IDidResolver,
            avatarResolver: IDidAvatarResolver? = null,
            avatarCredentialSource: IDidAvatarCredentialSource? = null
        ): DidSdk {
            val core = DidCoreService(store, resolver)
            return DidSdk(
                bridge = bridge,
                core = core,
                avatarResolver = avatarResolver,
                avatarCredentialSource = avatarCredentialSource,
                nftSdk = null
            )
        }
    }

    private fun NftSdkNft.toDidNft(): Nft =
        Nft(
            contract = contract,
            tokenId = tokenId,
            name = name,
            uri = uri,
            image = image,
            hasLocal = hasLocal,
            issuanceDate = issuanceDate,
            chainId = chainId
        )

    private fun AvatarCandidate.toDidAvatarAsset(): DidAvatarAsset =
        DidAvatarAsset(
            image = image,
            name = name,
            contract = contract,
            tokenId = tokenId,
            issuer = issuer,
            tokenName = tokenName,
            chainId = chainId,
            isSwtc = isSwtc
        )
}
