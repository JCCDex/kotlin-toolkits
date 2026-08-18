package com.jccdex.toolkits.dappconnect.middleware

import com.jccdex.toolkits.wallet.sdk.WalletSdk
import org.json.JSONArray
import org.json.JSONObject

/**
 * `swtc_batchTransactions` 的纯逻辑：入参解析（含字段白名单）、语义校验、三类交易构建。
 *
 * 与 [WalletSdk] 交互（isValidAddress / buildSwtcCreateOrder / buildSwtcCancelOrder），
 * 不依赖任何 middleware 状态，可独立测试与跨宿主复用。
 * 调用方（[SwtcMiddleware.batchTransactions]）负责鉴权、取 secret、send/return 编排。
 */
object SwtcBatchTransactions {

    /** 对齐 @swtc/common 的 CURRENCY_RE：3-6 位字母数字，或 40 位十六进制大写 */
    val CURRENCY_REGEX = Regex("^([a-zA-Z0-9]{3,6}|[A-F0-9]{40})$")

    data class Transfer(
        val to: String,
        val amount: String,
        val currency: String?,
        val issuer: String?,
        val memo: String?
    )

    data class CreateOrder(
        val amount: String,
        val base: String,
        val counter: String,
        val sum: String,
        val type: String,
        val platform: String?,
        val issuer: String?
    )

    data class CancelOrder(val sequence: Long)

    // ── 解析（含字段白名单，对齐插件 ALLOWED_*_KEYS）──

