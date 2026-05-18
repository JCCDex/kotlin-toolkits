package com.jccdex.toolkits.account

import android.content.Context
import androidx.room.Room
import com.jccdex.toolkits.account.storage.room.AccountRoomDatabase
import com.jccdex.toolkits.account.store.RoomAccountStore

internal class AccountTestDatabase(
    private val database: AccountRoomDatabase
) {
    val store: RoomAccountStore =
        RoomAccountStore(
            accountDao = database.accountDao(),
            currentAccountDao = database.currentAccountDao()
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
