package com.flowstate.desktop

import com.flowstate.desktop.cloud.Http
import com.flowstate.desktop.cloud.RestJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A newer Windows installer on the GitHub release page (the same releases the Android updater reads). */
data class DesktopUpdate(val version: String, val msiUrl: String, val pageUrl: String)

object UpdateCheck {
    private const val LATEST = "https://api.github.com/repos/Xcaliber-hub/flowstate/releases/latest"

    /** Null when up to date, offline, or the latest release has no .msi yet. */
    fun latest(current: String = FlowstateConfig.VERSION): DesktopUpdate? = runCatching {
        val root = RestJson.parseToJsonElement(
            Http.send("GET", LATEST, headers = mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "Flowstate-Windows"))
        ).jsonObject
        parse(root, current)
    }.getOrNull()

    internal fun parse(root: JsonObject, current: String): DesktopUpdate? {
        if (root["draft"]?.jsonPrimitive?.booleanOrNull == true || root["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null
        val version = root["tag_name"]?.jsonPrimitive?.contentOrNull?.trimStart('v', 'V') ?: return null
        if (!isNewer(version, current)) return null
        val msi = (root["assets"] as? JsonArray)?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".msi", ignoreCase = true) == true } ?: return null
        return DesktopUpdate(version, msi["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null, root["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    /** Same comparison as the Android UpdateChecker: numeric parts, suffixes ignored. */
    fun isNewer(remote: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val r = parts(remote); val c = parts(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val d = r.getOrElse(i) { 0 } - c.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
