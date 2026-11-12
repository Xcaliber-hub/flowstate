package com.flowstate.desktop.store

import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.data.db.TaskEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Where Flowstate keeps its files: %APPDATA%\Flowstate on Windows, ~/.flowstate elsewhere. */
object AppDirs {
    val root: File by lazy {
        val override = System.getProperty("flowstate.home")
        val dir = when {
            !override.isNullOrBlank() -> File(override)
            !System.getenv("APPDATA").isNullOrBlank() -> File(System.getenv("APPDATA"), "Flowstate")
            else -> File(System.getProperty("user.home"), ".flowstate")
        }
        dir.mkdirs()
        dir
    }
    fun file(name: String) = File(root, name)
}

val StoreJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false; explicitNulls = false }

/**
 * A small JSON file written atomically (temp file + move), so a crash never leaves half a file.
 *
 * 1.8.1: [save] no longer encodes and writes on the caller's thread. Saves were made from the UI thread
 * (every Settings stepper click, task tick, pause/resume, session delete), and SessionStore re-encoded the
 * whole history each time. Now the newest value is handed to one background writer thread; several saves
 * in quick succession collapse into one write of the latest value. [flush] waits for pending writes, and
 * a shutdown hook flushes on exit.
 */
class JsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    /**
     * 1.8.1: rebuilds what it can from a file that no longer decodes as a whole (for a list: every element that
     * still decodes). Null when nothing can be saved.
     */
    private val salvage: ((String) -> T?)? = null,
    private val default: () -> T,
) {
    private val backup = File(file.parentFile, file.name + ".bak")
    /** The file on disk decoded when loaded, or was written by this process; only such a file becomes the backup. */
    @Volatile private var fileGood = false
    private val lock = Any()
    private val pendingLock = Any()
    private var pending: Any? = NONE
    private var scheduled = false

    /**
     * Reads the file. 1.8.1: a file that does not decode is never treated as empty. Until 1.8.0 any decode
     * failure (a file cut short by a power cut, a disk error) loaded as the default, an empty history, and the
     * next save wrote that over the file, so every session was gone. Now the unreadable file is kept as
     * `<name>.corrupt-<time>`, and the last good copy (`<name>.bak`, written before every replace) is used,
     * or else whatever [salvage] can rebuild from the damaged file.
     */
    fun load(): T = synchronized(lock) {
        flushPending()
        if (!file.exists()) return@synchronized decodeFile(backup) ?: default()
        val text = runCatching { file.readText() }.getOrNull()
        text?.let { t -> runCatching { StoreJson.decodeFromString(serializer, t) }.getOrNull() }?.let { fileGood = true; return@synchronized it }
        fileGood = false
        runCatching { Files.copy(file.toPath(), File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}").toPath(), StandardCopyOption.REPLACE_EXISTING) }
        decodeFile(backup) ?: text?.let { t -> salvage?.let { f -> runCatching { f(t) }.getOrNull() } } ?: default()
    }

    private fun decodeFile(f: File): T? = runCatching { if (f.exists()) StoreJson.decodeFromString(serializer, f.readText()) else null }.getOrNull()

    fun save(value: T) {
        synchronized(pendingLock) {
            pending = value
            if (scheduled) return
            scheduled = true
        }
        Writer.submit(this::flushPending)
    }

    /** Blocks until everything saved so far is on disk. */
    fun flush() = flushPending()

    @Suppress("UNCHECKED_CAST")
    private fun flushPending() = synchronized(lock) {
        val value = synchronized(pendingLock) {
            val v = pending
            pending = NONE
            scheduled = false
            v
        }
        if (value !== NONE) write(value as T)
    }

    /**
     * Temp file, flushed to the disk (fsync), then moved over the file. 1.8.1: the fsync (without it a power cut
     * can leave a renamed but empty file on NTFS), a `.bak` copy of the previous good file, and a few retries
     * when the move fails (antivirus or a sync client briefly holding the file).
     */
    private fun write(value: T) {
        val text = runCatching { StoreJson.encodeToString(serializer, value) }.getOrNull() ?: return
        repeat(3) { attempt ->
            val ok = runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                java.io.FileOutputStream(tmp).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
                // Keep the previous version only when it is a good one.
                if (fileGood && file.exists()) runCatching { Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                fileGood = true
            }.isSuccess
            if (ok) return
            if (attempt < 2) Thread.sleep(200L)
        }
    }

    private companion object {
        val NONE = Any()
    }

    /** One daemon thread for all store files; flushed again on JVM exit so the last save is kept. */
    private object Writer {
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "flowstate-store-writer").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
        private val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()

        init {
            Runtime.getRuntime().addShutdownHook(Thread({
                executor.shutdown()
                runCatching { executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS) }
                while (true) { (queued.poll() ?: break).run() }
            }, "flowstate-store-flush"))
        }

        fun submit(task: Runnable) {
            queued.add(task)
            runCatching { executor.execute { queued.remove(task); task.run() } }.onFailure { queued.remove(task); task.run() }
        }
    }
}

