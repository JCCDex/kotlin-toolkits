package com.jccdex.toolkits.nft.remote

import android.app.Application
import com.jccdex.toolkits.nft.model.NftMetadataFields
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SWTC 元数据解析的空值语义。
 *
 * 这些用例是为 `7dbf548`（JSON 解析与空白过滤收敛到 core）补的回归护栏：该提交把
 * 28 处手写 `takeIf { it.isNotBlank() }` 与 6 处 `runCatching { JSONObject(x) }`
 * 换成了 `notBlankOrNull()` / `Json.safeParseObject()`，本文件把"空/空白/非法输入
 * 得到什么"逐条钉住，避免后续再改时行为静默漂移。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SwtcNftMetadataParserTest {
    // ── extractSwtcMetadataUri ─────────────────────────────────────────────

    @Test
    fun extractSwtcMetadataUri_returnsNullForBlankPayload() {
        assertThat(extractSwtcMetadataUri(null)).isNull()
        assertThat(extractSwtcMetadataUri("")).isNull()
        assertThat(extractSwtcMetadataUri("   ")).isNull()
    }

    @Test
    fun extractSwtcMetadataUri_returnsNullForMalformedPayload() {
        // 非 JSON、JSON 对象（不是数组）、数组里没有 TokenInfo 都应为 null，且不抛异常
        assertThat(extractSwtcMetadataUri("not json")).isNull()
        assertThat(extractSwtcMetadataUri("{}")).isNull()
        assertThat(extractSwtcMetadataUri("[1,2,3]")).isNull()
        assertThat(extractSwtcMetadataUri("""[{"Other":{}}]""")).isNull()
    }

    @Test
    fun extractSwtcMetadataUri_decodesHexEncodedTokenInfo() {
        val payload =
            """[{"TokenInfo":{"InfoType":"${hex("tokenUri")}","InfoData":"${hex("ipfs://QmTestUri")}"}}]"""

        assertThat(extractSwtcMetadataUri(payload))
            .isEqualTo("${DEFAULT_IPFS_GATEWAY_BASE_URL}QmTestUri")
    }

    @Test
    fun extractSwtcMetadataUri_ignoresNonTokenUriInfoTypes() {
        val payload =
            """[{"TokenInfo":{"InfoType":"${hex("otherType")}","InfoData":"${hex("ipfs://QmIgnored")}"}}]"""

        assertThat(extractSwtcMetadataUri(payload)).isNull()
    }

    // ── extractMetadataFields ──────────────────────────────────────────────

    @Test
    fun extractMetadataFields_returnsEmptyFieldsForBlankOrMalformedBody() {
        val expected = NftMetadataFields(null, null, null)

        assertThat(extractMetadataFields("", "ipfs://QmMeta")).isEqualTo(expected)
        assertThat(extractMetadataFields("   ", "ipfs://QmMeta")).isEqualTo(expected)
        assertThat(extractMetadataFields("not json", "ipfs://QmMeta")).isEqualTo(expected)
        // JSON 数组不是元数据对象
        assertThat(extractMetadataFields("[1,2]", "ipfs://QmMeta")).isEqualTo(expected)
    }

    @Test
    fun extractMetadataFields_readsNestedDataPayload() {
        val body =
            """
            {
              "data": {
                "name": "Cat",
                "description": "A cat",
                "image": "ipfs://QmImage"
              }
            }
            """.trimIndent()

        val fields = extractMetadataFields(body, "ipfs://QmMeta")

        assertThat(fields.name).isEqualTo("Cat")
        assertThat(fields.description).isEqualTo("A cat")
        assertThat(fields.image).isEqualTo("${DEFAULT_IPFS_GATEWAY_BASE_URL}QmImage")
    }

    @Test
    fun extractMetadataFields_fallsBackToRootWhenDataIsAbsent() {
        val body = """{"name":"Root name","image":"https://example.com/a.png"}"""

        val fields = extractMetadataFields(body, "ipfs://QmMeta")

        assertThat(fields.name).isEqualTo("Root name")
        assertThat(fields.image).isEqualTo("https://example.com/a.png")
    }

    /**
     * 收敛后的判空语义：空白字符串等同于"字段不存在"，不能原样透出给 UI。
     */
    @Test
    fun extractMetadataFields_treatsBlankStringsAsAbsent() {
        val body = """{"name":"   ","description":"\t","image":"  "}"""

        val fields = extractMetadataFields(body, "ipfs://QmMeta")

        assertThat(fields.name).isNull()
        assertThat(fields.description).isNull()
        assertThat(fields.image).isNull()
    }

    private fun hex(value: String): String = value.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
}
