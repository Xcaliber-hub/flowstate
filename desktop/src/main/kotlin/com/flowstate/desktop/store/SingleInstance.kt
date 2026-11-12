package com.flowstate.desktop.store

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.TimeUnit

/**
 * 1.8.1: only one Flowstate runs per Windows user.
 *
 * With "close to tray" on, the window hides while Flowstate keeps running, so opening it again from the Start
 * menu or the desktop shortcut used to start a second copy. Each copy kept its own history in memory and
 * rewrote the whole sessions.json from it, so a session recorded (or pulled) by one copy was erased by the
 * next write of the other: one of the ways sessions "disappeared". The second launch now asks the running
 * copy to show its window and exits.
 */
class SingleInstance private constructor(private val channel: FileChannel?, private val lock: FileLock?, private val dir: File) {

    /** Calls [onActivate] (from a background thread) whenever another launch asks this copy to show itself. */
    fun listen(onActivate: () -> Unit) {
        Thread({
            runCatching {
                FileSystems.getDefault().newWatchService().use { watch ->
                    dir.toPath().register(watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)
                    while (true) {
                        val key = watch.poll(5, TimeUnit.SECONDS) ?: continue
                        val asked = key.pollEvents().any { it.context()?.toString() == SIGNAL }
                        key.reset()
                        if (asked) onActivate()
                    }
                }
            }
        }, "flowstate-single-instance").apply { isDaemon = true }.start()
    }

    fun release() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
    }

    companion object {
        private const val LOCK = "instance.lock"
        private const val SIGNAL = "activate.signal"

        /** The lock for [dir], or null when another copy holds it (that copy has then been asked to show itself). */
        fun acquire(dir: File): SingleInstance? {
            dir.mkdirs()
            // A lock file that cannot be opened at all (permissions) must not keep Flowstate from starting.
            val channel = runCatching { RandomAccessFile(File(dir, LOCK), "rw").channel }.getOrNull()
                ?: return SingleInstance(null, null, dir)
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null } catch (_: Exception) { null }
            if (lock == null) {
                runCatching { channel.close() }
                signal(dir)
                return null
            }
            return SingleInstance(channel, lock, dir)
        }

        private fun signal(dir: File) {
            runCatching { File(dir, SIGNAL).writeText(System.currentTimeMillis().toString()) }
        }
    }
}
