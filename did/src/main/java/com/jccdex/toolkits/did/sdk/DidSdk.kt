package com.jccdex.toolkits.did

import android.content.Context
import android.util.Log
import com.jccdex.toolkits.did.model.ChainType
import com.jccdex.toolkits.did.model.DidAvatarCredential
import com.jccdex.toolkits.did.model.Did
import com.jccdex.toolkits.did.model.DidEntity
import com.jccdex.toolkits.did.model.DidStatResult
import com.jccdex.toolkits.did.model.GenerateBase58PKResult
import com.jccdex.toolkits.did.model.Nft
import com.jccdex.toolkits.did.model.Profile
import com.jccdex.toolkits.did.model.ProfileVC
import com.jccdex.toolkits.did.model.PublishDidResult
import com.jccdex.toolkits.did.model.VerificationMethod
import com.jccdex.toolkits.did.model.WalletAccount
import com.jccdex.toolkits.did.port.DidAvatarAsset
import com.jccdex.toolkits.did.port.DidAvatarCredentialSource
import com.jccdex.toolkits.did.port.DidAvatarResolver
import com.jccdex.toolkits.did.port.DidBridge
import com.jccdex.toolkits.did.sdk.AndroidDidWebRuntime
import com.jccdex.toolkits.did.service.DidCoreService
import com.jccdex.toolkits.did.service.DidResolver
import com.jccdex.toolkits.did.storage.room.DidRoomDatabase
import com.jccdex.toolkits.did.storage.room.RoomDidStore
import com.jccdex.toolkits.did.store.DidStore
import com.jccdex.toolkits.did.util.ChecksumUtils
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DidSdk internal constructor(
    private val bridge: DidBridge,
    private val core: DidCoreService,
    private val avatarResolver: DidAvatarResolver? = null,
    private val avatarCredentialSource: DidAvatarCredentialSource? = null,
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
                        nft =
                            when {
                                isSwtcDid(did) -> generateSwtcNft(vc)
                                isEthrDid(did) -> generateEthrNft(vc)
                                else -> null
                            }
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

    suspend fun generateSwtcNft(vc: String): Nft? {
        avatarResolver?.resolveSwtcAvatar(vc)?.let { return it }
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
        return buildEthrNft(vc)
    }

    suspend fun getAvatarNftCredentials(account: WalletAccount): List<DidAvatarCredential> {
        val ownerDid = toDid(account)
        if (ownerDid.isBlank()) return emptyList()

        val source = avatarCredentialSource ?: return emptyList()
        return source.getAvatarCandidates(account).map { asset ->
            buildAvatarCredential(ownerDid, asset)
        }
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
                                                if (previousCid.isNotBlank()) put("previousCid", previousCid)
                                            }
                                        )
                                    }
                                )
                            }
                        )
                        put("credentials", JSONArray())
                        put("did", did)
                    }

                val didDocJson = bridge.call("generateDidDoc", didDoc.toString())
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
                                            put("preferredAvatar", readProfileField(doc, "preferredAvatar").orEmpty())
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
                                            put("nickname", readProfileField(doc, "nickname").orEmpty())
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
        bridge.call(
            "generateVC",
            buildGenerateAvatarVcParams(privateKey, did, selectedAvatar).toString()
        )

    internal fun buildGenerateAvatarVcParams(
        privateKey: String,
        did: String,
        selectedAvatar: DidAvatarCredential
    ): JSONObject =
        JSONObject().apply {
            put("id", selectedAvatar.credentialId)
            put("types", JSONArray(listOf("VerifiableCredential", "NFTOwnership")))
            put("subject", buildAvatarSubject(did, selectedAvatar))
            put("privateKey", privateKey)
            put("address", did.substringAfterLast(':'))
            put("did", did)
            put("expirationDate", Instant.now().plusSeconds(365L * 24 * 60 * 60).toString())
        }

    private fun buildAvatarSubject(
        did: String,
        selectedAvatar: DidAvatarCredential
    ): JSONObject {
        return if (selectedAvatar.isSwtc) {
            JSONObject().apply {
                put("id", did)
                put("owner", did)
                put("chainId", 315)
                put("nftIssuer", selectedAvatar.issuer.orEmpty())
                put("tokenName", selectedAvatar.tokenName.orEmpty())
                put("tokenId", selectedAvatar.tokenId)
                put("status", "Active")
                put("standard", "jingtumNFT")
            }
        } else {
            val checksumOwner = runCatching { ChecksumUtils.toChecksumAddress(did.substringAfterLast(':')) }.getOrDefault("")
            val checksumContract = selectedAvatar.contract?.let { runCatching { ChecksumUtils.toChecksumAddress(it) }.getOrNull() }.orEmpty()
            JSONObject().apply {
                put("id", did)
                put("owner", checksumOwner)
                put("chainId", selectedAvatar.chainId ?: 1)
                put("contractAddress", checksumContract)
                put("tokenId", selectedAvatar.tokenId)
                put("status", "Active")
                put("standard", "ERC-721")
            }
        }
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

        if (!chainDoc.isNullOrBlank() && chainDoc != "{}") return chainDoc

        return core.getDidDocument(did)?.doc
    }

    private fun readServices(json: JSONObject): JSONArray = json.optJSONArray("service") ?: json.optJSONArray("services") ?: JSONArray()

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
    ): JSONArray = try {
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

    private fun buildAvatarCredential(
        ownerDid: String,
        asset: DidAvatarAsset
    ): DidAvatarCredential {
        return if (asset.isSwtc) {
            val tokenNameClean = asset.tokenName.orEmpty().replace("\\s+".toRegex(), "")
            DidAvatarCredential(
                credentialId = "$ownerDid#nft-$tokenNameClean-${asset.issuer.orEmpty()}-${asset.tokenId}",
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
                asset.contract?.let { runCatching { ChecksumUtils.toChecksumAddress(it) }.getOrNull() }.orEmpty()
            DidAvatarCredential(
                credentialId = "$ownerDid#nft-$checksumContract-${asset.tokenId}",
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
        fun create(
            context: Context,
            avatarResolver: DidAvatarResolver? = null,
            avatarCredentialSource: DidAvatarCredentialSource? = null,
            databaseName: String = DidRoomDatabase.DEFAULT_DATABASE_NAME
        ): DidSdk {
            val runtime = AndroidDidWebRuntime(context)
            val store = RoomDidStore(DidRoomDatabase.getInstance(context, databaseName).didDao())
            val core = DidCoreService(store, runtime)
            return DidSdk(
                bridge = runtime,
                core = core,
                avatarResolver = avatarResolver,
                avatarCredentialSource = avatarCredentialSource
            )
        }

        fun create(
            bridge: DidBridge,
            store: DidStore,
            resolver: DidResolver,
            avatarResolver: DidAvatarResolver? = null,
            avatarCredentialSource: DidAvatarCredentialSource? = null
        ): DidSdk {
            val core = DidCoreService(store, resolver)
            return DidSdk(
                bridge = bridge,
                core = core,
                avatarResolver = avatarResolver,
                avatarCredentialSource = avatarCredentialSource
            )
        }
    }
}
