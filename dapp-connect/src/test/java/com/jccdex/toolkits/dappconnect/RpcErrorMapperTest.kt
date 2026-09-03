package com.jccdex.toolkits.dappconnect

import com.jccdex.toolkits.core.rpc.ErrorCodes
import com.jccdex.toolkits.dappconnect.model.ChainNotSupportedException
import com.jccdex.toolkits.dappconnect.model.UnauthorizedException
import com.jccdex.toolkits.dappconnect.model.UserRejectedException
import org.junit.Assert.assertEquals
import org.junit.Test

class RpcErrorMapperTest {
    @Test
    fun `maps UserRejectedException to 4001`() {
        val err = UserRejectedException("User rejected").toRpcError("fallback")
        assertEquals(ErrorCodes.USER_REJECTED, err.code)
        assertEquals("User rejected", err.message)
    }

    @Test
    fun `maps ChainNotSupportedException to 4902`() {
        val err = ChainNotSupportedException(56L, "Unsupported chain").toRpcError("fallback")
        assertEquals(ErrorCodes.CHAIN_NOT_SUPPORTED, err.code)
    }

    @Test
    fun `maps IllegalArgumentException to invalid params`() {
        val err = IllegalArgumentException("Account not found").toRpcError("fallback")
        assertEquals(ErrorCodes.INVALID_PARAMS, err.code)
        assertEquals("Account not found", err.message)
    }

    @Test
    fun `maps password required to unauthorized`() {
        val err =
            IllegalStateException("Password required to sign transaction")
                .toRpcError("fallback")
        assertEquals(ErrorCodes.UNAUTHORIZED, err.code)
    }

    @Test
    fun `maps IllegalArgumentException password required to unauthorized`() {
        val err =
            IllegalArgumentException("Password required to sign transaction")
                .toRpcError("fallback")
        assertEquals(ErrorCodes.UNAUTHORIZED, err.code)
    }

    @Test
    fun `maps provider not set to internal error`() {
        val err = IllegalStateException("ChainProvider not set").toRpcError("fallback")
        assertEquals(ErrorCodes.INTERNAL_ERROR, err.code)
    }

    @Test
    fun `maps generic exception to wallet error with fallback message`() {
        val err = RuntimeException().toRpcError("Transaction failed")
        assertEquals(ErrorCodes.WALLET_ERROR, err.code)
        assertEquals("Transaction failed", err.message)
    }

    @Test
    fun `maps UnauthorizedException to 4100`() {
        val err = UnauthorizedException("Not authorized").toRpcError("fallback")
        assertEquals(ErrorCodes.UNAUTHORIZED, err.code)
    }
}
