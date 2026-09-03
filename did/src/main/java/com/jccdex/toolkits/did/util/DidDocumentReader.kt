package com.jccdex.toolkits.did.util

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared DID document field readers (C-15).
 *
 * Single implementation for Profile service fields and common JSON arrays used by
 * [com.jccdex.toolkits.did.sdk.DidSdk] and [com.jccdex.toolkits.did.service.DidCoreService].
 */
object DidDocumentReader {
    private const val TAG = "DidDocumentReader"

    fun readServices(root: JSONObject): JSONArray =
        root.optJSONArray("service") ?: root.optJSONArray("services") ?: JSONArray()

    fun readProfileField(
        doc: String,
        key: String
    ): String? {
        return try {
            val root = JSONObject(doc)
            val services = readServices(root)
            for (i in 0 until services.length()) {
                val service = services.optJSONObject(i) ?: continue
                if (service.optString("type") != "Profile") continue
                val endpoint = service.optJSONObject("serviceEndpoint") ?: continue
                val value = endpoint.optString(key, "")
                if (value.isNotBlank()) return value
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "readProfileField failed", e)
            null
        }
    }

    fun readJsonArray(
        doc: String,
        key: String
    ): JSONArray =
        try {
            val root = JSONObject(doc)
            root.optJSONArray(key) ?: root.optJSONArray(
                when (key) {
                    "service" -> "services"
                    "verificationMethod" -> "verificationMethods"
                    else -> key
                }
            ) ?: JSONArray()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            JSONArray()
        }
}
