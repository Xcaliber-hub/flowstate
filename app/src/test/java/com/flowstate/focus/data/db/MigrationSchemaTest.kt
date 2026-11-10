package com.flowstate.focus.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * 1.7.2: checks the hand-written migrations against the schema Room expects.
 *
 * A database is created with the version 1 tables (as 1.5.x created them), migrated 1→2→3→4 with
 * [AppDatabase.MIGRATION_1_2], [AppDatabase.MIGRATION_2_3] and (1.8.0) [AppDatabase.MIGRATION_3_4], and its tables, columns (type, NOT NULL,
 * primary key) and indices are compared with a fresh database built from the exported v4 schema
 * (app/schemas). A mismatch here is exactly what makes Room throw "Migration didn't properly handle"
 * on upgrade. Runs on the JVM with sqlite-jdbc; MigrationTestHelper would need a device.
 */
class MigrationSchemaTest {

    private val v1 = listOf(
        "CREATE TABLE IF NOT EXISTS `sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAt` INTEGER NOT NULL, " +
            "`durationMs` INTEGER NOT NULL, `mode` TEXT NOT NULL, `subject` TEXT NOT NULL, `completed` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_sessions_startedAt` ON `sessions` (`startedAt`)",
        "CREATE TABLE IF NOT EXISTS `tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
            "`category` TEXT NOT NULL, `priority` INTEGER NOT NULL, `deadline` INTEGER, `done` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
    )

    private fun schemaFile(version: Int): File {
        val candidates = listOf(
            File("schemas/com.flowstate.focus.data.db.AppDatabase/$version.json"),
            File("app/schemas/com.flowstate.focus.data.db.AppDatabase/$version.json"),
        )
        return candidates.firstOrNull { it.exists() } ?: error("exported schema $version.json not found (build once to export it)")
    }

    /** The CREATE statements of an exported Room schema, in order. */
    private fun createStatements(json: String): List<String> {
        val tables = Regex("\"tableName\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        // Each entity's createSql and its indices' createSql use ${TABLE_NAME}; walk them in file order.
        val out = mutableListOf<String>()
        var table = ""
        val token = Regex("\"tableName\"\\s*:\\s*\"([^\"]+)\"|\"createSql\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        token.findAll(json).forEach { m ->
            if (m.groupValues[1].isNotEmpty()) table = m.groupValues[1]
            else out += m.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\").replace("\${TABLE_NAME}", table)
        }
        assertTrue("no tables in schema", tables.isNotEmpty())
        return out
    }

    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    /** Table -> sorted column descriptions + sorted index descriptions. */
    private fun Connection.describe(): Map<String, List<String>> {
        val tables = createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata')").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }
        return tables.associateWith { t ->
            val cols = createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(`$t`)").use { rs ->
                    generateSequence {
                        if (rs.next()) "col ${rs.getString("name")} ${rs.getString("type").uppercase()} notnull=${rs.getInt("notnull")} pk=${rs.getInt("pk")}" else null
                    }.toList()
                }
            }
            val idx = createStatement().use { st ->
                st.executeQuery("PRAGMA index_list(`$t`)").use { rs ->
                    generateSequence { if (rs.next()) rs.getString("name") to rs.getInt("unique") else null }.toList()
                }
            }.filter { !it.first.startsWith("sqlite_autoindex") }.map { (name, unique) ->
                val columns = createStatement().use { st ->
                    st.executeQuery("PRAGMA index_info(`$name`)").use { rs -> generateSequence { if (rs.next()) rs.getString("name") else null }.toList() }
                }
                "index $name unique=$unique $columns"
            }
            (cols + idx).sorted()
        }
    }

    /** A SupportSQLiteDatabase that only supports execSQL, which is all the migrations use. */
    private fun Connection.asSupport(): SupportSQLiteDatabase = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, args ->
        when (method.name) {
            "execSQL" -> { exec(args!![0] as String); Unit }
            else -> throw UnsupportedOperationException("migration used ${method.name}")
        }
    } as SupportSQLiteDatabase

    @Test
    fun migrationsReachTheExportedV4Schema() {
        val expected = db().use { c ->
            createStatements(schemaFile(4).readText()).forEach { c.exec(it) }
            c.describe()
        }
        val migrated = db().use { c ->
            v1.forEach { c.exec(it) }
            AppDatabase.MIGRATION_1_2.migrate(c.asSupport())
            AppDatabase.MIGRATION_2_3.migrate(c.asSupport())
            AppDatabase.MIGRATION_3_4.migrate(c.asSupport())
            c.describe()
        }
        assertEquals(expected.keys, migrated.keys)
        for (t in expected.keys) assertEquals("table $t", expected[t], migrated[t])
    }

    /** The v3 schema a 1.7.x install has, migrated by MIGRATION_3_4 alone, also matches v4. */
    @Test
    fun migration34FromExportedV3() {
        val expected = db().use { c -> createStatements(schemaFile(4).readText()).forEach { c.exec(it) }; c.describe() }
        val migrated = db().use { c ->
            createStatements(schemaFile(3).readText()).forEach { c.exec(it) }
            AppDatabase.MIGRATION_3_4.migrate(c.asSupport())
            c.describe()
        }
        assertEquals(expected, migrated)
    }

    @Test
    fun migrationsKeepExistingRows() {
        db().use { c ->
            v1.forEach { c.exec(it) }
            c.exec("INSERT INTO sessions (startedAt, durationMs, mode, subject, completed) VALUES (1000, 1500000, 'POMODORO', 'Physics', 1)")
            c.exec("INSERT INTO tasks (title, category, priority, deadline, done, createdAt) VALUES ('Revise', 'Study', 2, NULL, 0, 5)")
            AppDatabase.MIGRATION_1_2.migrate(c.asSupport())
            AppDatabase.MIGRATION_2_3.migrate(c.asSupport())
            c.exec("INSERT INTO exams (name, date, emoji, tag, showOnFocus, pinned, createdAt) VALUES ('JEE Main 2027', 20838, '', 0, 1, 1, 7)")
            AppDatabase.MIGRATION_3_4.migrate(c.asSupport())
            c.createStatement().use { st ->
                st.executeQuery("SELECT subject, cloudId, ownerUid, origin FROM sessions").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("Physics", rs.getString(1))
                    assertEquals(null, rs.getString(2)); assertEquals(null, rs.getString(3)); assertEquals(null, rs.getString(4))
                }
                st.executeQuery("SELECT COUNT(*) FROM tasks").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
                st.executeQuery("SELECT COUNT(*) FROM exams").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
                st.executeQuery("SELECT COUNT(*) FROM syllabus_progress").use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) }
            }
        }
    }
}
