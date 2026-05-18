package com.jccdex.toolkits.did.storage.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DidRoomDatabaseTest {
    private var dbName: String? = null

    @After
    fun tearDown() {
        val name = dbName ?: return
        val context = ApplicationProvider.getApplicationContext<Context>()
        DidRoomDatabase.getInstance(context, name).close()
    }

    @Test
    fun getInstance_returnsSingletonPerDatabaseName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbName = "did_singleton_test_${System.nanoTime()}"

        val first = DidRoomDatabase.getInstance(context, dbName!!)
        val second = DidRoomDatabase.getInstance(context, dbName!!)

        assertThat(first).isSameAs(second)
        assertThat(first.didDao()).isNotNull
    }
}
