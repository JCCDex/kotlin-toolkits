package com.jccdex.toolkits.account.storage.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AccountRoomDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun getInstance_returnsSameInstanceForSameName() {
        val databaseName = "account-room-db-same.db"

        val first = AccountRoomDatabase.getInstance(context, databaseName)
        val second = AccountRoomDatabase.getInstance(context, databaseName)

        assertThat(first).isSameAs(second)

        first.close()
    }

    @Test
    fun getInstance_withoutName_usesDefaultDatabaseName() {
        val viaDefault = AccountRoomDatabase.getInstance(context)
        val viaExplicit =
            AccountRoomDatabase.getInstance(context, AccountRoomDatabase.DEFAULT_DATABASE_NAME)

        assertThat(viaDefault).isSameAs(viaExplicit)

        viaDefault.close()
    }

    @Test
    fun getInstance_returnsDifferentInstancesForDifferentNames() {
        val first = AccountRoomDatabase.getInstance(context, "account-room-db-a.db")
        val second = AccountRoomDatabase.getInstance(context, "account-room-db-b.db")

        assertThat(first).isNotSameAs(second)

        first.close()
        second.close()
    }
}
