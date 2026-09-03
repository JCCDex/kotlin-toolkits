package com.jccdex.toolkits.core.json

import org.json.JSONObject

/**
 * Safe org.json reading helpers (C-4).
 *
 * JSON strategy (project convention): **org.json** for untyped/dynamic JSON (DID documents, RPC
 * responses, bridge messages); **Gson** for typed data-class deserialization — never mix the two
 * for the same job in one module. org.json is platform-provided on Android (android.jar).
 */
fun JSONObject.optStringSafe(key: String): String? = optString(key).takeIf { it.isNotBlank() }

fun JSONObject.optJSONObjectSafe(key: String): JSONObject? = optJSONObject(key)
