package com.jccdex.toolkits.dappconnect

import android.net.Uri
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * Normalizes a page URL to a web origin key: `scheme://host[:port]` (M-R4 / H-R2).
 * Returns null if [url] is not an http(s) URL with a host.
 */
object WebOrigin {
    /**
     * Sentinel origin for **native wallet UI** secret access (not a DApp page).
     * Used so internal NFT/sign paths do not pass a blank string (M-18).
     * Hosts must not treat this as a grantable web origin.
     */
    const val WALLET_INTERNAL = "wallet_internal"

    fun normalize(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return null
        return try {
            val uri = Uri.parse(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (host.isBlank()) return null
            val port = uri.port
            val defaultPort = if (scheme == "https") 443 else 80
            if (port != -1 && port != defaultPort) {
                "$scheme://$host:$port"
            } else {
                "$scheme://$host"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
