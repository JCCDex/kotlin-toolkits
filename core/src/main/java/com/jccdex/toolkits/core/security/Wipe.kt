package com.jccdex.toolkits.core.security

/** Zeroes the contents of [this] byte array to scrub sensitive data. */
fun ByteArray.wipe() = fill(0)

/** Replaces the contents of [this] char array with NULs to scrub sensitive data. */
fun CharArray.wipe() = fill('\u0000')