@Serializable
data class DeviceInfo(val deviceId: String = "")

/** A stable id for this installation, used for the one-device timer lock and synced session ids. */
object DeviceIdentity {
    private val store = JsonFile(AppDirs.file("device.json"), DeviceInfo.serializer()) { DeviceInfo() }
    val deviceId: String by lazy {
        val saved = store.load().deviceId
        if (saved.isNotBlank()) saved else ("win-" + UUID.randomUUID().toString()).also { store.save(DeviceInfo(it)) }
    }
    val deviceName: String by lazy {
        (System.getenv("COMPUTERNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "PC").take(60)
    }
}

@Serializable
data class DesktopSettings(
    val focusMin: Int = 25,
    val shortBreakMin: Int = 5,
    val longBreakMin: Int = 15,
    val rounds: Int = 4,
    val countdownMin: Int = 45,
    val dailyGoalMin: Int = 120,
    val strictByDefault: Boolean = false,
    val inactivityCheck: Boolean = true,
    val checkInMinutes: Int = 60,
    val sound: Boolean = true,
    /** Closing the window keeps Flowstate running in the system tray, so the timer and alerts continue. */
    val closeToTray: Boolean = true,
    /** Window renderer: AUTO (Skiko's default, Direct3D on Windows), DIRECT3D, OPENGL or SOFTWARE. Applied at next launch. */
    val renderApi: String = "AUTO",
)

class SettingsStore {
    private val file = JsonFile(AppDirs.file("settings.json"), DesktopSettings.serializer()) { DesktopSettings() }
    private val _settings = MutableStateFlow(file.load())
    val settings: StateFlow<DesktopSettings> = _settings.asStateFlow()
    fun update(transform: (DesktopSettings) -> DesktopSettings) {
        synchronized(this) {
            val next = transform(_settings.value)
            _settings.value = next
            file.save(next)
        }
    }
}

/** Local focus history, newest first. Holds this PC's own sessions and those pulled from other devices. */
class SessionStore(fileName: String = "sessions.json") {
    private val file = JsonFile(AppDirs.file(fileName), ListSerializer(SessionEntity.serializer()), salvage = ::salvageSessions) { emptyList() }
    private val _all = MutableStateFlow(file.load().sortedByDescending { it.startedAt })
    val all: StateFlow<List<SessionEntity>> = _all.asStateFlow()

    private fun commit(list: List<SessionEntity>) {
        val sorted = list.sortedByDescending { it.startedAt }
        _all.value = sorted
        file.save(sorted)
    }

    fun insert(session: SessionEntity): SessionEntity = synchronized(this) {
        val id = (_all.value.maxOfOrNull { it.id } ?: 0L) + 1
        val row = session.copy(id = id)
        commit(_all.value + row)
        row
    }

    /** Adds sessions from the cloud; one already known by [SessionEntity.cloudId] is skipped. */
    fun insertRemote(rows: List<SessionEntity>): Int = synchronized(this) {
        val known = _all.value.mapNotNull { it.cloudId }.toHashSet()
        var next = (_all.value.maxOfOrNull { it.id } ?: 0L) + 1
        val fresh = rows.filter { it.cloudId != null && known.add(it.cloudId) }.map { it.copy(id = next++) }
        if (fresh.isNotEmpty()) commit(_all.value + fresh)
        fresh.size
    }

    fun markSynced(id: Long, cloudId: String, ownerUid: String, origin: String) = markSynced(mapOf(id to cloudId), ownerUid, origin)

    /**
     * Marks many uploaded sessions at once (id to cloud id): one list update and one file write for the whole
     * batch. Until 1.8.1 each uploaded session rewrote sessions.json and pushed a new history to the UI,
     * so the first backfill of a few hundred sessions meant hundreds of full writes and recompositions.
     */
    fun markSynced(cloudIds: Map<Long, String>, ownerUid: String, origin: String) = synchronized(this) {
        if (cloudIds.isEmpty()) return@synchronized
        commit(_all.value.map { row -> cloudIds[row.id]?.let { row.copy(cloudId = it, ownerUid = ownerUid, origin = origin) } ?: row })
    }

    fun claim(ids: Set<Long>, ownerUid: String) = synchronized(this) {
        commit(_all.value.map { if (it.id in ids && it.ownerUid == null) it.copy(ownerUid = ownerUid) else it })
    }

    fun delete(id: Long): SessionEntity? = synchronized(this) {
        val row = _all.value.firstOrNull { it.id == id } ?: return@synchronized null
        commit(_all.value - row)
        row
    }

    fun deleteByCloudId(cloudId: String) = deleteByCloudIds(setOf(cloudId))

    /** Drops every session whose cloud id is in [cloudIds], with one write. */
    fun deleteByCloudIds(cloudIds: Set<String>) = synchronized(this) {
        if (cloudIds.isNotEmpty() && _all.value.any { it.cloudId in cloudIds }) commit(_all.value.filterNot { it.cloudId in cloudIds })
    }

    /**
     * On sign-out: forget sessions that were only pulled from the account's other devices. 1.8.1: only rows that
     * came from the cloud (cloud id set), so signing in again brings them back; this PC's own rows stay.
     */
    fun deletePulled(myDeviceId: String) = synchronized(this) {
        if (_all.value.any { isPulled(it, myDeviceId) }) commit(_all.value.filterNot { isPulled(it, myDeviceId) })
    }

    /** On signing in to another account: drop rows pulled for a different account (they stay in its cloud). */
    fun deletePulledOfOtherAccounts(myDeviceId: String, uid: String) = synchronized(this) {
        val other = { r: SessionEntity -> isPulled(r, myDeviceId) && r.ownerUid != null && r.ownerUid != uid }
        if (_all.value.any(other)) commit(_all.value.filterNot(other))
    }

    private fun isPulled(r: SessionEntity, myDeviceId: String) = r.origin != null && r.origin != myDeviceId && r.cloudId != null

    /** Blocks until the history is on disk (tests, and before the app exits). */
    fun flush() = file.flush()
}

/** Every session in a damaged sessions.json that still decodes on its own (1.8.1). */
internal fun salvageSessions(text: String): List<SessionEntity>? {
    val array = runCatching { StoreJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray }.getOrNull()
    if (array != null) return array.mapNotNull { runCatching { StoreJson.decodeFromJsonElement(SessionEntity.serializer(), it) }.getOrNull() }.takeIf { it.isNotEmpty() }
    // Cut short: decode every complete {...} object before the cut.
    val rows = Regex("""\{[^{}]*\}""").findAll(text).mapNotNull { m -> runCatching { StoreJson.decodeFromString(SessionEntity.serializer(), m.value) }.getOrNull() }.toList()
    return rows.takeIf { it.isNotEmpty() }
}

class TaskStore {
    private val file = JsonFile(AppDirs.file("tasks.json"), ListSerializer(TaskEntity.serializer())) { emptyList() }
    private val _tasks = MutableStateFlow(sorted(file.load()))
    val tasks: StateFlow<List<TaskEntity>> = _tasks.asStateFlow()

    // Same order as the Android TaskDao: open first, high priority first, nearest deadline, newest.
    private fun sorted(list: List<TaskEntity>) = list.sortedWith(
        compareBy<TaskEntity> { it.done }.thenByDescending { it.priority }
            .thenBy { if (it.deadline == null) 1 else 0 }.thenBy { it.deadline ?: 0L }.thenByDescending { it.createdAt }
    )
    private fun commit(list: List<TaskEntity>) { val s = sorted(list); _tasks.value = s; file.save(s) }

    fun add(task: TaskEntity) = synchronized(this) { commit(_tasks.value + task.copy(id = (_tasks.value.maxOfOrNull { it.id } ?: 0L) + 1)) }
    fun update(task: TaskEntity) = synchronized(this) { commit(_tasks.value.map { if (it.id == task.id) task else it }) }
    fun delete(task: TaskEntity) = synchronized(this) { commit(_tasks.value.filterNot { it.id == task.id }) }
    fun clearDone() = synchronized(this) { commit(_tasks.value.filterNot { it.done }) }
}

@Serializable
data class SyncPrefs(
    /** Per account: newest cloud session updatedAt (ms) already pulled. */
    val lastPull: Map<String, Long> = emptyMap(),
    /** Accounts whose local history was uploaded once. */
    val backfilled: Set<String> = emptySet(),
    /** Per group: newest chat message time seen in this app. */
    val chatSeen: Map<String, Long> = emptyMap(),
    /** When this PC last wrote the timer lock (epoch ms). */
    val lockWrittenAt: Long = 0L,
    /**
     * 1.8.1, per account: where the session pull stopped, "<updatedAt ISO-8601>|<document name>". Missing means
     * pull the whole history (once after updating to 1.8.1, which brings back sessions earlier pulls skipped).
     */
    val pullCursor: Map<String, String> = emptyMap(),
    /** 1.8.1, per account: cloud ids deleted on this PC whose "deleted" mark has not reached the cloud yet. */
    val pendingTombstones: Map<String, Set<String>> = emptyMap(),
)

class SyncPrefsStore {
    private val file = JsonFile(AppDirs.file("sync.json"), SyncPrefs.serializer()) { SyncPrefs() }
    @Volatile private var value = file.load()
    fun get(): SyncPrefs = value
    fun update(transform: (SyncPrefs) -> SyncPrefs) = synchronized(this) { value = transform(value); file.save(value) }
}
