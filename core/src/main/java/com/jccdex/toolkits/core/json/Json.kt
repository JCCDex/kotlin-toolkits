package com.jccdex.toolkits.core.json

import com.jccdex.toolkits.core.text.notBlankOrNull
import org.json.JSONObject

/**
 * Safe org.json reading helpers (C-4).
 *
 * JSON strategy (project convention): **org.json** for untyped/dynamic JSON (DID documents, RPC
 * responses, bridge messages); **Gson** for typed data-class deserialization — never mix the two
 * for the same job in one module. org.json is platform-provided on Android (android.jar).
 */
fun JSONObject.optStringSafe(key: String): String? = optString(key).notBlankOrNull()

fun JSONObject.optJSONObjectSafe(key: String): JSONObject? = optJSONObject(key)

/**
 * 解析与深拷贝的收敛工具：替代各模块
 * `runCatching { JSONObject(x) }.getOrNull()` 与 `JSONObject(obj.toString())` 的手写样板。
 */
object Json {
    /**
     * 安全解析 JSON 对象字符串；空白或非法输入（含数组/标量）返回 null。
     */
    fun safeParseObject(text: String?): JSONObject? {
        if (text.isNullOrBlank()) return null
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 结构化深拷贝一个 JSONObject（含嵌套对象/数组）。
     * 与原 `JSONObject(obj.toString())` 重新解析语义一致。
     */
    fun copy(obj: JSONObject): JSONObject = JSONObject(obj.toString())
}
