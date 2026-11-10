package com.flowstate.desktop

import com.flowstate.desktop.cloud.AuthClient
import com.flowstate.desktop.cloud.CloudException
import com.flowstate.desktop.cloud.EmptyListGuard
import com.flowstate.desktop.cloud.parseRunQuery
import com.flowstate.desktop.cloud.pollBackoffMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 1.8.0 desktop fix: the Groups list must not empty itself on one bad runQuery poll. */
class GroupsPollTest {

    private val groupDoc = """
        {"document":{"name":"projects/p/databases/(default)/documents/groups/ABCDEFGHJKLM",
          "fields":{"name":{"stringValue":"Physics squad"},"memberIds":{"arrayValue":{"values":[{"stringValue":"u1"}]}}},
          "updateTime":"2026-11-10T10:00:00Z"},"readTime":"2026-11-10T10:00:01Z"}
    """.trimIndent()

    // ---- runQuery parsing ----

    @Test fun documentsAreDecoded() {
        val docs = parseRunQuery("[$groupDoc]")
        assertEquals(1, docs.size)
        assertEquals("ABCDEFGHJKLM", docs[0].id)
        assertEquals("Physics squad", docs[0].string("name"))
        assertEquals(listOf("u1"), docs[0].strings("memberIds"))
    }

    @Test fun readTimeOnlyIsARealEmptyResult() {
        assertTrue(parseRunQuery("""[{"readTime":"2026-11-10T10:00:01Z"}]""").isEmpty())
        assertTrue(parseRunQuery("""[{"readTime":"2026-11-10T10:00:01Z","skippedResults":0}]""").isEmpty())
    }

    @Test fun errorElementFailsTheQueryEvenWithDocuments() {
        val e = assertFailsWith<CloudException> {
            parseRunQuery("""[$groupDoc,{"error":{"code":403,"message":"Missing or insufficient permissions.","status":"PERMISSION_DENIED"}}]""")
        }
        assertEquals(403, e.status)
        assertTrue(e.permissionDenied)
        val alone = assertFailsWith<CloudException> { parseRunQuery("""[{"error":{"code":503,"message":"unavailable","status":"UNAVAILABLE"}}]""") }
        assertEquals(503, alone.status)
        assertEquals("UNAVAILABLE", alone.reason)
        // Error without a usable code still fails.
        assertEquals(500, assertFailsWith<CloudException> { parseRunQuery("""[{"error":{"message":"x"}}]""") }.status)
        // A bare error object instead of an array.
        assertEquals(401, assertFailsWith<CloudException> { parseRunQuery("""{"error":{"code":401,"message":"no","status":"UNAUTHENTICATED"}}""") }.status)
    }

    @Test fun cutOffOrForeignBodiesFail() {
        assertEquals("MALFORMED_RESPONSE", assertFailsWith<CloudException> { parseRunQuery("[$groupDoc,{\"document\":{\"na") }.reason)
        assertEquals("MALFORMED_RESPONSE", assertFailsWith<CloudException> { parseRunQuery("<html>Proxy error</html>") }.reason)
        assertEquals("MALFORMED_RESPONSE", assertFailsWith<CloudException> { parseRunQuery("""{"ok":true}""") }.reason)
        assertEquals("MALFORMED_RESPONSE", assertFailsWith<CloudException> { parseRunQuery("""[42]""") }.reason)
    }

    @Test fun documentWithoutNameIsAFailureNotADrop() {
        assertFailsWith<CloudException> { parseRunQuery("""[{"document":{"fields":{"name":{"stringValue":"x"}}}}]""") }
        assertFailsWith<CloudException> { parseRunQuery("""[{"document":{"name":{"oops":1}}}]""") }
        assertFailsWith<CloudException> { parseRunQuery("""[{"document":"nope"}]""") }
    }

    // ---- empty guard ----

    @Test fun oneEmptyPollKeepsTheList() {
        val g = EmptyListGuard()
        val current = listOf("a", "b")
        assertNull(g.accept(current, emptyList()))
        // The second consecutive empty success is believed.
        assertEquals(emptyList(), g.accept(current, emptyList<String>()))
    }

    @Test fun aNonEmptyOrFailedPollBreaksTheStreak() {
        val g = EmptyListGuard()
        val current = listOf("a")
        assertNull(g.accept(current, emptyList()))
        assertEquals(listOf("a"), g.accept(current, listOf("a")))
        assertNull(g.accept(current, emptyList()))
        g.onFailure()
        assertNull(g.accept(current, emptyList()))
    }

    @Test fun confirmedLeaveEmptiesAtOnceButOnlyOnce() {
        val g = EmptyListGuard()
        g.allowNextEmpty()
        assertEquals(emptyList(), g.accept(listOf("a"), emptyList<String>()))
        // The permission is used up.
        assertNull(g.accept(listOf("b"), emptyList()))
        // And a successful non-empty poll uses it up too.
        val h = EmptyListGuard()
        h.allowNextEmpty()
        assertEquals(listOf("a"), h.accept(listOf("a"), listOf("a")))
        assertNull(h.accept(listOf("a"), emptyList()))
    }

    @Test fun changesAndFirstLoadPassStraightThrough() {
        val g = EmptyListGuard()
        assertEquals(emptyList(), g.accept(emptyList(), emptyList<String>()))
        assertEquals(listOf("a"), g.accept(emptyList(), listOf("a")))
        assertEquals(listOf("b"), g.accept(listOf("a"), listOf("b")))
        g.reset()
        assertNull(g.accept(listOf("a"), emptyList()))
    }

    // ---- backoff and sign-out rules ----

    @Test fun backoffDoublesAndCaps() {
        assertEquals(0L, pollBackoffMs(0))
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L), (1..4).map { pollBackoffMs(it) })
        assertEquals(300_000L, pollBackoffMs(12))
        assertEquals(300_000L, pollBackoffMs(1_000))
    }

    @Test fun onlyARejectedRefreshTokenSignsOut() {
        assertTrue(AuthClient.isRevoked(CloudException(400, "INVALID_ARGUMENT", "TOKEN_EXPIRED")))
        assertTrue(AuthClient.isRevoked(CloudException(400, "INVALID_ARGUMENT", "USER_DISABLED")))
        assertTrue(AuthClient.isRevoked(CloudException(400, "INVALID_ARGUMENT", "INVALID_REFRESH_TOKEN")))
        assertFalse(AuthClient.isRevoked(CloudException(403, "HTTP 403", "HTTP 403")))
        assertFalse(AuthClient.isRevoked(CloudException(400, "QUOTA_EXCEEDED", "QUOTA_EXCEEDED")))
        assertFalse(AuthClient.isRevoked(CloudException(503, "UNAVAILABLE", "TOKEN_EXPIRED")))
    }
}
