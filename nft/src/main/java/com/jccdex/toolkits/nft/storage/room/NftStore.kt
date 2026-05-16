package com.jccdex.toolkits.nft.storage.room

import android.content.Context
import com.jccdex.toolkits.nft.model.AvatarCandidate
import com.jccdex.toolkits.nft.model.ChainType
import com.jccdex.toolkits.nft.model.EthTokenUriResolver
import com.jccdex.toolkits.nft.model.Nft
import com.jccdex.toolkits.nft.model.WalletAccount
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class NftStore(
    private val dao: NftDao,
    private val ethTokenUriResolver: EthTokenUriResolver? = null
) {
    fun observeSwtcNfts(ownerAddress: String): Flow<List<SwtcNftEntity>> = dao.observeSwtcNfts(ownerAddress)

    fun observeEvmNftItems(
        chainId: String,
        ownerAddress: String,
        contractAddress: String
    ): Flow<List<EvmNftItemEntity>> = dao.observeEvmNftItems(chainId, ownerAddress.lowercase(), contractAddress.lowercase())

    fun observeAllEvmNftItems(
        chainId: String,
        ownerAddress: String
    ): Flow<List<EvmNftItemEntity>> = dao.observeAllEvmNftItems(chainId, ownerAddress.lowercase())

    suspend fun getNftMeta(
        contract: String,
        tokenId: String
    ): NftMetaEntity? = dao.getNftMeta(contract, tokenId)

    suspend fun upsertNftMeta(entity: NftMetaEntity) {
        dao.upsertNftMeta(entity)
    }

    suspend fun upsertSwtcNfts(entities: List<SwtcNftEntity>) {
        if (entities.isNotEmpty()) dao.upsertSwtcNfts(entities)
    }

    suspend fun upsertEvmNftItems(entities: List<EvmNftItemEntity>) {
        if (entities.isNotEmpty()) dao.upsertEvmNftItems(entities)
    }

    suspend fun getSwtcNftByIssuerAndTokenId(
        issuer: String,
        tokenId: String
    ): SwtcNftEntity? = dao.getSwtcNftByIssuerAndTokenId(issuer, tokenId)

    suspend fun getSwtcNftByTokenId(
        ownerAddress: String,
        tokenId: String
    ): SwtcNftEntity? = dao.getSwtcNftByTokenId(ownerAddress, tokenId)

    suspend fun deleteSwtcNftsByOwner(ownerAddress: String) {
        dao.deleteSwtcNftsByOwner(ownerAddress)
    }

    suspend fun getEvmNftItemByContractAndTokenId(
        chainId: String,
        contractAddress: String,
        tokenId: String
    ): EvmNftItemEntity? = dao.getEvmNftItemByContractAndTokenId(chainId, contractAddress.lowercase(), tokenId)

    suspend fun getEvmNftItem(
        chainId: String,
        ownerAddress: String,
        contractAddress: String,
        tokenId: String
    ): EvmNftItemEntity? = dao.getEvmNftItem(chainId, ownerAddress.lowercase(), contractAddress.lowercase(), tokenId)

    suspend fun deleteEvmNftItemsByCollection(
        chainId: String,
        ownerAddress: String,
        contractAddress: String
    ) {
        dao.deleteEvmNftItemsByCollection(chainId, ownerAddress.lowercase(), contractAddress.lowercase())
    }

    suspend fun getAvatarCandidates(account: WalletAccount): List<AvatarCandidate> {
        return if (account.chain == ChainType.SWTC) {
            observeSwtcNfts(account.address)
                .map { items ->
                    items.map { entity ->
                        val tokenName = entity.fundCodeName.ifBlank { entity.fundCode }
                        AvatarCandidate(
                            image = entity.image,
                            name = entity.name ?: tokenName,
                            contract = entity.issuer,
                            tokenId = entity.tokenId,
                            issuer = entity.issuer,
                            tokenName = tokenName,
                            chainId = null,
                            isSwtc = true
                        )
                    }
                }.first()
        } else {
            val chainId = account.chain.evmChainId ?: return emptyList()
            val chainIdHex = "0x${chainId.toString(16)}"
            observeAllEvmNftItems(chainIdHex, account.address)
                .map { items ->
                    items.map { entity ->
                        AvatarCandidate(
                            image = entity.imageUrl,
                            name = entity.title ?: "",
                            contract = entity.contractAddress,
                            tokenId = entity.tokenId,
                            issuer = null,
                            tokenName = entity.title,
                            chainId = chainId,
                            isSwtc = false
                        )
                    }
                }.first()
        }
    }

    suspend fun resolveSwtcAvatar(vc: String): Nft? {
        val tokenId = parseString(vc, "$.credentialSubject.tokenId").orEmpty()
        val nftIssuer = parseString(vc, "$.credentialSubject.nftIssuer").orEmpty()
        val tokenName = parseString(vc, "$.credentialSubject.tokenName").orEmpty()
        val issuance = parseString(vc, "$.issuanceDate").orEmpty()
        if (tokenId.isBlank() || nftIssuer.isBlank()) return null

        val localMeta = getNftMeta(nftIssuer, tokenId)
        if (localMeta != null) {
            return Nft(
                contract = nftIssuer,
                tokenId = tokenId,
                name = localMeta.name ?: tokenName,
                uri = localMeta.tokenUri.orEmpty(),
                image = localMeta.image,
                hasLocal = true,
                issuanceDate = issuance,
                chainId = null
            )
        }

        val swtcNft = getSwtcNftByIssuerAndTokenId(nftIssuer, tokenId)
        return Nft(
            contract = nftIssuer,
            tokenId = tokenId,
            name = swtcNft?.name ?: tokenName,
            uri = swtcNft?.metadataUri.orEmpty(),
            image = swtcNft?.image,
            hasLocal = swtcNft?.image != null,
            issuanceDate = issuance,
            chainId = null
        )
    }

    suspend fun resolveEthrAvatar(vc: String): Nft? {
        val tokenId = parseString(vc, "$.credentialSubject.tokenId").orEmpty()
        val contract = parseString(vc, "$.credentialSubject.contractAddress").orEmpty()
        val chainId = parseString(vc, "$.credentialSubject.chainId")?.toLongOrNull() ?: 0L
        val issuance = parseString(vc, "$.issuanceDate").orEmpty()
        if (tokenId.isBlank() || contract.isBlank()) return null

        val resolvedTokenUri =
            sanitizeUri(
                ethTokenUriResolver
                    ?.resolveEthrTokenUri(contract, tokenId, chainId)
            )
        val localMeta = getNftMeta(contract, tokenId)
        if (localMeta != null) {
            return Nft(
                contract = contract,
                tokenId = tokenId,
                name = localMeta.name.orEmpty(),
                uri = sanitizeUri(localMeta.tokenUri).ifBlank { resolvedTokenUri },
                image = localMeta.image,
                hasLocal = true,
                issuanceDate = issuance,
                chainId = chainId
            )
        }

        val evmItem = getEvmNftItemByContractAndTokenId("0x${chainId.toString(16)}", contract, tokenId)
        return Nft(
            contract = contract,
            tokenId = tokenId,
            name = evmItem?.title.orEmpty(),
            uri = resolvedTokenUri.ifBlank { sanitizeUri(evmItem?.metadata) },
            image = evmItem?.imageUrl,
            hasLocal = evmItem?.imageUrl != null,
            issuanceDate = issuance,
            chainId = chainId
        )
    }

    suspend fun fetchAndCacheNftMeta(
        contract: String,
        tokenId: String,
        tokenUri: String
    ): NftMetaEntity? =
        withContext(Dispatchers.IO) {
            if (tokenUri.isBlank()) return@withContext null
            try {
                val content = fetchJson(tokenUri) ?: return@withContext null
                val nameVal = content.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                val imageVal =
                    content.get("image")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                val entity =
                    NftMetaEntity(
                        contract = contract,
                        tokenId = tokenId,
                        name = nameVal,
                        image = imageVal,
                        tokenUri = tokenUri,
                        fullContent = content.toString(),
                        updatedAt = System.currentTimeMillis()
                    )
                val existing = getNftMeta(contract, tokenId)
                if (existing != null) {
                    dao.upsertNftMeta(entity.copy(id = existing.id))
                } else {
                    dao.upsertNftMeta(entity)
                }
                getNftMeta(contract, tokenId)
            } catch (_: Exception) {
                null
            }
        }

    private fun parseString(vc: String, path: String): String? {
        return try {
            val cleaned = path.removePrefix("$.")
            var current = JsonParser.parseString(vc)
            if (cleaned.isBlank()) {
                if (current.isJsonNull) null else current.asString
            } else {
                for (part in cleaned.split('.')) {
                    if (!current.isJsonObject) return null
                    current = current.asJsonObject.get(part) ?: return null
                }
                if (current.isJsonNull) null else current.asString
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeUri(uri: String?): String =
        uri
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.looksLikeJson() }
            .orEmpty()

    private fun String.looksLikeJson(): Boolean {
        val trimmed = trim()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    private suspend fun fetchJson(url: String): JsonObject? =
        withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = true
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299 || body.isBlank()) return@withContext null
                JsonParser.parseString(body).asJsonObject
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        fun getInstance(
            context: Context,
            databaseName: String = NftRoomDatabase.DEFAULT_DATABASE_NAME,
            ethTokenUriResolver: EthTokenUriResolver? = null
        ): NftStore =
            NftStore(
                NftRoomDatabase.getInstance(context, databaseName).nftDao(),
                ethTokenUriResolver
            )
    }
}
