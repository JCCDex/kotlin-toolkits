package com.jccdex.toolkits.dappconnect

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 注入到 WebView 的 JS 片段必须对来自宿主的字符串做完整转义（L-22）。
 *
 * 覆盖此前遗漏的一处：[DAppConnectSdk.loadEip6963IconOverrideJs] 原先自己写了一套
 * 只处理 `\` 与 `'` 的转义，漏掉 `\n` / `\r` / U+2028 / U+2029；单引号字面量里的裸换行
 * 会让整段脚本语法错误，行分隔符还能越出字符串字面量。现在统一走 jsQuote。
 */
class DAppConnectJsInjectionTest {
    /** 异常载荷：引号、反斜杠、换行、以及 JS 里的行分隔符。 */
    private val hostile = "a\"b\\c\nd\re\u2028f\u2029g"

    @Test
    fun `loadAddressJs escapes quotes backslashes and line separators`() {
        val js = DAppConnectSdk.loadAddressJs(hostile, isSwtc = false)

        assertTrue(js.contains("\\\""), "double quote must be escaped")
        assertTrue(js.contains("\\\\"), "backslash must be escaped")
        assertTrue(js.contains("\\n"), "newline must be escaped")
        assertTrue(js.contains("\\r"), "carriage return must be escaped")
        assertTrue(js.contains("\\u2028"), "U+2028 must be escaped")
        assertTrue(js.contains("\\u2029"), "U+2029 must be escaped")
        assertFalse(js.contains('\u2028'), "no raw U+2028 may remain")
        assertFalse(js.contains('\u2029'), "no raw U+2029 may remain")
        assertFalse(js.contains('\n'), "single-line snippet must not contain a raw newline")
    }

    @Test
    fun `loadInitJs escapes both chainId and rpcUrl`() {
        val js = DAppConnectSdk.loadInitJs(hostile, hostile)

        assertTrue(js.contains("\\u2028"))
        assertTrue(js.contains("\\u2029"))
        assertFalse(js.contains('\u2028'))
        assertFalse(js.contains('\u2029'))

        // loadInitJs 本身是多行模板：载荷里的换行若未转义就会多出若干行，
        // 因此用"行数与良性输入一致"来断言转义生效。
        val benignLines = DAppConnectSdk.loadInitJs("0x1", "https://rpc.example").lines().size
        assertTrue(js.lines().size == benignLines, "payload newlines must not add lines")
    }

    @Test
    fun `loadEip6963IconOverrideJs escapes line separators too`() {
        val js = DAppConnectSdk.loadEip6963IconOverrideJs(hostile)

        assertTrue(js.contains("\\u2028"))
        assertTrue(js.contains("\\u2029"))
        assertTrue(js.contains("\\n"))
        assertFalse(js.contains('\u2028'))
        assertFalse(js.contains('\u2029'))
        assertFalse(js.contains('\n'))
    }

    @Test
    fun `normal data uri icon survives unchanged`() {
        val icon = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="

        val js = DAppConnectSdk.loadEip6963IconOverrideJs(icon)

        assertTrue(js.contains(icon))
    }
}
