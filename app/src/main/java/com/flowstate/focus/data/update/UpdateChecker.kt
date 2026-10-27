package com.flowstate.focus.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** A newer release published on GitHub, with the APK asset to download. */
data class ReleaseInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val pageUrl: String,
)

/**
 * Reads the latest published GitHub release of the app. The release must carry an `.apk` asset and a
 * tag like `v1.1.5`. Uses the unauthenticated REST API (60 requests an hour per IP), which is plenty
 * for one check per app launch.
 */
@Singleton
class UpdateChecker @Inject constructor() {

    suspend fun latestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Flowstate-Android")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                parse(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    companion object {
        const val OWNER = "Xcaliber-hub"
        const val REPO = "flowstate"
        private const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

        internal fun parse(json: String): ReleaseInfo? {
            val root = JSONObject(json)
            if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
            val assets = root.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return ReleaseInfo(
                        version = root.optString("tag_name").trimStart('v', 'V'),
                        notes = root.optString("body").trim(),
                        apkUrl = a.getString("browser_download_url"),
                        apkSizeBytes = a.optLong("size"),
                        pageUrl = root.optString("html_url"),
                    )
                }
            }
            return null
        }

        /** True when [remote] (e.g. "1.1.5") is newer than [current] (e.g. "1.1.4"). Ignores suffixes like "-beta". */
        fun isNewer(remote: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val r = parts(remote)
            val c = parts(current)
            for (i in 0 until maxOf(r.size, c.size)) {
                val diff = r.getOrElse(i) { 0 } - c.getOrElse(i) { 0 }
                if (diff != 0) return diff > 0
            }
            return false
        }
    }
}
