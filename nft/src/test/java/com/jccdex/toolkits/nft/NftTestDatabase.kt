package com.jccdex.toolkits.nft

import android.content.Context
import androidx.room.Room
import com.jccdex.toolkits.nft.storage.room.NftRoomDatabase
import com.jccdex.toolkits.nft.storage.room.NftStore

internal class NftTestDatabase(
    private val database: NftRoomDatabase
) {
    val nftDao get() = database.nftDao()

    val store: NftStore = NftStore(nftDao)

    fun close() {
        database.close()
    }

    companion object {
        fun inMemory(context: Context): NftTestDatabase {
            val db =
                Room
                    .inMemoryDatabaseBuilder(context, NftRoomDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            return NftTestDatabase(db)
        }
    }
}
