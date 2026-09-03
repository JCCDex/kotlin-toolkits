package com.jccdex.toolkits.core.error

import com.jccdex.toolkits.core.rpc.ErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolkitExceptionTest {
    @Test
    fun `carries error code and message`() {
        val ex = ToolkitException("boom", ErrorCodes.WALLET_ERROR)
        assertEquals(ErrorCodes.WALLET_ERROR, ex.errorCode)
        assertEquals("boom", ex.message)
    }
}
