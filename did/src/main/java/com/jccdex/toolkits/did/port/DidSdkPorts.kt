package com.jccdex.toolkits.did.port

import com.jccdex.toolkits.did.model.Nft
import com.jccdex.toolkits.did.model.WalletAccount

/**
 * Bridge to the chain-side DID JS runtime (e.g. WebView bridge).
 *
 * Implementations MUST be safe to call from background threads.
 */
interface IDidBridge {
    suspend fun call(method: String, params: String? = null): String
    suspend fun <T> callAs(method: String, params: String? = null, clazz: Class<T>): T
}

interface IDidAvatarResolver {
    suspend fun resolveSwtcAvatar(vc: String): Nft?
    suspend fun resolveEthrAvatar(vc: String): Nft?
}

/**
 * Optional extension point: provide wallet-specific NFT avatar candidates.
 *
 * This stays out of [com.jccdex.toolkits.did.DidSdk] to avoid coupling to app-specific DB schemas.
 */
interface IDidAvatarCredentialSource {
    suspend fun getAvatarCandidates(account: WalletAccount): List<DidAvatarAsset>
}

data class DidAvatarAsset(
    val image: String?,
    val name: String,
    val contract: String?,
    val tokenId: String,
    val issuer: String?,
    val tokenName: String?,
    val chainId: Long?,
    val isSwtc: Boolean
)
