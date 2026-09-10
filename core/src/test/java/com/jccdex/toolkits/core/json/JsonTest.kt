package com.jccdex.toolkits.core.json

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JsonTest {
    @Test
    fun `safeParseObject returns null for blank or malformed input`() {
        assertNull(Json.safeParseObject(null))
        assertNull(Json.safeParseObject(""))
        assertNull(Json.safeParseObject("   "))
        assertNull(Json.safeParseObject("not json"))
        assertNull(Json.safeParseObject("[1,2,3]")) // 数组不是对象
    }

    @Test
    fun `safeParseObject parses valid object`() {
        val obj = Json.safeParseObject("""{"a":1,"b":"x"}""")
        assertNotNull(obj)
        assertEquals(1, obj!!.optInt("a"))
        assertEquals("x", obj.optString("b"))
    }

    @Test
    fun `copy deep-copies nested content`() {
        val original = JSONObject()
        original.put("name", "vc")
        original.put("nested", JSONObject().put("id", "n-1"))
        original.put("arr", JSONArray().put(1).put(2))

        val copy = Json.copy(original)

        assertEquals("vc", copy.optString("name"))
        assertEquals("n-1", copy.optJSONObject("nested").optString("id"))
        assertEquals(2, copy.optJSONArray("arr").length())

        // 修改原对象不影响副本
        original.getJSONObject("nested").put("id", "changed")
        original.getJSONArray("arr").put(0, 99)
        assertEquals("n-1", copy.optJSONObject("nested").optString("id"))
        assertEquals(1, copy.optJSONArray("arr").optInt(0))
    }

    @Test
    fun `copy result is independent of later top-level edits`() {
        val original = JSONObject().put("k", "v")
        val copy = Json.copy(original)
        original.put("k", "w")
        assertEquals("v", copy.optString("k"))
        assertTrue(copy.has("k"))
    }

    /**
     * 反方向：改动副本（含嵌套对象/数组内对象）不得回流到原件。
     * 既有用例只覆盖了"改原件不影响副本"，而 `Json.copy` 的调用方多是拿它当快照用。
     */
    @Test
    fun `editing a copy does not leak back into the original`() {
        val original =
            JSONObject()
                .put("nested", JSONObject().put("id", "n-1"))
                .put("arr", JSONArray().put(JSONObject().put("v", 1)))

        val copy = Json.copy(original)

        copy.getJSONObject("nested").put("id", "changed")
        copy.getJSONArray("arr").getJSONObject(0).put("v", 99)

        assertEquals("n-1", original.getJSONObject("nested").optString("id"))
        assertEquals(1, original.getJSONArray("arr").getJSONObject(0).optInt("v"))
    }

    @Test
    fun `safeParseObject matches legacy runCatching semantics on representative inputs`() {
        // 与被替换的旧写法逐一对拍，证明行为等价（等价性回归护栏）
        fun legacy(text: String?): JSONObject? = runCatching { JSONObject(text) }.getOrNull()

        val inputs =
            listOf(
                null,
                "",
                "   ",
                "not json",
                "[1,2,3]",
                "123",
                "true",
                "{}",
                """{"a":1}""",
                // 前导/尾随空白
                """ {"a":1} """,
                """{"nested":{"id":"n"},"arr":[1,true,null]}""",
                """{"s":"x","d":1.25}"""
            )
        for (input in inputs) {
            val expected = legacy(input)
            val actual = Json.safeParseObject(input)
            assertEquals("input=$input", expected?.toString(), actual?.toString())
        }
    }

    @Test
    fun `copy matches legacy toString reparse`() {
        val original = JSONObject()
        original.put("n", 1)
        original.put("f", 1.5)
        original.put("flag", true)
        original.put("nullVal", JSONObject.NULL)
        original.put("nested", JSONObject().put("id", "x"))

        val legacy = JSONObject(original.toString())
        val copy = Json.copy(original)

        assertEquals(legacy.toString(), copy.toString())
    }
}
