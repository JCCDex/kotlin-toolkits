package com.jccdex.toolkits.did.storage.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.ConcurrentHashMap

@Database(
    entities = [DidRoomEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DidRoomDatabase : RoomDatabase() {
    abstract fun didDao(): DidRoomDao

    companion object {
        const val DEFAULT_DATABASE_NAME: String = "did_storage.db"

        private val instances = ConcurrentHashMap<String, DidRoomDatabase>()

        fun getInstance(
            context: Context,
            databaseName: String = DEFAULT_DATABASE_NAME
        ): DidRoomDatabase {
            val appContext = context.applicationContext
            return instances.getOrPut(databaseName) {
                Room.databaseBuilder(
                    appContext,
                    DidRoomDatabase::class.java,
                    databaseName
                ).build()
            }
        }
    }
}
