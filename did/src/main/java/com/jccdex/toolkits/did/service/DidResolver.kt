package com.jccdex.toolkits.did.service

interface DidResolver {
    suspend fun resolve(did: String): String
}
