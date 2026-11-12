package com.flowstate.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.flowstate.desktop.store.DesktopSettings
import com.flowstate.desktop.store.JsonFile
import com.flowstate.desktop.store.SessionStore
import com.flowstate.desktop.ui.FlowstateTheme
import com.flowstate.desktop.ui.FocusActions
import com.flowstate.desktop.ui.FocusScreen
import com.flowstate.desktop.ui.FocusUi
import com.flowstate.desktop.ui.LocalNow
import com.flowstate.desktop.ui.Shell
import com.flowstate.desktop.ui.nextTickDelayMs
import com.flowstate.desktop.ui.untilNextSecond
import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Plan
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 1.8.1: the Windows app must not recompose or repaint on clock ticks that change nothing, nor write files on the UI thread. */
class PerformanceTest {

    @Test fun clockTicksDoNotRecomposeTheWindow() {
        System.setProperty("flowstate.home", Files.createTempDirectory("fs-perf").toString())
        val clock = mutableLongStateOf(System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val running = TimerState(TimerMode.POMODORO, Phase.FOCUS, Status.RUNNING, 25 * 60_000L, now - 60_000L, plan = Plan())
        for (timer in listOf(TimerState(), running)) {
            var shellContent = 0
            val ui = FocusUi(timer, FocusSelection(), DesktopSettings(), recordedTodayMs = 3_600_000L, sessionsToday = 2, streak = 3,
                tasks = emptyList(), remote = null, signedIn = false)
            val scene = ImageComposeScene(1440, 900, Density(1f)) {
                CompositionLocalProvider(LocalNow provides clock) {
                    FlowstateTheme {
                        Shell(Screen.FOCUS, {}, { 0.5f }, 0f) {
                            SideEffect { shellContent++ }
                            FocusScreen(ui, FocusActions())
                        }
                    }
                }
            }
            try {
                scene.render(0)
                val composed = shellContent
                repeat(8) { i ->
                    clock.longValue += 250
                    Snapshot.sendApplyNotifications()
                    scene.render((i + 1) * 250_000_000L)
                }
                // Until 1.8.0 every tick recomposed everything under App; now only the clock text may.
                assertEquals(composed, shellContent, "the screen recomposed on clock ticks (timer ${timer.status})")
            } finally { scene.close() }
        }
    }

    @Test fun idleTicksOncePerMinuteAndRunningTimerOncePerSecond() {
        val now = 1_700_000_012_345L
        val idle = nextTickDelayMs(now, TimerState(), null, null)
        assertEquals(60_000L - now % 60_000L + 5L, idle)
        assertTrue(idle in 5L..60_005L)

        val running = TimerState(TimerMode.POMODORO, Phase.FOCUS, Status.RUNNING, 25 * 60_000L, now - 10_400L, plan = Plan())
        val d = nextTickDelayMs(now, running, null, null)
        assertTrue(d in 5L..1005L, "running delay $d")
        // After the delay the remaining time has crossed exactly one whole-second boundary.
        val before = (running.remainingAt(now) + 999) / 1000
        val after = (running.remainingAt(now + d) + 999) / 1000
        assertEquals(before - 1, after)

        val paused = running.copy(status = Status.PAUSED, pausedAt = now)
        assertTrue(nextTickDelayMs(now, paused, null, null) > 1005L, "a paused timer needs no per-second tick")

        val stopwatch = TimerState(TimerMode.STOPWATCH, Phase.FOCUS, Status.RUNNING, 0L, now - 2_250L)
        assertEquals(755L, untilNextSecond(stopwatch, now))
        // A phone's running session (remote) also ticks every second here.
        assertTrue(nextTickDelayMs(now, TimerState(), running, null) <= 1005L)
    }

    @Test fun jsonFileCoalescesSavesAndKeepsTheLatest() {
        val dir = Files.createTempDirectory("fs-json").toFile()
        val f = File(dir, "x.json")
        val store = JsonFile(f, ListSerializer(Int.serializer())) { emptyList() }
        repeat(200) { store.save(List(it + 1) { i -> i }) }
        store.flush()
        assertEquals(200, store.load().size)
        assertEquals(200, JsonFile(f, ListSerializer(Int.serializer())) { emptyList() }.load().size)
        assertFalse(File(dir, "x.json.tmp").exists())
    }

    @Test fun uploadedSessionsAreMarkedInOneBatch() {
        System.setProperty("flowstate.home", Files.createTempDirectory("fs-batch").toString())
        val store = SessionStore("sessions-batch.json")
        repeat(300) { store.insert(SessionEntity(startedAt = it * 1000L, durationMs = 60_000L, mode = "POMODORO", subject = "", completed = true)) }
        val before = store.all.value
        store.markSynced(before.associate { it.id to "win_${it.startedAt}" }, "u", "win")
        assertTrue(store.all.value.all { it.cloudId != null && it.ownerUid == "u" })
        assertEquals(300, store.all.value.size)
        store.deleteByCloudIds(setOf("win_0", "win_1000"))
        assertEquals(298, store.all.value.size)
    }
}
