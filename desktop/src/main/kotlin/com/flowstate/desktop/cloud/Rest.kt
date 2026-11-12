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
            name = (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            fields = (o["fields"] as? JsonObject)?.mapValues { fromFs(it.value) } ?: emptyMap(),
            updateTime = o["updateTime"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
    }
}

/**
 * Minimal Cloud Firestore REST client (get, runQuery, commit) authenticated with the user's Firebase
 * ID token, so the same security rules apply as on Android.
 */
class Firestore(private val token: suspend (forceRefresh: Boolean) -> String) {
    private val root = "projects/${FlowstateConfig.FIREBASE_PROJECT_ID}/databases/(default)/documents"
    private val base = "https://firestore.googleapis.com/v1/"

    fun docName(path: String) = "$root/$path"

    /**
     * Runs [call] with a Bearer token. A blank token never goes out (it would be answered as an anonymous
     * request). On 401/403 the call is retried once with a force-refreshed token, which covers an ID
     * token that expired early or was revoked server-side; a real rules denial fails again and is thrown.
     */
    private suspend fun <T> authed(call: (Map<String, String>) -> T): T {
        suspend fun headers(force: Boolean): Map<String, String> {
            val t = token(force)
            if (t.isBlank()) throw CloudException(401, "UNAUTHENTICATED", "Sign in first.")
            return mapOf("Authorization" to "Bearer $t")
        }
        return try {
            call(headers(false))
        } catch (e: CloudException) {
            if (e.status != 401 && e.status != 403) throw e
            call(headers(true))
        }
    }

    /** Null when the document does not exist. */
    suspend fun get(path: String): FsDoc? = try {
        authed { h -> FsDoc.parse(RestJson.parseToJsonElement(Http.send("GET", base + docName(path), headers = h)).jsonObject) }
    } catch (e: CloudException) {
        if (e.notFound) null else throw e
    }

    /** Runs a structured query under [parentPath] ("" for the database root). Throws on any error entry; see [parseRunQuery]. */
    suspend fun runQuery(parentPath: String, structuredQuery: JsonObject): List<FsDoc> {
        val parent = if (parentPath.isEmpty()) root else "$root/$parentPath"
        val body = buildJsonObject { put("structuredQuery", structuredQuery) }.toString()
        return authed { h -> parseRunQuery(Http.send("POST", "$base$parent:runQuery", body, h)) }
    }

    /** Applies [writes] atomically (at most 500). Returns the commit time. */
    suspend fun commit(writes: List<JsonObject>): Instant? {
        if (writes.isEmpty()) return null
        val body = buildJsonObject { put("writes", JsonArray(writes)) }.toString()
        val res = authed { h -> RestJson.parseToJsonElement(Http.send("POST", "$base$root:commit", body, h)).jsonObject }
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

/**
 * Decodes a `:runQuery` response body. The REST API streams a JSON array and can answer HTTP 200 with an
 * `{"error": …}` element (for example when the query fails after the response has started), so a 200 is
 * not proof of success. Rules:
 *  - an `error` element anywhere fails the whole query (a [CloudException] with the element's code and status);
 *  - a body that is not a complete JSON array (cut off, HTML from a proxy) fails as MALFORMED_RESPONSE;
 *  - a `document` without a decodable `name` fails as MALFORMED_RESPONSE rather than being silently dropped;
 *  - entries with only `readTime` / `skippedResults` carry no document and are skipped (an empty result is
 *    exactly one such entry).
 * Callers therefore only ever see a list that the server really returned in full.
 */
fun parseRunQuery(body: String): List<FsDoc> {
    fun malformed(why: String) = CloudException(200, "MALFORMED_RESPONSE", "Firestore returned an unreadable query result ($why).")
    val root = try { RestJson.parseToJsonElement(body) } catch (e: Exception) { throw malformed("not JSON") }
    val array = root as? JsonArray ?: run {
        // A bare error object (some proxies and older endpoints answer this way).
        (root as? JsonObject)?.get("error")?.let { throw queryError(it) }
        throw malformed("not an array")
    }
    val docs = ArrayList<FsDoc>(array.size)
    array.forEach { el ->
        val o = el as? JsonObject ?: throw malformed("entry is not an object")
        o["error"]?.let { throw queryError(it) }
        val d = o["document"] ?: return@forEach
        val doc = (d as? JsonObject)?.let { runCatching { FsDoc.parse(it) }.getOrNull() } ?: throw malformed("document is not an object")
        if (doc.name.isBlank() || doc.id.isBlank()) throw malformed("document without a name")
        docs += doc
    }
    return docs
}

private fun queryError(err: JsonElement): CloudException {
    val o = err as? JsonObject
    val code = (o?.get("code") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it in 400..599 } ?: 500
    val msg = (o?.get("message") as? JsonPrimitive)?.contentOrNull ?: (err as? JsonPrimitive)?.contentOrNull ?: "Query failed"
    val status = (o?.get("status") as? JsonPrimitive)?.contentOrNull ?: "QUERY_ERROR"
    return CloudException(code, status, msg)
}

// ---- Structured query helpers ----

object Q {
    fun field(path: String) = buildJsonObject { put("fieldPath", path) }
    fun filter(path: String, op: String, value: Any?) = buildJsonObject {
        put("fieldFilter", buildJsonObject { put("field", field(path)); put("op", op); put("value", fsValue(value)) })
    }
    fun order(path: String, descending: Boolean = false) = buildJsonObject { put("field", field(path)); put("direction", if (descending) "DESCENDING" else "ASCENDING") }
    fun query(collection: String, where: JsonObject? = null, orderBy: List<JsonObject> = emptyList(), limit: Int? = null, startAt: JsonObject? = null) = buildJsonObject {
        put("from", buildJsonArray { add(buildJsonObject { put("collectionId", collection) }) })
        if (where != null) put("where", where)
        if (orderBy.isNotEmpty()) put("orderBy", JsonArray(orderBy))
        if (startAt != null) put("startAt", startAt)
        if (limit != null) put("limit", limit)
    }

    /**
     * A cursor just after the document [docName] (full resource name) whose ordered field is [at]; for a query
     * ordered by that field and then `__name__`. Paging on the timestamp alone skips or repeats documents that
     * share it, and a batch commit gives every document in it the same server timestamp.
     */
    fun cursorAfter(at: java.time.Instant, docName: String) = buildJsonObject {
        put("values", buildJsonArray {
            add(fsValue(at))
            add(buildJsonObject { put("referenceValue", docName) })
        })
        put("before", false)
    }
}

internal fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.lng(k: String): Long? = this[k]?.jsonPrimitive?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
