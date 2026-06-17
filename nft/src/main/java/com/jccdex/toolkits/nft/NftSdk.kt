package com.jccdex.toolkits.nft

import android.content.Context
import com.jccdex.toolkits.nft.model.AvatarCandidate
import com.jccdex.toolkits.nft.model.CredentialImageRequest
import com.jccdex.toolkits.nft.model.EthTokenUriResolver
import com.jccdex.toolkits.nft.model.Nft
import com.jccdex.toolkits.nft.model.NftMetadataFields
import com.jccdex.toolkits.nft.model.ResolvedCredentialImage
import com.jccdex.toolkits.nft.model.WalletAccount
import com.jccdex.toolkits.nft.storage.room.NftDao
import com.jccdex.toolkits.nft.storage.room.NftMetaEntity
import com.jccdex.toolkits.nft.storage.room.NftRoomDatabase
import com.jccdex.toolkits.nft.storage.room.NftStore

class NftSdk internal constructor(
    private val nftStore: NftStore
) {
    suspend fun getAvatarCandidates(account: WalletAccount): List<AvatarCandidate> =
        nftStore.getAvatarCandidates(account)

    suspend fun resolveSwtcAvatar(vc: String): Nft? = nftStore.resolveSwtcAvatar(vc)

    suspend fun ensureSwtcCredentialMetadata(vc: String) = nftStore.ensureSwtcCredentialMetadata(vc)

    suspend fun resolveEthrAvatar(vc: String): Nft? = nftStore.resolveEthrAvatar(vc)

    suspend fun fetchAndCacheNftMeta(
        contract: String,
        tokenId: String,
        tokenUri: String
    ): NftMetaEntity? = nftStore.fetchAndCacheNftMeta(contract, tokenId, tokenUri)

    suspend fun resolveCredentialImage(
        imageUrl: String?,
        metadataUri: String?
    ): String? = nftStore.resolveCredentialImage(imageUrl, metadataUri)

    suspend fun resolveCredentialImage(request: CredentialImageRequest): ResolvedCredentialImage? =
        nftStore.resolveCredentialImage(request)

    suspend fun resolveCredentialImages(requests: List<CredentialImageRequest>): List<ResolvedCredentialImage?> =
        nftStore.resolveCredentialImages(requests)

    suspend fun fetchResolvedMetadataImage(metadataUrl: String): String? =
        nftStore.fetchResolvedMetadataImage(metadataUrl)

    fun normalizeAssetUrl(
        rawUrl: String?,
        baseUrl: String? = null
    ): String? = nftStore.normalizeAssetUrl(rawUrl, baseUrl)

    fun extractResolvedMetadataImageUrl(
        metadataBody: String,
        metadataUri: String
    ): String? = nftStore.extractResolvedMetadataImageUrl(metadataBody, metadataUri)

    fun isSupportedRemoteAssetUrl(url: String?): Boolean = nftStore.isSupportedRemoteAssetUrl(url)

    fun extractSwtcMetadataUri(tokenInfosPayload: String?): String? = nftStore.extractSwtcMetadataUri(tokenInfosPayload)

    suspend fun fetchMetadataFields(metadataUri: String): NftMetadataFields = nftStore.fetchMetadataFields(metadataUri)

    companion object {
        fun create(
            context: Context,
            databaseName: String = NftRoomDatabase.DEFAULT_DATABASE_NAME,
            ethTokenUriResolver: EthTokenUriResolver? = null
        ): NftSdk = NftSdk(NftStore.getInstance(context, databaseName, ethTokenUriResolver))

        fun create(
            nftDao: NftDao,
            ethTokenUriResolver: EthTokenUriResolver? = null
        ): NftSdk = NftSdk(NftStore(nftDao, ethTokenUriResolver))
    }
}
