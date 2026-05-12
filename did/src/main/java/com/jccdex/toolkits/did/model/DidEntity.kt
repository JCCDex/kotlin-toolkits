package com.jccdex.toolkits.did.model

data class DidEntity(
    val id: Long = 0,
    val did: String,
    val doc: String,
    val updatedAt: Long = System.currentTimeMillis()
)
