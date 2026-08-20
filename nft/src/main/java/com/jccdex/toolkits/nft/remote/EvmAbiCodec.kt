package com.jccdex.toolkits.nft.remote

import java.math.BigInteger

object EvmAbiCodec {
    fun buildTokenUriCallData(tokenId: String): String? {
        val tokenIdBigInteger = runCatching { BigInteger(tokenId) }.getOrNull() ?: return null
        if (tokenIdBigInteger < BigInteger.ZERO) return null
        return "0xc87b56dd${tokenIdBigInteger.toString(16).padStart(64, '0')}"
    }

    fun decodeAbiString(hex: String?): String? {
        var normalized = hex?.trim().orEmpty()
        if (normalized.isBlank() || normalized == "0x") {
            return null
        }
        if (normalized.startsWith("0x")) {
            normalized = normalized.substring(2)
        }
        if (normalized.length < 128) {
            return null
        }

        return runCatching {
            val length = BigInteger(normalized.substring(64, 128), 16).toInt()
            val dataStart = 128
            val dataEnd = dataStart + length * 2
            if (normalized.length < dataEnd) {
                return null
            }

            val dataHex = normalized.substring(dataStart, dataEnd)
            val bytes = ByteArray(dataHex.length / 2)
            var index = 0
            while (index < dataHex.length) {
                bytes[index / 2] = dataHex.substring(index, index + 2).toInt(16).toByte()
                index += 2
            }
            String(bytes, Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun decodeBytes32(hex: String?): String? {
        var normalized = hex?.trim().orEmpty()
        if (normalized.isBlank() || normalized == "0x") {
            return null
        }
        if (normalized.startsWith("0x")) {
            normalized = normalized.substring(2)
        }
        if (normalized.length < 64) {
            return null
        }

        return runCatching {
            val bytes = ByteArray(32)
            for (i in 0 until 32) {
                bytes[i] = normalized.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            val endIndex = bytes.indexOfFirst { it == 0.toByte() }.let { if (it == -1) 32 else it }
            String(bytes, 0, endIndex, Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
