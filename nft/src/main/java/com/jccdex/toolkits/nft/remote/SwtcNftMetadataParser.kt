package com.jccdex.toolkits.nft.remote

import com.jccdex.toolkits.core.json.optStringSafe
import com.jccdex.toolkits.nft.model.NftMetadataFields
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

fun extractSwtcMetadataUri(tokenInfosPayload: String?): String? {
    val tokenInfosJson = tokenInfosPayload?.takeIf { it.isNotBlank() } ?: return null
    return try {
        val infos = JSONArray(tokenInfosJson)
        for (index in 0 until infos.length()) {
            val tokenInfo = infos.optJSONObject(index)?.optJSONObject("TokenInfo") ?: continue
            val infoType = decodeHexToUtf8(tokenInfo.optString("InfoType"))
            if (infoType != "tokenUri") {
                continue
            }
            val infoData = decodeHexToUtf8(tokenInfo.optString("InfoData"))
            normalizeRemoteAssetUrl(infoData)?.let { return it }
        }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

fun extractMetadataFields(
    metadataBody: String,
    metadataUri: String
): NftMetadataFields {
    val root = runCatching { JSONObject(metadataBody) }.getOrNull() ?: return NftMetadataFields(null, null, null)
    val payload = root.optJSONObject("data") ?: root
    return NftMetadataFields(
        image = extractMetadataImageUrl(root, metadataUri),
        name = payload.optStringSafe("name"),
        description = payload.optStringSafe("description")
    )
}

private fun decodeHexToUtf8(hex: String): String {
    return try {
        var clean = hex
        if (clean.startsWith("0x", ignoreCase = true)) {
            clean = clean.substring(2)
        }
        clean = clean.replace("\\s".toRegex(), "")
        if (clean.isEmpty() || clean.length % 2 != 0) {
            return ""
        }
        val bytes = ByteArray(clean.length / 2)
        for (index in bytes.indices) {
            val byteStr = clean.substring(index * 2, index * 2 + 2)
            bytes[index] = byteStr.toInt(16).toByte()
        }
        String(bytes, Charsets.UTF_8)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ""
    }
}
