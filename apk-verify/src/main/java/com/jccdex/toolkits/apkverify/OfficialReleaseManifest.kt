package com.jccdex.toolkits.apkverify

import android.content.Context
import org.json.JSONObject

/**
 * A single release entry in [OfficialReleaseManifest].
 */
data class ReleaseEntry(
    val versionCode: Int,
    val versionName: String,
    val apkSha256: String
)

/**
 * Deserialized representation of `assets/official_release_manifest.json`.
 */
data class OfficialReleaseManifest(
    val signingCertSha256: String,
    val releases: List<ReleaseEntry>
)

/**
 * Loads [OfficialReleaseManifest] from the bundled asset file.
 */
object OfficialReleaseManifestLoader {
    private const val ASSET_PATH = "official_release_manifest.json"

    /**
     * Reads and parses the manifest from assets.
     * Returns null when the file is missing or malformed.
     */
    fun load(context: Context): OfficialReleaseManifest? =
        try {
            val json =
                context.assets
                    .open(ASSET_PATH)
                    .bufferedReader()
                    .use { it.readText() }
            parse(json)
        } catch (_: Exception) {
            null
        }

    /** Test-only entry point for parsing manifest JSON without assets. */
    internal fun parseFromJson(json: String): OfficialReleaseManifest? = parse(json)

    private fun parse(json: String): OfficialReleaseManifest? {
        return try {
            val root = JSONObject(json)
            val signingCertSha256 = root.optString("signingCertSha256", "")
            val releasesArray = root.optJSONArray("releases") ?: return null

            val releases = mutableListOf<ReleaseEntry>()
            for (i in 0 until releasesArray.length()) {
                val entry = releasesArray.getJSONObject(i)
                releases.add(
                    ReleaseEntry(
                        versionCode = entry.getInt("versionCode"),
                        versionName = entry.getString("versionName"),
                        apkSha256 = entry.optString("apkSha256", "")
                    )
                )
            }

            OfficialReleaseManifest(
                signingCertSha256 = signingCertSha256,
                releases = releases
            )
        } catch (_: Exception) {
            null
        }
    }
}
