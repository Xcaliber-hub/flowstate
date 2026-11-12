package com.flowstate.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.Surface
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.unit.Density
import com.flowstate.desktop.cloud.ChatMessage
import com.flowstate.desktop.cloud.ChatUi
import com.flowstate.desktop.cloud.CloudState
import com.flowstate.desktop.cloud.CloudUser
import com.flowstate.desktop.cloud.Group
import com.flowstate.desktop.cloud.Member
import com.flowstate.desktop.cloud.RemoteTimer
import com.flowstate.desktop.timer.TimerAlerts
import com.flowstate.desktop.ui.FlowstateTheme
import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Plan
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerEvent
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Headless performance probe for the real [App] composable (the same tree the Windows window shows).
 * Skia renders on the CPU here, which is close to what a Windows PC gets when Skiko falls back to software.
 *
 * For each scenario it runs the app for a few seconds of real time, polling at 60 Hz like a window would,
 * and only renders a frame when the scene asks for one through its invalidate callback. It reports frames drawn per second,
 * recomposed scopes per second and the UI-thread CPU time per second, plus the cost of one full repaint.
 *
 *   gradle :desktop:perfProbe
 */
private val noAlerts = object : TimerAlerts {
    override fun alert(event: TimerEvent) = Unit
    override fun checkIn(timeoutMs: Long) = Unit
    override fun cancelCheckIn() = Unit
    override fun autoPaused() = Unit
}

private val recomposedScopes = AtomicInteger()

@OptIn(ExperimentalComposeRuntimeApi::class)
@Composable
private fun Observed(content: @Composable () -> Unit) {
    val composition = currentComposer.composition
    remember(composition) {
        // A Proxy sidesteps the generic signature of this experimental interface.
        val observer = java.lang.reflect.Proxy.newProxyInstance(CompositionObserver::class.java.classLoader, arrayOf(CompositionObserver::class.java)) { proxy, method, margs ->
            when (method.name) {
                "onBeginComposition" -> {
                    val map = margs[1] as Map<*, *>
                    recomposedScopes.addAndGet(map.size)
                    if (System.getenv("PERF_DEBUG") != null) map.forEach { (scope, why) -> println("  invalidated ${scope} by ${(why as? Set<*>)?.map { it?.let { o -> o.javaClass.simpleName + ":" + o.toString().take(80) } }}") }
                    null
                }
                "equals" -> proxy === margs[0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "PerfObserver"
                else -> null
            }
        } as CompositionObserver
        composition.observe(observer)
    }
    content()
}

data class ProbeResult(val name: String, val framesPerSec: Double, val scopesPerSec: Double, val cpuMsPerSec: Double, val fullRepaintMs: Double)

@OptIn(InternalComposeUiApi::class)
fun main(args: Array<String>) {
    val home = File(System.getProperty("java.io.tmpdir"), "flowstate-perf-" + System.nanoTime()).apply { mkdirs() }
    System.setProperty("flowstate.home", home.path)
    val seconds = args.firstOrNull()?.toDoubleOrNull() ?: 6.0
    val model = AppModel(noAlerts)
    seedHistory(model)
    val threads = ManagementFactory.getThreadMXBean()
    val results = mutableListOf<ProbeResult>()

    fun scenario(name: String, setup: () -> Unit) {
        setup()
        // The same scene type a ComposeWindow uses, with its invalidate callback: a frame is drawn only when
        // Compose asks for one, exactly like the real window's redraw requests.
        val needsFrame = AtomicBoolean(true)
        val scene = CanvasLayersComposeScene(density = Density(1f), size = IntSize(1440, 900), invalidate = { needsFrame.set(true) })
        scene.setContent { FlowstateTheme { Observed { App(model) } } }
        val surface = Surface.makeRasterN32Premul(1440, 900)
        val canvas = surface.canvas.asComposeCanvas()
        fun frame() { needsFrame.set(false); Snapshot.sendApplyNotifications(); scene.render(canvas, System.nanoTime()) }
        try {
            val warmEnd = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < warmEnd) { Snapshot.sendApplyNotifications(); if (needsFrame.get()) frame(); Thread.sleep(16) }
            recomposedScopes.set(0)
            var frames = 0
            var cpu = 0L
            val start = System.nanoTime()
            val end = start + (seconds * 1e9).toLong()
            while (System.nanoTime() < end) {
                Snapshot.sendApplyNotifications()
                if (needsFrame.get()) {
                    val c0 = threads.currentThreadCpuTime
                    frame()
                    cpu += threads.currentThreadCpuTime - c0
                    frames++
                }
                Thread.sleep(16)
            }
            val elapsed = (System.nanoTime() - start) / 1e9
            val n = 20
            val c0 = threads.currentThreadCpuTime
            repeat(n) { scene.render(canvas, System.nanoTime()) }
            val full = (threads.currentThreadCpuTime - c0) / 1e6 / n
            results += ProbeResult(name, frames / elapsed, recomposedScopes.get() / elapsed, cpu / 1e6 / elapsed, full)
        } finally { scene.close(); surface.close() }
    }

    scenario("Focus, idle") { model.engine.stop(force = true); setRemote(model, null); model.screen.value = Screen.FOCUS }
    scenario("Focus, timer running") {
        model.engine.start(TimerMode.POMODORO, Plan(), strict = false, subject = "Physics")
        model.screen.value = Screen.FOCUS
    }
    scenario("Focus, locked (phone runs timer)") {
        model.engine.stop(force = true)
        val now = System.currentTimeMillis()
        setRemote(model, RemoteTimer("android-1", "OnePlus Nord 5", "android",
            TimerState(TimerMode.POMODORO, Phase.FOCUS, Status.RUNNING, 50 * 60_000L, now - 10 * 60_000L, subject = "Maths", plan = Plan()), now))
        model.screen.value = Screen.FOCUS
    }
    scenario("Groups (8 members, 1 live, 50 messages)") { setRemote(model, null); seedGroups(model); model.screen.value = Screen.GROUPS }
    scenario("Stats (1,500 sessions)") { model.screen.value = Screen.STATS }

    println()
    println("%-42s %10s %12s %12s %14s".format("Scenario", "frames/s", "scopes/s", "UI cpu ms/s", "full repaint ms"))
    results.forEach { println("%-42s %10.1f %12.1f %12.1f %14.1f".format(it.name, it.framesPerSec, it.scopesPerSec, it.cpuMsPerSec, it.fullRepaintMs)) }
    args.getOrNull(1)?.let { out -> File(out).writeText(results.joinToString("\n") { "${it.name}\t%.1f\t%.1f\t%.1f\t%.1f".format(it.framesPerSec, it.scopesPerSec, it.cpuMsPerSec, it.fullRepaintMs) } + "\n") }
    home.deleteRecursively()
    kotlin.system.exitProcess(0)
}

