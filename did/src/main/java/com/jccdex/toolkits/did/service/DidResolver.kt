package com.jccdex.toolkits.did.service

interface IDidResolver {
    suspend fun resolve(did: String): String
}
