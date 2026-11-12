package com.flowstate.desktop

import com.flowstate.desktop.cloud.FsDoc
import com.flowstate.desktop.cloud.decodeSessionDoc
import com.flowstate.desktop.cloud.formatPullCursor
import com.flowstate.desktop.cloud.parsePullCursor
import com.flowstate.desktop.cloud.sessionPullQuery
import com.flowstate.desktop.store.JsonFile
import com.flowstate.desktop.store.SessionStore
import com.flowstate.desktop.store.SingleInstance
import com.flowstate.desktop.store.StoreJson
import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.domain.SessionSyncPlan
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 1.8.1 "some sessions have somehow disappeared" on Windows. */
class MissingSessionsTest {
    init {
        if (System.getProperty("flowstate.home").isNullOrBlank())
            System.setProperty("flowstate.home", Files.createTempDirectory("fs-missing").toString())
    }

    private fun session(at: Long, cloudId: String? = null, origin: String? = null, owner: String? = null) =
        SessionEntity(startedAt = at, durationMs = 25 * 60_000L, mode = "POMODORO", subject = "Physics", completed = true, cloudId = cloudId, ownerUid = owner, origin = origin)

    private val serializer = ListSerializer(SessionEntity.serializer())

    @Test fun aCorruptHistoryFallsBackToTheBackupInsteadOfLoadingEmpty() {
        val dir = Files.createTempDirectory("fs-corrupt").toFile()
        val f = File(dir, "sessions.json")
        val store = JsonFile(f, serializer) { emptyList() }
        store.load()
        store.save(listOf(session(1L), session(2L))); store.flush()
        store.save(listOf(session(1L), session(2L), session(3L))); store.flush()
        assertTrue(File(dir, "sessions.json.bak").exists(), "the previous good file is kept")
        f.writeText("") // a power cut after the rename, before the data reached the disk
        val loaded = JsonFile(f, serializer) { emptyList() }.load()
        assertEquals(2, loaded.size, "the backup (one write older) is used, never an empty list")
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("sessions.json.corrupt-") }, "the unreadable file is kept for recovery")
    }

    @Test fun aTruncatedHistoryWithoutBackupKeepsEveryCompleteSession() {
        val dir = Files.createTempDirectory("fs-trunc").toFile()
        val f = File(dir, "sessions.json")
        val full = StoreJson.encodeToString(serializer, List(5) { session(it + 1L) })
        f.writeText(full.substring(0, full.length - 30))
        val store = JsonFile(f, serializer, salvage = { t -> com.flowstate.desktop.store.salvageSessions(t) }) { emptyList() }
        assertEquals(4, store.load().size)
    }

    @Test fun aSecondCopyOfTheAppIsRefusedAndAsksTheFirstToShowItself() {
        val dir = Files.createTempDirectory("fs-single").toFile()
        val first = SingleInstance.acquire(dir)
        assertNotNull(first)
        assertNull(SingleInstance.acquire(dir), "a second launch must not run its own copy of the history")
        assertTrue(File(dir, "activate.signal").exists())
        first.release()
        assertNotNull(SingleInstance.acquire(dir)?.also { it.release() }, "after the first copy exits, a launch starts normally")
    }

    @Test fun signOutForgetsOnlyCloudCopiesOfOtherDevicesSessions() {
        val store = SessionStore("sessions-signout-${System.nanoTime()}.json")
        store.insert(session(1L))                                           // this PC, never synced
        store.insert(session(2L, cloudId = "win-me_2", origin = "win-me", owner = "u"))  // this PC, synced
        store.insert(session(3L, cloudId = "android-x_3", origin = "android-x", owner = "u"))  // pulled
        store.insert(session(4L, origin = "android-x"))                     // odd row without a cloud id: kept
        store.deletePulled("win-me")
        assertEquals(listOf(4L, 2L, 1L), store.all.value.map { it.startedAt })
        store.insert(session(5L, cloudId = "android-y_5", origin = "android-y", owner = "other"))
        store.insert(session(6L, cloudId = "android-y_6", origin = "android-y", owner = "u"))
        store.deletePulledOfOtherAccounts("win-me", "u")
        assertEquals(listOf(6L, 4L, 2L, 1L), store.all.value.map { it.startedAt })
    }

    @Test fun pullCursorKeepsFullPrecision() {
        val at = Instant.parse("2026-11-12T03:00:00.123456Z")
        val name = "projects/p/databases/(default)/documents/users/u/sessions/android-a_1"
        assertEquals(at to name, parsePullCursor(formatPullCursor(at, name)))
        assertNull(parsePullCursor("garbage"))
        assertNull(parsePullCursor(""))
    }

    /**
     * A batch upload gives every session in it the same server time. With 400 of them, paging by
     * "updatedAt greater than the newest seen" (until 1.8.0) either skipped 100 for good or returned the same
     * 300 for ever. The cursor on (updatedAt, name) pages through all of them and stops.
     */
    @Test fun pagingThroughOneBatchWithTheSameTimestampGetsEverySessionOnce() {
        val t = Instant.parse("2026-11-12T03:00:00.123456Z")
        val server = List(400) { i -> FsDoc("projects/p/databases/(default)/documents/users/u/sessions/android-a_${1000 + i}",
            mapOf("updatedAt" to t, "startedAt" to (1000L + i), "deviceId" to "android-a", "deleted" to false), t) } +
            FsDoc("projects/p/databases/(default)/documents/users/u/sessions/android-a_9999",
                mapOf("updatedAt" to t.plusSeconds(60), "startedAt" to 9999L, "deviceId" to "android-a"), t)
        val got = mutableListOf<String>()
        var cursor: Pair<Instant, String>? = null
        var pages = 0
        while (pages++ < 10) {
            val page = fakeRunQuery(server, sessionPullQuery(cursor, 300))
            got += page.map { it.id }
            if (page.isEmpty()) break
            cursor = (page.last().fields["updatedAt"] as Instant) to page.last().name
            if (page.size < 300) break
        }
        assertEquals(401, got.size)
        assertEquals(401, got.toSet().size)
        assertTrue(pages <= 3)
        val plan = SessionSyncPlan.plan(server.map(::decodeSessionDoc), "u", "win-me", emptySet(), emptySet())
        assertEquals(401, plan.add.size)
    }

    /** Evaluates the parts of a structured query the session pull uses, the way Firestore does. */
    private fun fakeRunQuery(all: List<FsDoc>, q: JsonObject): List<FsDoc> {
        val sorted = all.sortedWith(compareBy<FsDoc>({ it.fields["updatedAt"] as Instant }, { it.name }))
        val where = q["where"]?.jsonObject?.get("fieldFilter")?.jsonObject
        val min = where?.get("value")?.jsonObject?.get("timestampValue")?.jsonPrimitive?.content?.let(Instant::parse)
        assertTrue(where == null || where["op"]!!.jsonPrimitive.content == "GREATER_THAN_OR_EQUAL")
        val order = (q["orderBy"] as JsonArray).map { it.jsonObject["field"]!!.jsonObject["fieldPath"]!!.jsonPrimitive.content }
        assertEquals(listOf("updatedAt", "__name__"), order)
        val start = q["startAt"]?.jsonObject
        val startAt = start?.get("values")?.jsonArray?.let { v ->
            Instant.parse(v[0].jsonObject["timestampValue"]!!.jsonPrimitive.content) to v[1].jsonObject["referenceValue"]!!.jsonPrimitive.content
        }
        assertTrue(start == null || start["before"]!!.jsonPrimitive.content == "false")
        val limit = q["limit"]!!.jsonPrimitive.content.toInt()
        return sorted.filter { d ->
            val at = d.fields["updatedAt"] as Instant
            (min == null || at >= min) && (startAt == null || at > startAt.first || (at == startAt.first && d.name > startAt.second))
        }.take(limit)
    }
}
