package com.jccdex.toolkits.nft.storage.room

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jccdex.toolkits.core.json.JsonPath
import com.jccdex.toolkits.core.model.toEvmChainIdHex
import com.jccdex.toolkits.core.net.HttpFetcher
import com.jccdex.toolkits.core.net.HttpResult
import com.jccdex.toolkits.core.net.RedirectPolicy
import com.jccdex.toolkits.nft.model.AvatarCandidate
import com.jccdex.toolkits.nft.model.ChainType
import com.jccdex.toolkits.nft.model.CredentialImageRequest
import com.jccdex.toolkits.nft.model.EthTokenUriResolver
import com.jccdex.toolkits.nft.model.Nft
import com.jccdex.toolkits.nft.model.NftMetadataFields
import com.jccdex.toolkits.nft.model.ResolvedCredentialImage
import com.jccdex.toolkits.nft.model.WalletAccount
import com.jccdex.toolkits.nft.remote.MAX_HTTP_RESPONSE_CHARS
import com.jccdex.toolkits.nft.remote.SwtcChainNftClient
import com.jccdex.toolkits.nft.remote.extractMetadataFields
import com.jccdex.toolkits.nft.remote.extractMetadataImageUrl
import com.jccdex.toolkits.nft.remote.fetchMetadataImage
import com.jccdex.toolkits.nft.remote.isLoadableRemoteAssetUrl
import com.jccdex.toolkits.nft.remote.normalizeRemoteAssetUrl
import com.jccdex.toolkits.nft.remote.resolveRemoteImageUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale
import com.jccdex.toolkits.nft.remote.extractSwtcMetadataUri as parseSwtcMetadataUri

