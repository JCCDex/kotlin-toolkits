package com.jccdex.toolkits.did.util

import com.jccdex.toolkits.core.encoding.toHex
import org.bouncycastle.jcajce.provider.digest.Keccak
import java.util.Locale

object ChecksumUtils {
    fun toChecksumAddress(rawAddress: String): String {
        val clean = rawAddress.removePrefix("0x").removePrefix("0X")
        require(clean.length == 40) { "Invalid address length ${clean.length}, expect 40 hex chars." }
        require(clean.matches(Regex("^[0-9a-fA-F]{40}$"))) { "Address contains invalid characters." }

        val lower = clean.lowercase(Locale.US)
        val digest = Keccak.Digest256()
        val input = lower.toByteArray(Charsets.US_ASCII)
        digest.update(input, 0, input.size)
        val hashHex = digest.digest().toHex()

        val result = StringBuilder(42)
        result.append("0x")
        for (i in lower.indices) {
            val c = lower[i]
            if (c in 'a'..'f') {
                val nibble = Character.digit(hashHex[i], 16)
                if (nibble >= 8) {
                    result.append(c.uppercaseChar())
                } else {
                    result.append(c)
                }
            } else {
                result.append(c)
            }
        }
        return result.toString()
    }
}
