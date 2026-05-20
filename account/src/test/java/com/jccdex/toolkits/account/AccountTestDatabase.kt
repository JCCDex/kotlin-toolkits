package com.jccdex.toolkits.account

import android.content.Context
import androidx.room.Room
import com.jccdex.toolkits.account.storage.room.AccountRoomDatabase
import com.jccdex.toolkits.account.store.RoomAccountStore

internal class AccountTestDatabase(
    private val database: AccountRoomDatabase
) {
    val accountDao get() = database.accountDao()

    val currentAccountDao get() = database.currentAccountDao()

    val store: RoomAccountStore =
        RoomAccountStore(
            accountDao = accountDao,
            currentAccountDao = currentAccountDao
        )

    fun close() {
        database.close()
    }

    companion object {
        fun inMemory(context: Context): AccountTestDatabase {
            val db =
                Room
                    .inMemoryDatabaseBuilder(context, AccountRoomDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            return AccountTestDatabase(db)
        }
    }
}
