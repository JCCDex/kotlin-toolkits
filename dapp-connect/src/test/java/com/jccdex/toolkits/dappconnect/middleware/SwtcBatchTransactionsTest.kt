package com.jccdex.toolkits.dappconnect.middleware

import com.jccdex.toolkits.wallet.sdk.WalletSdk
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 直接测 SwtcBatchTransactions 的纯逻辑（parse / validate / build），不依赖 middleware 与 provider。 */
@RunWith(RobolectricTestRunner::class)
class SwtcBatchTransactionsTest {
    private val from = "jSwtcAddress"
    private val dest = "jDestAddress"

    @Before
    fun setup() {
        mockkObject(WalletSdk)
        coEvery { WalletSdk.isValidAddress(any()) } returns true
        coEvery {
            WalletSdk.buildSwtcCreateOrder(any(), any(), any(), any(), any(), any(), any(), any())
        } returns """{"Account":"$from","TransactionType":"OfferCreate"}"""
        coEvery {
            WalletSdk.buildSwtcCancelOrder(any(), any())
        } returns """{"Account":"$from","TransactionType":"OfferCancel"}"""
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── parse ──

    @Test
    fun `parseTransfers parses valid items`() {
        val arr =
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("to", dest)
                        put("amount", "1")
                        put("currency", "SWT")
                    }
                )
                put(
                    JSONObject().apply {
                        put("to", dest)
                        put("amount", "2")
                        put("currency", "CCC")
                        put("issuer", from)
                        put("memo", "hi")
                    }
                )
            }

        val result = SwtcBatchTransactions.parseTransfers(arr)

        assertEquals(2, result.size)
        assertEquals(dest, result[0].to)
        assertEquals("CCC", result[1].currency)
        assertEquals(from, result[1].issuer)
        assertEquals("hi", result[1].memo)
    }

    @Test
    fun `parseTransfers rejects unknown field`() {
        val arr =
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("to", dest)
                        put("amount", "1")
                        put("unknown", "x")
                    }
                )
            }

        assertFailsWith<IllegalArgumentException> { SwtcBatchTransactions.parseTransfers(arr) }
    }

    @Test
    fun `parseTransfers rejects non-object element`() {
        val arr = JSONArray().put("not-an-object")
        assertFailsWith<IllegalArgumentException> { SwtcBatchTransactions.parseTransfers(arr) }
    }

    @Test
    fun `parseCreateOrders rejects non-object element`() {
        val arr = JSONArray().put(42)
        assertFailsWith<IllegalArgumentException> { SwtcBatchTransactions.parseCreateOrders(arr) }
    }

    @Test
    fun `parseCancelOrders rejects non-object element`() {
        val arr = JSONArray().put(JSONArray())
        assertFailsWith<IllegalArgumentException> { SwtcBatchTransactions.parseCancelOrders(arr) }
    }

    @Test
    fun `parseTransfers returns empty for null`() {
        assertTrue(SwtcBatchTransactions.parseTransfers(null).isEmpty())
    }

    @Test
    fun `parseCreateOrders rejects unknown field`() {
        val arr =
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("amount", "1")
                        put("base", "SWT")
                        put("counter", "CCC")
                        put("sum", "100")
                        put("type", "buy")
                        put("unknown", "x")
                    }
                )
            }

        assertFailsWith<IllegalArgumentException> { SwtcBatchTransactions.parseCreateOrders(arr) }
    }

    @Test
    fun `parseCancelOrders parses sequence`() {
        val arr = JSONArray().apply { put(JSONObject().apply { put("sequence", 42) }) }

        val result = SwtcBatchTransactions.parseCancelOrders(arr)

        assertEquals(1, result.size)
        assertEquals(42L, result[0].sequence)
    }

    @Test
    fun `parseCancelOrders rejects unknown field`() {
        val arr =
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("sequence", 1)
                        put("unknown", 2)
                    }
                )
            }

        assertFailsWith<IllegalArgumentException> { SwtcBatchTransactions.parseCancelOrders(arr) }
    }

    // ── validate ──

    @Test
    fun `isValidTransfer true for native SWT with valid address`() =
        runTest {
            val t = SwtcBatchTransactions.Transfer(dest, "1", "SWT", null, null)
            assertTrue(SwtcBatchTransactions.isValidTransfer(t))
        }

    @Test
    fun `isValidTransfer false for non-positive amount`() =
        runTest {
            val t = SwtcBatchTransactions.Transfer(dest, "-5", "SWT", null, null)
            assertFalse(SwtcBatchTransactions.isValidTransfer(t))
        }

    @Test
    fun `isValidTransfer false when destination invalid`() =
        runTest {
            coEvery { WalletSdk.isValidAddress(dest) } returns false
            val t = SwtcBatchTransactions.Transfer(dest, "1", "SWT", null, null)
            assertFalse(SwtcBatchTransactions.isValidTransfer(t))
        }

    @Test
    fun `isValidTransfer false for native currency with non-empty issuer`() =
        runTest {
            val t = SwtcBatchTransactions.Transfer(dest, "1", "SWT", from, null)
            assertFalse(SwtcBatchTransactions.isValidTransfer(t))
        }

    // ── M-D8: amount / memo bounds ──

    @Test
    fun `parseTransfers rejects over-length memo`() {
        val arr =
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("to", dest)
                        put("amount", "1")
                        put("currency", "SWT")
                        put("memo", "x".repeat(65))
                    }
                )
            }

        assertFailsWith<IllegalArgumentException> { SwtcBatchTransactions.parseTransfers(arr) }
    }

    @Test
    fun `parseTransfers accepts max-length memo`() {
        val arr =
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("to", dest)
                        put("amount", "1")
                        put("currency", "SWT")
                        put("memo", "x".repeat(64))
                    }
                )
            }

        val result = SwtcBatchTransactions.parseTransfers(arr)
        assertEquals(64, result[0].memo!!.length)
    }

    @Test
    fun `isValidTransfer false for over-limit amount`() =
        runTest {
            val t = SwtcBatchTransactions.Transfer(dest, "1000000000001", "SWT", null, null)
            assertFalse(SwtcBatchTransactions.isValidTransfer(t))
        }

    @Test
    fun `isValidTransfer false for amount exceeding 6 decimal places`() =
        runTest {
            val t = SwtcBatchTransactions.Transfer(dest, "1.0000001", "SWT", null, null)
            assertFalse(SwtcBatchTransactions.isValidTransfer(t))
        }

    @Test
    fun `isBoundedPositiveAmount accepts max-limit and rejects over-limit`() {
        assertTrue(SwtcBatchTransactions.isBoundedPositiveAmount("1000000000000", enforceScale = true))
        assertFalse(SwtcBatchTransactions.isBoundedPositiveAmount("1000000000000.000001", enforceScale = true))
        assertFalse(SwtcBatchTransactions.isBoundedPositiveAmount("0", enforceScale = true))
    }

    @Test
    fun `isValidTransfer accepts high precision for non-native token`() =
        runTest {
            // M-D8: precision cap (scale≤6) applies only to native SWT — non-native tokens may
            // legitimately have >6 decimal places.
            val t = SwtcBatchTransactions.Transfer(dest, "1.0000001", "CCC", from, null)
            assertTrue(SwtcBatchTransactions.isValidTransfer(t))
        }

    @Test
    fun `isValidCreateOrder true for valid buy`() =
        runTest {
            val o = SwtcBatchTransactions.CreateOrder("1", "SWT", "CCC", "100", "buy", null, from)
            assertTrue(SwtcBatchTransactions.isValidCreateOrder(o))
        }

    @Test
    fun `isValidCreateOrder false for invalid type`() =
        runTest {
            val o = SwtcBatchTransactions.CreateOrder("1", "SWT", "CCC", "100", "hold", null, from)
            assertFalse(SwtcBatchTransactions.isValidCreateOrder(o))
        }

    @Test
    fun `isValidCreateOrder false for non-positive sum`() =
        runTest {
            val o = SwtcBatchTransactions.CreateOrder("1", "SWT", "CCC", "-1", "buy", null, from)
            assertFalse(SwtcBatchTransactions.isValidCreateOrder(o))
        }

    @Test
    fun `isPositiveDecimal handles invalid input`() {
        assertTrue(SwtcBatchTransactions.isPositiveDecimal("1.5"))
        assertFalse(SwtcBatchTransactions.isPositiveDecimal("0"))
        assertFalse(SwtcBatchTransactions.isPositiveDecimal("abc"))
    }

    @Test
    fun `isValidCurrency matches 3-6 alnum or 40 hex`() {
        assertTrue(SwtcBatchTransactions.isValidCurrency("SWT"))
        assertTrue(SwtcBatchTransactions.isValidCurrency("CCCD"))
        assertTrue(SwtcBatchTransactions.isValidCurrency("A".repeat(40).uppercase()))
        assertFalse(SwtcBatchTransactions.isValidCurrency("AB"))
        assertFalse(SwtcBatchTransactions.isValidCurrency("SWT!"))
    }

    @Test
    fun `isValidCurrencyAndIssuer requires issuer for non-native`() =
        runTest {
            assertTrue(SwtcBatchTransactions.isValidCurrencyAndIssuer("CCC", from, defaultIssuerIfNonNative = false))
            assertFalse(SwtcBatchTransactions.isValidCurrencyAndIssuer("CCC", null, defaultIssuerIfNonNative = false))
            assertTrue(SwtcBatchTransactions.isValidCurrencyAndIssuer("CCC", null, defaultIssuerIfNonNative = true))
        }

    // ── build ──

    @Test
    fun `buildTxs builds native transfer as plain amount with fee 0 point 01`() =
        runTest {
            val txs =
                SwtcBatchTransactions.buildTxs(
                    from,
                    listOf(SwtcBatchTransactions.Transfer(dest, "1", "SWT", null, null)),
                    emptyList(),
                    emptyList()
                )

            assertEquals(1, txs.size)
            val tx = txs[0]
            assertEquals(from, tx.getString("Account"))
            assertEquals("Payment", tx.getString("TransactionType"))
            assertEquals(dest, tx.getString("Destination"))
            assertEquals("1", tx.getString("Amount"))
            assertEquals("0.01", tx.getString("Fee"))
        }

    @Test
    fun `buildTxs builds non-native transfer as amount object with issuer`() =
        runTest {
            val txs =
                SwtcBatchTransactions.buildTxs(
                    from,
                    listOf(SwtcBatchTransactions.Transfer(dest, "5", "CCC", from, null)),
                    emptyList(),
                    emptyList()
                )

            val amount = txs[0].getJSONObject("Amount")
            assertEquals("5", amount.getString("value"))
            assertEquals("CCC", amount.getString("currency"))
            assertEquals(from, amount.getString("issuer"))
        }

    @Test
    fun `buildTxs includes memo in text-plain format`() =
        runTest {
            val txs =
                SwtcBatchTransactions.buildTxs(
                    from,
                    listOf(SwtcBatchTransactions.Transfer(dest, "1", "SWT", null, "hello")),
                    emptyList(),
                    emptyList()
                )

            val memo = txs[0].getJSONArray("Memos").getJSONObject(0).getJSONObject("Memo")
            assertEquals("hello", memo.getString("MemoData"))
            assertEquals("text/plain", memo.getString("MemoType"))
        }

    @Test
    fun `buildTxs builds create order via WalletSdk`() =
        runTest {
            val txs =
                SwtcBatchTransactions.buildTxs(
                    from,
                    emptyList(),
                    listOf(SwtcBatchTransactions.CreateOrder("1", "SWT", "CCC", "100", "buy", null, from)),
                    emptyList()
                )

            assertEquals(1, txs.size)
            assertEquals("OfferCreate", txs[0].getString("TransactionType"))
        }

    @Test
    fun `buildTxs builds cancel order via WalletSdk`() =
        runTest {
            val txs =
                SwtcBatchTransactions.buildTxs(
                    from,
                    emptyList(),
                    emptyList(),
                    listOf(SwtcBatchTransactions.CancelOrder(42L))
                )

            assertEquals(1, txs.size)
            assertEquals("OfferCancel", txs[0].getString("TransactionType"))
        }

    @Test
    fun `buildTxs preserves order transfers then createOrders then cancelOrders`() =
        runTest {
            val txs =
                SwtcBatchTransactions.buildTxs(
                    from,
                    listOf(SwtcBatchTransactions.Transfer(dest, "1", "SWT", null, null)),
                    listOf(SwtcBatchTransactions.CreateOrder("1", "SWT", "CCC", "100", "buy", null, from)),
                    listOf(SwtcBatchTransactions.CancelOrder(42L))
                )

            assertEquals(3, txs.size)
            assertEquals("Payment", txs[0].getString("TransactionType"))
            assertEquals("OfferCreate", txs[1].getString("TransactionType"))
            assertEquals("OfferCancel", txs[2].getString("TransactionType"))
        }
}