/** About a year of history: 1,500 sessions, half of them synced from a phone. */
private fun seedHistory(model: AppModel) {
    val now = System.currentTimeMillis()
    val rows = (0 until 1500).map { i ->
        SessionEntity(startedAt = now - i * 5L * 3_600_000L, durationMs = (25 + i % 30) * 60_000L, mode = "POMODORO",
            subject = listOf("Physics", "Maths", "Chemistry")[i % 3], completed = i % 4 != 0,
            cloudId = "c$i", ownerUid = "me", origin = if (i % 2 == 0) "android-1" else "win-1")
    }
    model.sessions.insertRemote(rows)
}

@Suppress("UNCHECKED_CAST")
private fun cloudState(model: AppModel): MutableStateFlow<CloudState> =
    model.cloud.javaClass.getDeclaredField("_state").apply { isAccessible = true }.get(model.cloud) as MutableStateFlow<CloudState>

private fun setRemote(model: AppModel, remote: RemoteTimer?) { val f = cloudState(model); f.value = f.value.copy(remote = remote) }

private fun seedGroups(model: AppModel) {
    val now = System.currentTimeMillis()
    val me = CloudUser("me", "Aditya", "snowflake", "a@example.com")
    val avatars = listOf("owl", "cat", "snowflake", "rocket", "coffee", "bolt", "cloud", "comet")
    val members = (0 until 8).map { i ->
        Member("u$i", "Member $i", avatars[i], (20 - i) * 3_600_000L,
            if (i == 3) TimerState(TimerMode.POMODORO, Phase.FOCUS, Status.RUNNING, 25 * 60_000L, now - 60_000L) else null,
            if (i == 3) "android" else null, now, 10, 0)
    }
    val msgs = (0 until 50).map { i -> ChatMessage("m$i", "u${i % 8}", "Member ${i % 8}", avatars[i % 8], "message number $i about the physics mock", now - i * 60_000L) }
    val groups = listOf(Group("OBSRVEVRYTHG", "observing everything", members.map { it.uid }), Group("JEE2027GRIND", "JEE 2027 grind", listOf("u0", "u1")))
    val f = cloudState(model)
    f.value = f.value.copy(configured = true, user = me, groups = groups, selectedGroupId = groups[0].id, members = members,
        chat = ChatUi(groups[0].id, msgs, loaded = true))
}
