package com.jccdex.toolkits.vault.security

import android.app.ActivityManager
import android.content.Context
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.util.Arrays
import kotlin.math.min

object Argon2idKdf {
    data class Params(
        val iterations: Int,
        val memoryKiB: Int,
        val parallelism: Int,
    )

    fun randomSalt(len: Int = 16): ByteArray = ByteArray(len).apply { SecureRandom().nextBytes(this) }

    fun deriveKey(
        password: ByteArray,
        salt: ByteArray,
        p: Params,
        keyLen: Int = 32,
    ): ByteArray {
        val pwd = String(password).toByteArray(Charsets.UTF_8)
        try {
            val params =
                Argon2Parameters
                    .Builder(Argon2Parameters.ARGON2_id)
                    .withSalt(salt)
                    .withIterations(p.iterations)
                    .withMemoryAsKB(p.memoryKiB)
                    .withParallelism(p.parallelism)
                    .build()
            val gen = Argon2BytesGenerator()
            gen.init(params)
            val out = ByteArray(keyLen)
            gen.generateBytes(pwd, out, 0, keyLen)
            return out
        } finally {
            Arrays.fill(pwd, 0)
        }
    }
}

object Argon2ParamChooser {
    fun choose(
        context: Context,
        preferLargeHeap: Boolean = false,
    ): Argon2idKdf.Params {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mc = if (preferLargeHeap) am.largeMemoryClass else am.memoryClass

        val (m, t) =
            when {
                mc <= 256 -> 64 to 3
                mc <= 384 -> 96 to 2
                mc <= 512 -> 128 to 2
                else -> min(256, mc / 2) to 2
            }
        return Argon2idKdf.Params(memoryKiB = m * 1024, iterations = t, parallelism = 1)
    }
}
