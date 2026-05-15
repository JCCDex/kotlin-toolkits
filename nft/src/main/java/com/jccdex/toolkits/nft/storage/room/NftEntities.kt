package com.jccdex.toolkits.nft.storage.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "nft_meta",
    indices = [Index(value = ["contract", "tokenId"], unique = true)]
)
data class NftMetaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val contract: String,
    val tokenId: String,
    val name: String?,
    val image: String?,
    val tokenUri: String?,
    val fullContent: String?,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "evm_nft_items",
    primaryKeys = ["chainId", "ownerAddress", "contractAddress", "tokenId"],
    indices = [Index(value = ["chainId", "ownerAddress", "contractAddress"])]
)
data class EvmNftItemEntity(
    val chainId: String,
    val ownerAddress: String,
    val contractAddress: String,
    val tokenId: String,
    val objectId: String? = null,
    val blockchainId: Int? = null,
    val ownerTimestamp: Long? = null,
    val imageUrl: String? = null,
    val metadata: String? = null,
    val tokenProtocol: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "swtc_nfts",
    primaryKeys = ["ownerAddress", "tokenId"],
    indices = [Index(value = ["ownerAddress"])]
)
data class SwtcNftEntity(
    val ownerAddress: String,
    val tokenId: String,
    val fundCode: String,
    val fundCodeName: String,
    val issuer: String,
    val tokenOwner: String,
    val tokenSender: String,
    val flags: String?,
    val tokenInfos: String?,
    val metadataUri: String?,
    val image: String?,
    val name: String?,
    val description: String?,
    val time: Long,
    val hash: String?,
    val block: Long,
    val inservice: Int,
    val ledgerIndex: String?,
    val lastUpdateTime: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "evm_nft_collections",
    primaryKeys = ["chainId", "ownerAddress", "contractAddress"],
    indices = [Index(value = ["chainId", "ownerAddress"])]
)
data class EvmNftCollectionEntity(
    val chainId: String,
    val ownerAddress: String,
    val contractAddress: String,
    val name: String,
    val symbol: String,
    val iconUrl: String? = null,
    val decimals: Int = 0,
    val hid: Long? = null,
    val blockchainId: Int? = null,
    val tokenType: Int? = null,
    val tokenStatus: Int? = null,
    val tokenProtocol: Int = 1,
    val ts: Long? = null,
    val description: String? = null,
    val blSymbol: String? = null,
    val website: String? = null,
    val priceUsd: String? = null,
    val chg: String? = null,
    val validated: Int? = null,
    val gas: Int? = null,
    val liquidity: Double? = null,
    val priceUpdateTime: Long? = null,
    val tokenCount: Int = 0
)
