package com.jccdex.toolkits.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AccountClassificationTest {
    @Test
    fun kotlinPredicates_matchWalletAccountSemantics() {
        assertTrue(
            AccountClassification.isRootHD(
                isHD = true,
                parentId = null,
                pathAccount = 0,
                pathChange = 0,
                pathIndex = 0
            )
        )
        assertTrue(
            AccountClassification.isSubHD(
                isHD = true,
                parentId = "parent",
                pathAccount = 0,
                pathChange = 0,
                pathIndex = 0
            )
        )
        assertTrue(
            AccountClassification.isSubHD(
                isHD = true,
                parentId = null,
                pathAccount = 0,
                pathChange = 0,
                pathIndex = 2
            )
        )
        assertFalse(
            AccountClassification.isSubHD(
                isHD = true,
                parentId = null,
                pathAccount = 0,
                pathChange = 0,
                pathIndex = 0
            )
        )
        assertFalse(
            AccountClassification.isRootHD(
                isHD = true,
                parentId = null,
                pathAccount = 0,
                pathChange = 0,
                pathIndex = 1
            )
        )
        assertTrue(AccountClassification.isNonRoot(isHD = false, parentId = null, null, null, null))
        assertTrue(
            AccountClassification.isNonRoot(
                isHD = true,
                parentId = null,
                pathAccount = 0,
                pathChange = 0,
                pathIndex = 3
            )
        )
    }
}
