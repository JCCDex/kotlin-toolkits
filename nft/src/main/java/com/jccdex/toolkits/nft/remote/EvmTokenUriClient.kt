package com.jccdex.toolkits.nft.remote

import com.jccdex.toolkits.nft.model.EthTokenUriResolver

class EvmTokenUriClient(
    private val chainRpcProvider: (Long) -> List<String>
) : EthTokenUriResolver {
    override suspend fun resolveEthrTokenUri(
        contract: String,
        tokenId: String,
        chainId: Long
    ): String? {
        val rpcUrls = chainRpcProvider(chainId)
        if (rpcUrls.isEmpty()) return null

        val rpcClient = EvmRpcClient(rpcUrls)
        val callData = EvmAbiCodec.buildTokenUriCallData(tokenId) ?: return null
        val rawResult = rpcClient.ethCall(contract, callData) ?: return null
        val decodedUri = EvmAbiCodec.decodeAbiString(rawResult) ?: return null

        return normalizeRemoteAssetUrl(decodedUri) ?: decodedUri
    }
}

object EvmTokenUriClientFactory {
    fun create(chainRpcProvider: (Long) -> List<String>): EvmTokenUriClient {
        return EvmTokenUriClient(chainRpcProvider)
    }

    fun createDefault(): EvmTokenUriClient {
        return EvmTokenUriClient { chainId ->
            EvmRpcClient.DEFAULT_RPC_NODES[chainId] ?: emptyList()
        }
    }
}
