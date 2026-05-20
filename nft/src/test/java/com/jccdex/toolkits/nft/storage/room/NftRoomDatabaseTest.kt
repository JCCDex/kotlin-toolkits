package com.jccdex.toolkits.nft.storage.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NftRoomDatabaseTest {
    private var databaseName: String? = null

    @After
    fun tearDown() {
        val name = databaseName ?: return
        val context = ApplicationProvider.getApplicationContext<Context>()
        NftRoomDatabase.getInstance(context, name).close()
    }

    @Test
    fun getInstance_withoutName_usesDefaultDatabaseName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        databaseName = NftRoomDatabase.DEFAULT_DATABASE_NAME

        val viaDefault = NftRoomDatabase.getInstance(context)
        val viaExplicit = NftRoomDatabase.getInstance(context, NftRoomDatabase.DEFAULT_DATABASE_NAME)

        assertThat(viaDefault).isSameAs(viaExplicit)
        assertThat(viaDefault.nftDao()).isNotNull
    }

    @Test
    fun getInstance_returnsSingletonPerDatabaseName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        databaseName = "nft_singleton_${System.nanoTime()}"

        val first = NftRoomDatabase.getInstance(context, databaseName!!)
        val second = NftRoomDatabase.getInstance(context, databaseName!!)

        assertThat(first).isSameAs(second)
    }
}
