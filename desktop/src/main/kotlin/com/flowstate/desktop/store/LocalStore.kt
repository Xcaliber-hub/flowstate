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

/** A small JSON file written atomically (temp file + move), so a crash never leaves half a file. */
class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>, private val default: () -> T) {
    private val lock = Any()
    fun load(): T = synchronized(lock) {
        runCatching { if (file.exists()) StoreJson.decodeFromString(serializer, file.readText()) else default() }.getOrElse { default() }
    }
    fun save(value: T) = synchronized(lock) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(StoreJson.encodeToString(serializer, value))
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        Unit
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
    private val file = JsonFile(AppDirs.file(fileName), ListSerializer(SessionEntity.serializer())) { emptyList() }
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

    fun markSynced(id: Long, cloudId: String, ownerUid: String, origin: String) = synchronized(this) {
        commit(_all.value.map { if (it.id == id) it.copy(cloudId = cloudId, ownerUid = ownerUid, origin = origin) else it })
    }

    fun claim(ids: Set<Long>, ownerUid: String) = synchronized(this) {
        commit(_all.value.map { if (it.id in ids && it.ownerUid == null) it.copy(ownerUid = ownerUid) else it })
    }

    fun delete(id: Long): SessionEntity? = synchronized(this) {
        val row = _all.value.firstOrNull { it.id == id } ?: return@synchronized null
        commit(_all.value - row)
        row
    }

    fun deleteByCloudId(cloudId: String) = synchronized(this) {
        if (_all.value.any { it.cloudId == cloudId }) commit(_all.value.filterNot { it.cloudId == cloudId })
    }

    /** On sign-out: forget sessions that were only pulled from the account's other devices. */
    fun deletePulled(myDeviceId: String) = synchronized(this) {
        commit(_all.value.filterNot { it.origin != null && it.origin != myDeviceId })
    }
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
)

class SyncPrefsStore {
    private val file = JsonFile(AppDirs.file("sync.json"), SyncPrefs.serializer()) { SyncPrefs() }
    @Volatile private var value = file.load()
    fun get(): SyncPrefs = value
    fun update(transform: (SyncPrefs) -> SyncPrefs) = synchronized(this) { value = transform(value); file.save(value) }
}
