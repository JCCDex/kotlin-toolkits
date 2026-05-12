package com.jccdex.toolkits.did.storage.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "did_documents")
data class DidRoomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val did: String,
    val doc: String,
    val updatedAt: Long = System.currentTimeMillis()
)
