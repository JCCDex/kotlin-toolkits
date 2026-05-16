package com.jccdex.toolkits.nft.storage.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNftMeta(entity: NftMetaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNftMeta(entities: List<NftMetaEntity>)

    @Query("SELECT * FROM nft_meta WHERE contract = :contract AND tokenId = :tokenId LIMIT 1")
    suspend fun getNftMeta(contract: String, tokenId: String): NftMetaEntity?

    @Query("DELETE FROM nft_meta WHERE contract = :contract AND tokenId = :tokenId")
    suspend fun deleteNftMeta(contract: String, tokenId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSwtcNfts(entities: List<SwtcNftEntity>)

    @Query("SELECT * FROM swtc_nfts WHERE LOWER(ownerAddress) = LOWER(:ownerAddress) ORDER BY time DESC")
    fun observeSwtcNfts(ownerAddress: String): Flow<List<SwtcNftEntity>>

    @Query("SELECT * FROM swtc_nfts WHERE LOWER(ownerAddress) = LOWER(:ownerAddress) AND tokenId = :tokenId LIMIT 1")
    suspend fun getSwtcNftByTokenId(ownerAddress: String, tokenId: String): SwtcNftEntity?

    @Query("SELECT * FROM swtc_nfts WHERE LOWER(issuer) = LOWER(:issuer) AND tokenId = :tokenId LIMIT 1")
    suspend fun getSwtcNftByIssuerAndTokenId(issuer: String, tokenId: String): SwtcNftEntity?

    @Query("DELETE FROM swtc_nfts WHERE LOWER(ownerAddress) = LOWER(:ownerAddress)")
    suspend fun deleteSwtcNftsByOwner(ownerAddress: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvmNftItems(entities: List<EvmNftItemEntity>)

    @Query("SELECT * FROM evm_nft_items WHERE chainId = :chainId AND ownerAddress = :ownerAddress AND contractAddress = :contractAddress ORDER BY ownerTimestamp DESC")
    fun observeEvmNftItems(
        chainId: String,
        ownerAddress: String,
        contractAddress: String
    ): Flow<List<EvmNftItemEntity>>

    @Query("SELECT * FROM evm_nft_items WHERE chainId = :chainId AND ownerAddress = :ownerAddress ORDER BY ownerTimestamp DESC")
    fun observeAllEvmNftItems(
        chainId: String,
        ownerAddress: String
    ): Flow<List<EvmNftItemEntity>>

    @Query("SELECT * FROM evm_nft_items WHERE chainId = :chainId AND ownerAddress = :ownerAddress AND contractAddress = :contractAddress AND tokenId = :tokenId LIMIT 1")
    suspend fun getEvmNftItem(
        chainId: String,
        ownerAddress: String,
        contractAddress: String,
        tokenId: String
    ): EvmNftItemEntity?

    @Query("SELECT * FROM evm_nft_items WHERE chainId = :chainId AND contractAddress = :contractAddress AND tokenId = :tokenId LIMIT 1")
    suspend fun getEvmNftItemByContractAndTokenId(
        chainId: String,
        contractAddress: String,
        tokenId: String
    ): EvmNftItemEntity?

    @Query("DELETE FROM evm_nft_items WHERE chainId = :chainId AND ownerAddress = :ownerAddress AND contractAddress = :contractAddress")
    suspend fun deleteEvmNftItemsByCollection(
        chainId: String,
        ownerAddress: String,
        contractAddress: String
    )

    @Query("SELECT COUNT(*) FROM evm_nft_items WHERE chainId = :chainId AND ownerAddress = :ownerAddress AND contractAddress = :contractAddress")
    suspend fun getItemCount(
        chainId: String,
        ownerAddress: String,
        contractAddress: String
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollections(collections: List<EvmNftCollectionEntity>)

    @Query("SELECT * FROM evm_nft_collections WHERE chainId = :chainId AND ownerAddress = :ownerAddress ORDER BY ts DESC")
    fun getNftCollectionsFlow(
        chainId: String,
        ownerAddress: String
    ): Flow<List<EvmNftCollectionEntity>>

    @Query("DELETE FROM evm_nft_collections WHERE chainId = :chainId AND ownerAddress = :ownerAddress")
    suspend fun deleteByChainAndOwner(
        chainId: String,
        ownerAddress: String
    )

    @Query("UPDATE evm_nft_collections SET tokenCount = :tokenCount WHERE chainId = :chainId AND ownerAddress = :ownerAddress AND contractAddress = :contractAddress")
    suspend fun updateTokenCount(
        chainId: String,
        ownerAddress: String,
        contractAddress: String,
        tokenCount: Int
    )
}