class NftStore(
    private val dao: NftDao,
    private val ethTokenUriResolver: EthTokenUriResolver? = null,
    private val swtcChainNftClient: SwtcChainNftClient = SwtcChainNftClient.create()
) {
    // C-2: HTTP via core HttpFetcher; SSRF off for NFT asset hosts (http tokenUri / gateways).
    private val httpFetcher =
        HttpFetcher(
            maxResponseBytes = MAX_HTTP_RESPONSE_CHARS,
            httpsOnly = false,
            redirectPolicy = RedirectPolicy.NONE,
            ssrfCheck = null
        )

    fun observeSwtcNfts(ownerAddress: String): Flow<List<SwtcNftEntity>> = dao.observeSwtcNfts(ownerAddress)

    fun observeEvmNftItems(
        chainId: String,
        ownerAddress: String,
        contractAddress: String
    ): Flow<List<EvmNftItemEntity>> {
        val normalized = normalizeChainIdHex(chainId) ?: return flowOf(emptyList())
        return dao.observeEvmNftItems(
            normalized,
            ownerAddress.lowercase(Locale.ROOT),
            contractAddress.lowercase(Locale.ROOT)
        )
    }

    fun observeAllEvmNftItems(
        chainId: String,
        ownerAddress: String
    ): Flow<List<EvmNftItemEntity>> {
        val normalized = normalizeChainIdHex(chainId) ?: return flowOf(emptyList())
        return dao.observeAllEvmNftItems(normalized, ownerAddress.lowercase(Locale.ROOT))
    }

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
        observeSwtcNfts(ownerAddress).first().forEach { entity ->
            preserveSwtcEntityAsMeta(entity)
        }
        dao.deleteSwtcNftsByOwner(ownerAddress)
    }

    /**
     * Ensures SWTC NFT metadata for a credential exists in [nft_meta], fetching from chain by
     * [tokenId] when the NFT is not in the local ownership cache.
     */
    suspend fun ensureSwtcCredentialMetadata(vc: String) {
        val tokenId = parseString(vc, "$.credentialSubject.tokenId").orEmpty()
        val nftIssuer = parseString(vc, "$.credentialSubject.nftIssuer").orEmpty()
        if (tokenId.isBlank() || nftIssuer.isBlank()) {
            return
        }
        resolveAndCacheSwtcNftMeta(nftIssuer, tokenId)
    }

    suspend fun getEvmNftItemByContractAndTokenId(
        chainId: String,
        contractAddress: String,
        tokenId: String
    ): EvmNftItemEntity? {
        val normalized = normalizeChainIdHex(chainId) ?: return null
        return dao.getEvmNftItemByContractAndTokenId(normalized, contractAddress.lowercase(Locale.ROOT), tokenId)
    }

    suspend fun getEvmNftItem(
        chainId: String,
        ownerAddress: String,
        contractAddress: String,
        tokenId: String
    ): EvmNftItemEntity? {
        val normalized = normalizeChainIdHex(chainId) ?: return null
        return dao.getEvmNftItem(
            normalized,
            ownerAddress.lowercase(Locale.ROOT),
            contractAddress.lowercase(Locale.ROOT),
            tokenId
        )
    }

    suspend fun deleteEvmNftItemsByCollection(
        chainId: String,
        ownerAddress: String,
        contractAddress: String
    ) {
        val normalized = normalizeChainIdHex(chainId) ?: return
        dao.deleteEvmNftItemsByCollection(
            normalized,
            ownerAddress.lowercase(Locale.ROOT),
            contractAddress.lowercase(Locale.ROOT)
        )
    }

    /** Normalizes a chainId to lowercase hex (`0x1`), or null if blank/invalid (M-13N). */
    private fun normalizeChainIdHex(chainId: String): String? {
        val trimmed = chainId.trim()
        if (trimmed.isBlank()) return null
        return try {
            val value =
                if (trimmed.startsWith("0x", ignoreCase = true)) {
                    trimmed.drop(2).toLong(16)
                } else {
                    trimmed.toLong()
                }
            value.toEvmChainIdHex()
        } catch (_: NumberFormatException) {
            null
        }
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
            val chainIdHex = chainId.toEvmChainIdHex()
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

        getNftMeta(nftIssuer, tokenId)?.let { localMeta ->
            buildSwtcNftFromMeta(
                nftIssuer = nftIssuer,
                tokenId = tokenId,
                tokenName = tokenName,
                issuance = issuance,
                meta = localMeta
            )?.let { return it }
        }

        getSwtcNftByIssuerAndTokenId(nftIssuer, tokenId)?.let { swtcNft ->
            val resolvedUri = sanitizeUri(normalizeRemoteAssetUrl(swtcNft.metadataUri))
            if (!swtcNft.image.isNullOrBlank() || resolvedUri.isNotBlank()) {
                val resolvedImage = resolveRemoteImageUrl(swtcNft.image, resolvedUri)
                return Nft(
                    contract = nftIssuer,
                    tokenId = tokenId,
                    name = swtcNft.name ?: tokenName,
                    uri = resolvedUri,
                    image = resolvedImage,
                    hasLocal = swtcNft.image != null,
                    issuanceDate = issuance,
                    chainId = null
                )
            }
        }

        resolveAndCacheSwtcNftMeta(nftIssuer, tokenId)?.let { cachedMeta ->
            return buildSwtcNftFromMeta(
                nftIssuer = nftIssuer,
                tokenId = tokenId,
                tokenName = tokenName,
                issuance = issuance,
                meta = cachedMeta
            )
        }

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

    suspend fun resolveEthrAvatar(vc: String): Nft? {
        val tokenId = parseString(vc, "$.credentialSubject.tokenId").orEmpty()
        val contract = parseString(vc, "$.credentialSubject.contractAddress").orEmpty()
        val chainId = JsonPath.readEvmChainIdLong(vc) ?: return null
        val issuance = parseString(vc, "$.issuanceDate").orEmpty()
        if (tokenId.isBlank() || contract.isBlank()) return null

        val resolvedTokenUri =
            sanitizeUri(
                normalizeRemoteAssetUrl(
                    ethTokenUriResolver
                        ?.resolveEthrTokenUri(contract, tokenId, chainId)
                )
            )
        val localMeta = getNftMeta(contract, tokenId)
        if (localMeta != null) {
            val localTokenUri = sanitizeUri(normalizeRemoteAssetUrl(localMeta.tokenUri))
            val metadataUri = localTokenUri.ifBlank { resolvedTokenUri }
            val image = resolveEthrImage(contract, tokenId, localMeta.image, metadataUri)
            return Nft(
                contract = contract,
                tokenId = tokenId,
                name = localMeta.name.orEmpty(),
                uri = metadataUri,
                image = image,
                hasLocal = true,
                issuanceDate = issuance,
                chainId = chainId
            )
        }

        val evmItem = getEvmNftItemByContractAndTokenId(chainId.toEvmChainIdHex(), contract, tokenId)
        val fallbackMetadataUri = sanitizeUri(normalizeRemoteAssetUrl(evmItem?.metadata))
        val metadataUri = resolvedTokenUri.ifBlank { fallbackMetadataUri }

        if (evmItem == null && metadataUri.isBlank()) {
            return null
        }

        val image = resolveEthrImage(contract, tokenId, evmItem?.imageUrl, metadataUri)
        return Nft(
            contract = contract,
            tokenId = tokenId,
            name = evmItem?.title.orEmpty(),
            uri = metadataUri,
            image = image,
            hasLocal = evmItem?.imageUrl != null,
            issuanceDate = issuance,
            chainId = chainId
        )
    }

    private suspend fun resolveEthrImage(
        contract: String,
        tokenId: String,
        imageUrl: String?,
        metadataUri: String
    ): String? {
        if (metadataUri.isBlank()) {
            return resolveRemoteImageUrl(imageUrl, null)
        }
        resolveRemoteImageUrl(imageUrl, metadataUri)?.let { return it }
        fetchAndCacheNftMeta(contract, tokenId, metadataUri)?.image?.let { cachedImage ->
            resolveRemoteImageUrl(cachedImage, metadataUri)?.let { return it }
        }
        return null
    }

    suspend fun fetchAndCacheNftMeta(
        contract: String,
        tokenId: String,
        tokenUri: String
    ): NftMetaEntity? =
        withContext(Dispatchers.IO) {
            if (tokenUri.isBlank()) return@withContext null
            try {
                // L-R3: normalize ipfs:// (etc.) to the final HTTP URL before fetchJson.
                val requestUri = normalizeRemoteAssetUrl(tokenUri) ?: tokenUri
                val content = fetchJson(requestUri) ?: return@withContext null
                val nameVal = content.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                val imageVal = extractMetadataImageUrl(content, requestUri)
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    suspend fun resolveCredentialImage(
        imageUrl: String?,
        metadataUri: String?
    ): String? = resolveRemoteImageUrl(imageUrl, metadataUri)

    suspend fun fetchResolvedMetadataImage(metadataUrl: String): String? = fetchMetadataImage(metadataUrl)

    suspend fun resolveCredentialImage(request: CredentialImageRequest): ResolvedCredentialImage? {
        val resolvedUrl =
            resolveCredentialImage(request.imageUrl, request.metadataUri)
                ?: resolveEvmCredentialImageFromChain(
                    chainId = request.chainId,
                    contract = request.contractAddress,
                    tokenId = request.tokenId
                )
                ?: return null
        return ResolvedCredentialImage(
            url = resolvedUrl,
            cacheKey =
                buildCredentialAssetKey(
                    chainId = request.chainId,
                    contractAddress = request.contractAddress,
                    tokenId = request.tokenId,
                    imageUrl = request.imageUrl,
                    metadataUri = request.metadataUri,
                    resolvedUrl = resolvedUrl
                )
        )
    }

    private suspend fun resolveEvmCredentialImageFromChain(
        chainId: Long?,
        contract: String?,
        tokenId: String?
    ): String? {
        val normalizedChainId = chainId?.takeIf { it > 0 } ?: return null
        val normalizedContract = contract?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val normalizedTokenId = tokenId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val metadataUri =
            sanitizeUri(
                normalizeRemoteAssetUrl(
                    ethTokenUriResolver?.resolveEthrTokenUri(
                        normalizedContract,
                        normalizedTokenId,
                        normalizedChainId
                    )
                )
            ).takeIf { it.isNotBlank() }
                ?: return null
        return resolveEthrImage(normalizedContract, normalizedTokenId, null, metadataUri)
    }

    suspend fun resolveCredentialImages(requests: List<CredentialImageRequest>): List<ResolvedCredentialImage?> {
        if (requests.isEmpty()) {
            return emptyList()
        }
        val resolvedByKey = LinkedHashMap<String, ResolvedCredentialImage?>()
        return requests.map { request ->
            val dedupeKey = buildCredentialResolutionKey(request)
            resolvedByKey.getOrPut(dedupeKey) { resolveCredentialImage(request) }
        }
    }

    fun normalizeAssetUrl(
        rawUrl: String?,
        baseUrl: String? = null
    ): String? = normalizeRemoteAssetUrl(rawUrl, baseUrl)

    fun extractResolvedMetadataImageUrl(
        metadataBody: String,
        metadataUri: String
    ): String? = extractMetadataImageUrl(metadataBody, metadataUri)

    fun isSupportedRemoteAssetUrl(url: String?): Boolean = isLoadableRemoteAssetUrl(url)

    fun extractSwtcMetadataUri(tokenInfosPayload: String?): String? = parseSwtcMetadataUri(tokenInfosPayload)

    suspend fun fetchMetadataFields(metadataUri: String): NftMetadataFields {
        val normalizedUri = normalizeRemoteAssetUrl(metadataUri) ?: return NftMetadataFields(null, null, null)
        val body =
            runCatching { fetchText(normalizedUri) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
                ?: return NftMetadataFields(null, null, null)
        return extractMetadataFields(body, normalizedUri)
    }

    private suspend fun resolveAndCacheSwtcNftMeta(
        nftIssuer: String,
        tokenId: String
    ): NftMetaEntity? {
        val existing = getNftMeta(nftIssuer, tokenId)
        if (!existing?.image.isNullOrBlank()) {
            return existing
        }

        val tokenUri =
            existing?.tokenUri?.takeIf { it.isNotBlank() }
                ?: swtcChainNftClient.fetchMetadataUri(tokenId)
                ?: return existing

        return fetchAndCacheNftMeta(nftIssuer, tokenId, tokenUri) ?: existing
    }

    private suspend fun preserveSwtcEntityAsMeta(entity: SwtcNftEntity) {
        val metadataUri = entity.metadataUri?.takeIf { it.isNotBlank() } ?: return
        val existing = getNftMeta(entity.issuer, entity.tokenId)
        if (!existing?.image.isNullOrBlank()) {
            return
        }
        dao.upsertNftMeta(
            NftMetaEntity(
                id = existing?.id ?: 0,
                contract = entity.issuer,
                tokenId = entity.tokenId,
                name = entity.name?.takeIf { it.isNotBlank() } ?: existing?.name,
                image = entity.image?.takeIf { it.isNotBlank() } ?: existing?.image,
                tokenUri = metadataUri,
                fullContent = existing?.fullContent,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun buildSwtcNftFromMeta(
        nftIssuer: String,
        tokenId: String,
        tokenName: String,
        issuance: String,
        meta: NftMetaEntity
    ): Nft? {
        val resolvedUri = sanitizeUri(normalizeRemoteAssetUrl(meta.tokenUri))
        val image = resolveRemoteImageUrl(meta.image, resolvedUri)
        if (image.isNullOrBlank() && resolvedUri.isBlank()) {
            return null
        }
        return Nft(
            contract = nftIssuer,
            tokenId = tokenId,
            name = meta.name ?: tokenName,
            uri = resolvedUri,
            image = image,
            hasLocal = !meta.image.isNullOrBlank(),
            issuanceDate = issuance,
            chainId = null
        )
    }

    private fun parseString(
        vc: String,
        path: String
    ): String? =
        try {
            JsonPath.readString(vc, path)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
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

    private fun buildCredentialResolutionKey(request: CredentialImageRequest): String =
        listOf(
            request.chainId?.toString().orEmpty(),
            request.contractAddress?.trim()?.lowercase(Locale.ROOT).orEmpty(),
            request.tokenId?.trim().orEmpty(),
            normalizeRemoteAssetUrl(request.metadataUri)?.trim().orEmpty(),
            normalizeRemoteAssetUrl(request.imageUrl, request.metadataUri)?.trim().orEmpty()
        ).joinToString("|")

    private fun buildCredentialAssetKey(
        chainId: Long?,
        contractAddress: String?,
        tokenId: String?,
        imageUrl: String?,
        metadataUri: String?,
        resolvedUrl: String
    ): String =
        when {
            resolvedUrl.isNotBlank() ->
                "image:${resolvedUrl.trim()}"

            !contractAddress.isNullOrBlank() && !tokenId.isNullOrBlank() ->
                listOf(
                    "nft",
                    chainId?.toString().orEmpty().ifBlank { "unknown" },
                    contractAddress.trim().lowercase(Locale.ROOT),
                    tokenId.trim()
                ).joinToString(":")

            !metadataUri.isNullOrBlank() ->
                "metadata:${normalizeRemoteAssetUrl(metadataUri)?.trim().orEmpty().ifBlank { metadataUri.trim() }}"

            !imageUrl.isNullOrBlank() ->
                "image:${normalizeRemoteAssetUrl(imageUrl, metadataUri)?.trim().orEmpty().ifBlank { imageUrl.trim() }}"

            else -> "image:${resolvedUrl.trim()}"
        }

    private suspend fun fetchJson(url: String): JsonObject? =
        withContext(Dispatchers.IO) {
            when (val result = httpFetcher.get(url)) {
                is HttpResult.Success ->
                    result.value
                        .takeIf { it.isNotBlank() }
                        ?.let { JsonParser.parseString(it).asJsonObject }
                is HttpResult.Failure -> null
            }
        }

    private suspend fun fetchText(url: String): String? =
        withContext(Dispatchers.IO) {
            when (val result = httpFetcher.get(url)) {
                is HttpResult.Success -> result.value.takeIf { it.isNotBlank() }
                is HttpResult.Failure -> null
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