    fun parseTransfers(arr: JSONArray?): List<Transfer> {
        if (arr == null) return emptyList()
        val allowed = setOf("to", "amount", "currency", "issuer", "memo")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            if (!o.keys().asSequence().all { it in allowed }) throw IllegalArgumentException("Invalid batch transfers")
            Transfer(o.optString("to"), o.optString("amount"), o.optString("currency").ifEmpty { null }, o.optString("issuer").ifEmpty { null }, o.optString("memo").ifEmpty { null })
        }
    }

    fun parseCreateOrders(arr: JSONArray?): List<CreateOrder> {
        if (arr == null) return emptyList()
        val allowed = setOf("amount", "base", "counter", "sum", "type", "platform", "issuer")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            if (!o.keys().asSequence().all { it in allowed }) throw IllegalArgumentException("Invalid batch createOrders")
            CreateOrder(o.optString("amount"), o.optString("base"), o.optString("counter"), o.optString("sum"), o.optString("type"), o.optString("platform").ifEmpty { null }, o.optString("issuer").ifEmpty { null })
        }
    }

    fun parseCancelOrders(arr: JSONArray?): List<CancelOrder> {
        if (arr == null) return emptyList()
        val allowed = setOf("sequence")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            if (!o.keys().asSequence().all { it in allowed }) throw IllegalArgumentException("Invalid batch cancelOrders")
            CancelOrder(o.optLong("sequence", -1L))
        }
    }

    // ── 语义校验（对齐插件 @swtc/utils isValidAmount）──

    suspend fun isValidTransfer(t: Transfer): Boolean =
        isPositiveDecimal(t.amount) &&
            isValidCurrencyAndIssuer(t.currency, t.issuer, defaultIssuerIfNonNative = false) &&
            WalletSdk.isValidAddress(t.to)

    suspend fun isValidCreateOrder(o: CreateOrder): Boolean =
        (o.type == "buy" || o.type == "sell") &&
            isPositiveDecimal(o.amount) &&
            isPositiveDecimal(o.sum) &&
            isValidOrderSide(o.base, o.issuer) &&
            isValidOrderSide(o.counter, o.issuer)

    /**
     * 挂单的 base/counter 侧校验，对齐插件 createJingtumMiddleware 的分别处理：
     * - native 币种（SWT/SWTC）：无 issuer 概念，忽略传入 issuer；
     * - 非 native：用传入 issuer 或默认发行方 jGa9J9...
     */
    private suspend fun isValidOrderSide(currency: String, issuer: String?): Boolean {
        val normalizedCurrency = if (currency == "SWTC") "SWT" else currency
        if (!isValidCurrency(normalizedCurrency)) return false
        return if (normalizedCurrency == "SWT") {
            true
        } else {
            val effectiveIssuer = issuer?.takeIf { it.isNotEmpty() } ?: "jGa9J9TkqtBcUoHe2zqhVFFbgUVED6o9or"
            WalletSdk.isValidAddress(effectiveIssuer)
        }
    }

    fun isPositiveDecimal(value: String): Boolean =
        value.toBigDecimalOrNull()?.let { it > java.math.BigDecimal.ZERO } ?: false

    fun isValidCurrency(currency: String): Boolean = CURRENCY_REGEX.matches(currency)

    suspend fun isValidCurrencyAndIssuer(
        currency: String?,
        issuer: String?,
        defaultIssuerIfNonNative: Boolean
    ): Boolean {
        val normalizedCurrency = currency?.let { if (it == "SWTC") "SWT" else it } ?: return false
        if (!isValidCurrency(normalizedCurrency)) return false
        val normalizedIssuer = issuer ?: ""
        return if (normalizedCurrency == "SWT") {
            normalizedIssuer.isEmpty()
        } else {
            val effectiveIssuer =
                if (normalizedIssuer.isEmpty() && defaultIssuerIfNonNative) "jGa9J9TkqtBcUoHe2zqhVFFbgUVED6o9or"
                else normalizedIssuer
            effectiveIssuer.isNotEmpty() && WalletSdk.isValidAddress(effectiveIssuer)
        }
    }

    // ── 构建三类交易（转账对齐 app 单笔：Fee=0.01；挂单/撤单走 jingtum-lib）──

    suspend fun buildTxs(
        from: String,
        transfers: List<Transfer>,
        createOrders: List<CreateOrder>,
        cancelOrders: List<CancelOrder>
    ): List<JSONObject> {
        val txs = mutableListOf<JSONObject>()
        transfers.forEach { t ->
            // 转账字段对齐 app 层 SwtcSendViewModel：Fee 固定 0.01，本币判定 currency=="SWT" && issuer 空。
            // 不用 WalletSdk.buildSwtcPayment：其 Fee=fee/1e6、硬编码 wallet.getIssuer()，与单笔转账不一致。
            val normalizedCurrency = t.currency?.let { if (it == "SWTC") "SWT" else it }
            val normalizedIssuer = t.issuer ?: ""
            val amountObj: Any =
                if (normalizedCurrency == "SWT" && normalizedIssuer.isEmpty()) {
                    t.amount
                } else {
                    JSONObject().apply {
                        put("value", t.amount)
                        put("currency", normalizedCurrency)
                        put("issuer", normalizedIssuer)
                    }
                }
            txs.add(
                JSONObject().apply {
                    put("Account", from)
                    put("TransactionType", "Payment")
                    put("Destination", t.to)
                    put("Amount", amountObj)
                    put("Fee", "0.01")
                    if (!t.memo.isNullOrBlank()) {
                        put(
                            "Memos",
                            JSONArray().apply {
                                put(
                                    JSONObject().apply {
                                        put(
                                            "Memo",
                                            JSONObject().apply {
                                                put("MemoType", "text/plain")
                                                put("MemoFormat", "utf-8")
                                                put("MemoData", t.memo)
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }
        createOrders.forEach { o ->
            txs.add(
                JSONObject(
                    WalletSdk.buildSwtcCreateOrder(
                        address = from,
                        amount = o.amount,
                        base = o.base,
                        counter = o.counter,
                        sum = o.sum,
                        type = o.type,
                        platform = o.platform,
                        issuer = o.issuer
                    )
                )
            )
        }
        cancelOrders.forEach { c ->
            txs.add(
                JSONObject(
                    WalletSdk.buildSwtcCancelOrder(address = from, sequence = c.sequence)
                )
            )
        }
        return txs
    }
}
