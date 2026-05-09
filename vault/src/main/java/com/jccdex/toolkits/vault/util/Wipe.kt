package com.jccdex.toolkits.vault.util

fun ByteArray.wipe() = fill(0)

fun CharArray.wipe() = fill('\u0000')
