package com.jccdex.toolkits.nft

import android.content.Context
import com.jccdex.toolkits.nft.model.AvatarCandidate
import com.jccdex.toolkits.nft.model.Nft
import com.jccdex.toolkits.nft.model.WalletAccount
import com.jccdex.toolkits.nft.storage.room.NftStore
import com.jccdex.toolkits.nft.storage.room.NftRoomDatabase
import com.jccdex.toolkits.nft.storage.room.NftDao
import com.jccdex.toolkits.nft.storage.room.NftMetaEntity

class NftSdk internal constructor(
    private val nftStore: NftStore
) {
    suspend fun getAvatarCandidates(account: WalletAccount): List<AvatarCandidate> =
        nftStore.getAvatarCandidates(account)

    suspend fun resolveSwtcAvatar(vc: String): Nft? = nftStore.resolveSwtcAvatar(vc)

    suspend fun resolveEthrAvatar(vc: String): Nft? = nftStore.resolveEthrAvatar(vc)

    suspend fun fetchAndCacheNftMeta(
        contract: String,
        tokenId: String,
        tokenUri: String
    ): NftMetaEntity? = nftStore.fetchAndCacheNftMeta(contract, tokenId, tokenUri)

    companion object {
        fun create(
            context: Context,
            databaseName: String = NftRoomDatabase.DEFAULT_DATABASE_NAME
        ): NftSdk = NftSdk(NftStore.getInstance(context, databaseName))

        fun create(
            nftDao: NftDao
        ): NftSdk = NftSdk(NftStore(nftDao))
    }
}
