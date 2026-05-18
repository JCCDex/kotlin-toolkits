package com.jccdex.toolkits.account.storage.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.ConcurrentHashMap

@Database(
    entities = [
        AccountEntity::class,
        CurrentAccountEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AccountRoomDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    abstract fun currentAccountDao(): CurrentAccountDao

    companion object {
        const val DEFAULT_DATABASE_NAME: String = "ccdao_accounts.db"

        private val instances = ConcurrentHashMap<String, AccountRoomDatabase>()

        fun getInstance(
            context: Context,
            databaseName: String = DEFAULT_DATABASE_NAME
        ): AccountRoomDatabase {
            val appContext = context.applicationContext
            return instances.getOrPut(databaseName) {
                Room
                    .databaseBuilder(
                        appContext,
                        AccountRoomDatabase::class.java,
                        databaseName
                    ).build()
            }
        }
    }
}
