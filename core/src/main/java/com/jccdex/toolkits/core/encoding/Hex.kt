package com.jccdex.toolkits.core.encoding

import kotlin.text.HexFormat
import kotlin.text.toHexString

/** Lowercase hex encoding of [this] byte array. */
fun ByteArray.toHex(): String = toHexString(HexFormat.Default)

/** Decodes a hex string into a byte array. */
fun String.fromHex(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
