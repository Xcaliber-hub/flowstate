package com.flowstate.desktop.cloud

import com.flowstate.desktop.FlowstateConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

val RestJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** A REST call that failed. [status] is the HTTP code; [reason] the API's error status, e.g. PERMISSION_DENIED. */
class CloudException(val status: Int, val reason: String, message: String) : Exception(message) {
    val permissionDenied: Boolean get() = status == 403 || reason == "PERMISSION_DENIED"
    val notFound: Boolean get() = status == 404 || reason == "NOT_FOUND"
}

object Http {
    val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

    fun send(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap(), contentType: String = "application/json"): String {
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
        headers.forEach { (k, v) -> b.header(k, v) }
        if (body != null) b.header("Content-Type", contentType)
        b.method(method, if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody())
        val res = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() !in 200..299) throw toError(res.statusCode(), res.body())
        return res.body()
    }

    private fun toError(code: Int, body: String): CloudException {
        val err = runCatching { RestJson.parseToJsonElement(body).jsonObject["error"] }.getOrNull()
        val obj = err as? JsonObject
        val msg = obj?.get("message")?.jsonPrimitive?.contentOrNull ?: (err as? JsonPrimitive)?.contentOrNull ?: "HTTP $code"
        val status = obj?.get("status")?.jsonPrimitive?.contentOrNull ?: msg
        return CloudException(code, status, msg)
    }

    fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8) }
}

// ---- Firestore typed values <-> plain Kotlin ----

/** Plain Kotlin value to a Firestore REST Value. Supports String, Boolean, Int/Long, Double, Map, List, null and Instant. */
fun fsValue(v: Any?): JsonObject = when (v) {
    null -> buildJsonObject { put("nullValue", JsonNull) }
    is String -> buildJsonObject { put("stringValue", v) }
    is Boolean -> buildJsonObject { put("booleanValue", v) }
    is Int -> buildJsonObject { put("integerValue", v.toString()) }
    is Long -> buildJsonObject { put("integerValue", v.toString()) }
    is Double -> buildJsonObject { put("doubleValue", v) }
    is Float -> buildJsonObject { put("doubleValue", v.toDouble()) }
    is Instant -> buildJsonObject { put("timestampValue", v.toString()) }
    is Map<*, *> -> buildJsonObject { put("mapValue", buildJsonObject { put("fields", fsFields(v.entries.associate { it.key.toString() to it.value })) }) }
    is List<*> -> buildJsonObject { put("arrayValue", buildJsonObject { put("values", buildJsonArray { v.forEach { add(fsValue(it)) } }) }) }
    else -> error("Unsupported Firestore value ${v::class}")
}

fun fsFields(map: Map<String, Any?>): JsonObject = buildJsonObject { map.forEach { (k, v) -> put(k, fsValue(v)) } }

/** Firestore REST Value to plain Kotlin: strings, Long, Double, Boolean, Map, List, Instant (timestamps) or null. */
fun fromFs(value: JsonElement?): Any? {
    val o = value as? JsonObject ?: return null
    o["stringValue"]?.let { return it.jsonPrimitive.content }
    o["integerValue"]?.let { return it.jsonPrimitive.content.toLongOrNull() }
    o["doubleValue"]?.let { return it.jsonPrimitive.content.toDoubleOrNull() }
    o["booleanValue"]?.let { return it.jsonPrimitive.booleanOrNull }
    o["timestampValue"]?.let { return runCatching { Instant.parse(it.jsonPrimitive.content) }.getOrNull() }
    o["mapValue"]?.let { m -> return (m.jsonObject["fields"] as? JsonObject)?.mapValues { fromFs(it.value) } ?: emptyMap<String, Any?>() }
    o["arrayValue"]?.let { a -> return (a.jsonObject["values"] as? JsonArray)?.map { fromFs(it) } ?: emptyList<Any?>() }
    return null
}

