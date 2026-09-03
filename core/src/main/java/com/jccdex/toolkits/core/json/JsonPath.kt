package com.jccdex.toolkits.core.json

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * JSON dot-path reader (C-14): parses [doc] with Gson and walks `$.a.b.c`.
 *
 * Converges the previously duplicated implementations in DidSdk (readElement/readString) and
 * NftStore (parseString). Gson is used for structured reads; org.json for untyped dynamic reads.
 */
object JsonPath {
    /** Walks [path] (e.g. `$.credentialSubject.chainId`) returning the element, or null. */
    fun readElement(
        doc: String,
        path: String
    ): JsonElement? {
        val cleaned = path.removePrefix("$.")
        var current: JsonElement =
            try {
                JsonParser.parseString(doc)
            } catch (e: Exception) {
                return null
            }
        if (cleaned.isBlank()) return current
        for (part in cleaned.split('.')) {
            if (!current.isJsonObject) return null
            current = current.asJsonObject.get(part) ?: return null
        }
        return current
    }

    /** Reads a string at [path], or null when missing, null, or not a string. */
    fun readString(
        doc: String,
        path: String
    ): String? = readElement(doc, path)?.takeIf { !it.isJsonNull }?.asString

    /** Reads a string at [path], falling back to [defaultValue]. */
    fun readString(
        doc: String,
        path: String,
        defaultValue: String
    ): String = readString(doc, path) ?: defaultValue

    /** Reads an EVM chain id (JSON number, decimal string, or `0x` hex string). */
    fun readChainIdLong(
        doc: String,
        path: String
    ): Long? {
        val element = readElement(doc, path) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        return when {
            primitive.isNumber -> primitive.asLong
            primitive.isString -> {
                val raw = primitive.asString.trim()
                when {
                    raw.isBlank() -> null
                    raw.startsWith("0x", ignoreCase = true) ->
                        raw.substring(2).toLongOrNull(16)
                    else -> raw.toLongOrNull()
                }
            }
            else -> null
        }
    }

    /**
     * Reads an EVM chain id from [path], falling back to Ethereum mainnet (`1`) when an ethr DID
     * is present on the subject (`owner` / `id`) or credential `id`, and chain id is omitted.
     */
    fun readEvmChainIdLong(
        doc: String,
        path: String = "\$.credentialSubject.chainId",
        ownerPath: String = "\$.credentialSubject.owner"
    ): Long? {
        readChainIdLong(doc, path)?.takeIf { it > 0 }?.let { return it }
        for (candidatePath in listOf(ownerPath, "\$.credentialSubject.id")) {
            readString(doc, candidatePath)
                ?.takeIf { it.startsWith("did:ethr:", ignoreCase = true) }
                ?.let { return 1L }
        }
        readString(doc, "\$.id")
            ?.substringBefore("#")
            ?.takeIf { it.startsWith("did:ethr:", ignoreCase = true) }
            ?.let { return 1L }
        return null
    }
}
