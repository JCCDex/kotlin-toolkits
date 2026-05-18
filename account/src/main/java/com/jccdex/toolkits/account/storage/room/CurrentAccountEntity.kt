package com.jccdex.toolkits.account.storage.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "current_account")
data class CurrentAccountEntity(
    @PrimaryKey
    val id: Int = 1,
    val accountId: String
)