/** One Firestore document, decoded. [id] is the last path segment. */
data class FsDoc(val name: String, val fields: Map<String, Any?>, val updateTime: Instant?) {
    val id: String get() = name.substringAfterLast('/')
    fun string(k: String): String? = fields[k] as? String
    fun long(k: String): Long? = (fields[k] as? Number)?.toLong()
    fun bool(k: String): Boolean? = fields[k] as? Boolean
    fun millis(k: String): Long? = (fields[k] as? Instant)?.toEpochMilli()
    @Suppress("UNCHECKED_CAST") fun map(k: String): Map<String, Any?>? = fields[k] as? Map<String, Any?>
    fun strings(k: String): List<String> = (fields[k] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    companion object {
        fun parse(o: JsonObject): FsDoc = FsDoc(
            name = o["name"]?.jsonPrimitive?.content.orEmpty(),
            fields = (o["fields"] as? JsonObject)?.mapValues { fromFs(it.value) } ?: emptyMap(),
            updateTime = o["updateTime"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
    }
}

/**
 * Minimal Cloud Firestore REST client (get, runQuery, commit) authenticated with the user's Firebase
 * ID token, so the same security rules apply as on Android.
 */
class Firestore(private val token: suspend () -> String) {
    private val root = "projects/${FlowstateConfig.FIREBASE_PROJECT_ID}/databases/(default)/documents"
    private val base = "https://firestore.googleapis.com/v1/"

    fun docName(path: String) = "$root/$path"

    private suspend fun headers() = mapOf("Authorization" to "Bearer ${token()}")

    /** Null when the document does not exist. */
    suspend fun get(path: String): FsDoc? = try {
        FsDoc.parse(RestJson.parseToJsonElement(Http.send("GET", base + docName(path), headers = headers())).jsonObject)
    } catch (e: CloudException) {
        if (e.notFound) null else throw e
    }

    /** Runs a structured query under [parentPath] ("" for the database root). */
    suspend fun runQuery(parentPath: String, structuredQuery: JsonObject): List<FsDoc> {
        val parent = if (parentPath.isEmpty()) root else "$root/$parentPath"
        val body = buildJsonObject { put("structuredQuery", structuredQuery) }.toString()
        val res = RestJson.parseToJsonElement(Http.send("POST", "$base$parent:runQuery", body, headers())).jsonArray
        return res.mapNotNull { (it.jsonObject["document"] as? JsonObject)?.let(FsDoc::parse) }
    }

    /** Applies [writes] atomically (at most 500). Returns the commit time. */
    suspend fun commit(writes: List<JsonObject>): Instant? {
        if (writes.isEmpty()) return null
        val body = buildJsonObject { put("writes", JsonArray(writes)) }.toString()
        val res = RestJson.parseToJsonElement(Http.send("POST", "$base$root:commit", body, headers())).jsonObject
        return res["commitTime"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
    }

    /**
     * A write that replaces the whole document (like Android's set() without merge) or, with [mask],
     * only the listed fields (like set(..., merge)). [serverTimeFields] are set to the server's
     * request time, the REST form of FieldValue.serverTimestamp().
     */
    fun setWrite(path: String, fields: Map<String, Any?>, serverTimeFields: List<String> = emptyList(), mask: List<String>? = null, mustExist: Boolean? = null): JsonObject = buildJsonObject {
        put("update", buildJsonObject { put("name", docName(path)); put("fields", fsFields(fields)) })
        if (mask != null) put("updateMask", buildJsonObject { put("fieldPaths", buildJsonArray { mask.forEach { add(JsonPrimitive(it)) } }) })
        if (serverTimeFields.isNotEmpty()) put("updateTransforms", buildJsonArray {
            serverTimeFields.forEach { f -> add(buildJsonObject { put("fieldPath", f); put("setToServerValue", "REQUEST_TIME") }) }
        })
        if (mustExist != null) put("currentDocument", buildJsonObject { put("exists", mustExist) })
    }

    fun deleteWrite(path: String): JsonObject = buildJsonObject { put("delete", docName(path)) }
}

// ---- Structured query helpers ----

object Q {
    fun field(path: String) = buildJsonObject { put("fieldPath", path) }
    fun filter(path: String, op: String, value: Any?) = buildJsonObject {
        put("fieldFilter", buildJsonObject { put("field", field(path)); put("op", op); put("value", fsValue(value)) })
    }
    fun order(path: String, descending: Boolean = false) = buildJsonObject { put("field", field(path)); put("direction", if (descending) "DESCENDING" else "ASCENDING") }
    fun query(collection: String, where: JsonObject? = null, orderBy: List<JsonObject> = emptyList(), limit: Int? = null) = buildJsonObject {
        put("from", buildJsonArray { add(buildJsonObject { put("collectionId", collection) }) })
        if (where != null) put("where", where)
        if (orderBy.isNotEmpty()) put("orderBy", JsonArray(orderBy))
        if (limit != null) put("limit", limit)
    }
}

internal fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.lng(k: String): Long? = this[k]?.jsonPrimitive?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
