package com.flowstate.focus.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1.9.1: crash reports without Crashlytics.
 *
 * - A Kotlin/Java crash is written to files/last_crash.txt (stack trace, phone, Android version, app version,
 *   the last few things the app did) before the system closes the app.
 * - A native crash (graphics driver, RenderThread) cannot be caught in-process; on the next launch Android 11+
 *   tells us about it through ApplicationExitInfo, and that becomes the report instead.
 * - On the next launch [pending] returns the report once, and CrashPrompt offers to share it.
 *
 * Safe chat glass: if the app died natively while the group chat was open, the chat bars switch to frosted
 * glass on that phone ([safeChatGlass]). Liquid glass is a shader in the graphics driver, the likeliest thing
 * to kill the process there.
 */
object CrashReporter {
    private const val PREFS = "crash_reporter"
    private const val FILE = "last_crash.txt"
    private const val MAX_CRUMBS = 12
    private val crumbs = ArrayDeque<String>()
    @Volatile private var appContext: Context? = null

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter(); error.printStackTrace(PrintWriter(sw))
                File(app.filesDir, FILE).writeText(header(app, "Crash on thread ${thread.name}") + "\n" + sw.toString().take(24_000))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Remembers a step for the next report ("Opened group chat"). Also stored, so a native crash report has it. */
    fun note(event: String) {
        val line = "${stamp()} $event"
        val joined = synchronized(crumbs) {
            crumbs.addLast(line); while (crumbs.size > MAX_CRUMBS) crumbs.removeAt(0)
            crumbs.joinToString("\n")
        }
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("crumbs", joined)?.apply()
    }

    /** True while the group chat screen is open; cleared when it closes. Read after a native crash. */
    fun setInChat(inChat: Boolean) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("in_chat", inChat)?.commit()
    }

    fun safeChatGlass(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("safe_chat_glass", false)

    /** The report from the last crash, once. Null when the app last closed normally. */
    fun pending(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = File(context.filesDir, FILE)
        if (file.exists()) {
            val text = runCatching { file.readText() }.getOrNull()
            file.delete()
            prefs.edit().putBoolean("in_chat", false).apply()
            if (!text.isNullOrBlank()) return text
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
            val seen = prefs.getLong("seen_exit", 0L)
            if (last.timestamp <= seen) return null
            val wasInChat = prefs.getBoolean("in_chat", false)
            prefs.edit().putLong("seen_exit", last.timestamp).putBoolean("in_chat", false).apply()
            when (last.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> {
                    if (wasInChat) prefs.edit().putBoolean("safe_chat_glass", true).apply()
                    buildString {
                        append(header(context, "Native crash (graphics or system library)"))
                        append("\nExit description: ").append(last.description ?: "none")
                        append("\nIn group chat: ").append(wasInChat)
                        if (wasInChat) append("\nChat glass switched to frosted on this phone.")
                    }
                }
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_ANR -> buildString {
                    append(header(context, if (last.reason == ApplicationExitInfo.REASON_ANR) "App not responding" else "Crash"))
                    append("\nExit description: ").append(last.description ?: "none")
                    append("\nIn group chat: ").append(wasInChat)
                }
                else -> null
            }
        }.onFailure { Log.w("Flowstate", "Exit reasons unavailable", it) }.getOrNull()
    }

    private fun header(context: Context, title: String): String {
        val version = runCatching {
            val p = context.packageManager.getPackageInfo(context.packageName, 0)
            "${p.versionName} (${if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()})"
        }.getOrDefault("unknown")
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("crumbs", null)
        val steps = synchronized(crumbs) { crumbs.joinToString("\n") }.ifBlank { saved.orEmpty() }
        return buildString {
            append("Flowstate crash report\n").append(title)
            append("\nTime: ").append(stamp())
            append("\nApp: ").append(version)
            append("\nPhone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append("\nAndroid: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(')')
            if (steps.isNotBlank()) append("\nLast steps:\n").append(steps)
        }
    }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
