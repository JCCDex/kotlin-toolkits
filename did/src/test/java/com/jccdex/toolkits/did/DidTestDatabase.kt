package com.jccdex.toolkits.did

import android.content.Context
import androidx.room.Room
import com.jccdex.toolkits.did.storage.room.DidRoomDatabase
import com.jccdex.toolkits.did.storage.room.RoomDidStore

internal class DidTestDatabase(
    private val database: DidRoomDatabase
) {
    val didDao get() = database.didDao()

    val store: RoomDidStore = RoomDidStore(didDao)

    fun close() {
        database.close()
    }

    companion object {
        fun inMemory(context: Context): DidTestDatabase {
            val db =
                Room
                    .inMemoryDatabaseBuilder(context, DidRoomDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            return DidTestDatabase(db)
        }
    }
}
