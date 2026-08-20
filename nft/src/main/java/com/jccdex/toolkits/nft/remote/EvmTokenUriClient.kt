package com.jccdex.toolkits.nft.remote

import com.jccdex.toolkits.core.model.ChainDefaults
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
    /**
     * 方式1: 默认配置（使用ChainDefaults.Evm中的节点）
     *
     * 适用场景：开发测试、个人应用
     *
     * @return 使用默认节点的客户端
     */
    fun createDefault(): EvmTokenUriClient {
        return EvmTokenUriClient { chainId ->
            ChainDefaults.Evm.getRpcUrls(chainId)
        }
    }

    /**
     * 方式2: 完全覆盖（使用自定义节点列表）
     *
     * 适用场景：企业有完全控制的节点
     *
     * @param chainRpcProvider 自定义节点提供器
     * @return 使用自定义节点的客户端
     */
    fun create(chainRpcProvider: (Long) -> List<String>): EvmTokenUriClient {
        return EvmTokenUriClient(chainRpcProvider)
    }

    /**
     * 方式3: 扩展默认节点（默认节点 + 额外节点）
     *
     * 适用场景：企业推荐方案（公共节点优先，私有节点fallback）
     *
     * 执行顺序：公共节点（先尝试）→ 私有节点（fallback）
     *
     * @param additionalNodes 额外的节点配置
     * @return 扩展后的客户端
     */
    fun createWithFallback(additionalNodes: Map<Long, List<String>>): EvmTokenUriClient {
        return EvmTokenUriClient { chainId ->
            val defaultUrls = ChainDefaults.Evm.getRpcUrls(chainId)
            val additionalUrls = additionalNodes[chainId] ?: emptyList()
            defaultUrls + additionalUrls
        }
    }

    /**
     * 方式4: 部分覆盖（某些链用自定义，其他用默认）
     *
     * 适用场景：只修改部分链的节点配置
     *
     * @param customNodes 自定义节点（只覆盖指定的链）
     * @return 部分覆盖的客户端
     */
    fun createWithOverride(customNodes: Map<Long, List<String>>): EvmTokenUriClient {
        return EvmTokenUriClient { chainId ->
            customNodes[chainId] ?: ChainDefaults.Evm.getRpcUrls(chainId)
        }
    }
}
