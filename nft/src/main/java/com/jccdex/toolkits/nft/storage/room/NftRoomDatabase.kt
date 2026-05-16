package com.jccdex.toolkits.nft.storage.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.ConcurrentHashMap

@Database(
    entities = [
        NftMetaEntity::class,
        EvmNftItemEntity::class,
        SwtcNftEntity::class,
        EvmNftCollectionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class NftRoomDatabase : RoomDatabase() {
    abstract fun nftDao(): NftDao

    companion object {
        const val DEFAULT_DATABASE_NAME: String = "nft_storage.db"

        private val instances = ConcurrentHashMap<String, NftRoomDatabase>()

        fun getInstance(
            context: Context,
            databaseName: String = DEFAULT_DATABASE_NAME
        ): NftRoomDatabase {
            val appContext = context.applicationContext
            return instances.getOrPut(databaseName) {
                Room.databaseBuilder(
                    appContext,
                    NftRoomDatabase::class.java,
                    databaseName
                ).build()
            }
        }
    }
}
