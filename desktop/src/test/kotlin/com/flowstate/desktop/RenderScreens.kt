package com.flowstate.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.flowstate.desktop.cloud.ChatMessage
import com.flowstate.desktop.cloud.ChatUi
import com.flowstate.desktop.cloud.CloudState
import com.flowstate.desktop.cloud.CloudUser
import com.flowstate.desktop.cloud.Group
import com.flowstate.desktop.cloud.Member
import com.flowstate.desktop.cloud.RemoteTimer
import com.flowstate.desktop.store.DesktopSettings
import com.flowstate.desktop.ui.FlowstateTheme
import com.flowstate.desktop.ui.FocusActions
import com.flowstate.desktop.ui.FocusScreen
import com.flowstate.desktop.ui.FocusUi
import com.flowstate.desktop.ui.GroupsActions
import com.flowstate.desktop.ui.GroupsScreen
import com.flowstate.desktop.ui.LocalNow
import com.flowstate.desktop.ui.Shell
import com.flowstate.desktop.ui.StatsScreen
import com.flowstate.desktop.ui.liveTodayMs
import com.flowstate.focus.data.db.SessionEntity
import com.flowstate.focus.data.db.TaskEntity
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Plan
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Renders the main screens with sample data to PNGs, headless. Run with :desktop:renderScreens. */
fun main(args: Array<String>) {
    System.setProperty("flowstate.home", File(System.getProperty("java.io.tmpdir"), "flowstate-render").path)
    val out = File(args.firstOrNull() ?: "build/screens").apply { mkdirs() }
    val now = renderNow
    val today = LocalDate.now()
    val settings = DesktopSettings(dailyGoalMin = 240)
    val tasks = listOf(
        TaskEntity(1, "Rotational motion DPP 4", "Physics", 2, today.atTime(23, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), createdAt = now),
        TaskEntity(2, "Definite integrals PYQs", "Maths", 2, null, createdAt = now),
        TaskEntity(3, "Ionic equilibrium notes", "Chemistry", 1, today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), createdAt = now),
        TaskEntity(4, "Revise formula sheet", "Physics", 0, null, createdAt = now),
    )
    val running = TimerState(
        mode = TimerMode.POMODORO, phase = Phase.FOCUS, status = Status.RUNNING, phaseDurationMs = 25 * 60_000L,
        phaseStartedAt = now - (25 * 60_000L - (18 * 60_000L + 42_000L)), subject = "Physics – Rotational motion", round = 2, plan = Plan(),
    )
    val focusUi = FocusUi(running, FocusSelection(subject = "Physics – Rotational motion"), settings, recordedTodayMs = 2 * 3_600_000L - 6 * 60_000L,
        sessionsToday = 5, streak = 12, tasks = tasks, remote = null, signedIn = true, today = today)
    val phoneRemote = RemoteTimer("android-1", "OnePlus Nord 5", "android",
        running.copy(phaseDurationMs = 50 * 60_000L, phaseStartedAt = now - (50 * 60_000L - (32 * 60_000L + 5_000L))), now)

    render(out, "flowstate-1.6.0-desktop-focus.png") { frame(focusUi) { FocusScreen(focusUi, FocusActions()) } }
    val locked = focusUi.copy(timer = TimerState(), remote = phoneRemote)
    render(out, "flowstate-1.6.0-desktop-locked.png") { frame(locked) { FocusScreen(locked, FocusActions()) } }

    val me = CloudUser("me", "Aditya", "snowflake", "aditya@example.com")
    val members = listOf(
        Member("r", "Rohan", "owl", 21 * 3_600_000L + 40 * 60_000L, null, null, now, 30, 3 * 3_600_000L),
        Member("p", "Priya", "cat", 17 * 3_600_000L + 5 * 60_000L, null, null, now, 25, 0),
        Member("me", "Aditya", "snowflake", 12 * 3_600_000L + 30 * 60_000L, null, null, now, 20, 0),
        Member("k", "Kabir", "rocket", 9 * 3_600_000L + 12 * 60_000L, running.copy(phaseStartedAt = now - 60_000L), "android", now, 14, 0),
        Member("a", "Ananya", "coffee", 6 * 3_600_000L + 48 * 60_000L, null, null, now, 10, 0),
    )
    val t0 = now - 25 * 60_000L
    val msgs = listOf(
        ChatMessage("m4", "me", "Aditya", "snowflake", "same, 2 more pomodoros first. loser buys chai", t0 + 22 * 60_000L),
        ChatMessage("m3", "k", "Kabir", "rocket", "on my phone rn, 24 min left", t0 + 9 * 60_000L),
        ChatMessage("m2", "p", "Priya", "cat", "in, after one more rotational motion block", t0 + 3 * 60_000L),
        ChatMessage("m1", "r", "Rohan", "owl", "physics mock at 9? full syllabus", t0),
    )
    val groups = listOf(
        Group("OBSRVEVRYTHG", "observing everything", listOf("r", "p", "me", "k", "a")),
        Group("JEE2027GRIND", "JEE 2027 grind", listOf("me", "r")),
        Group("PHYSICSPYQCL", "Physics PYQ club", List(12) { "u$it" }),
        Group("NIGHTOWLS234", "Night owls", listOf("me", "p")),
    )
    val cloud = CloudState(
        configured = true, user = me, groups = groups, selectedGroupId = groups[0].id, members = members,
        lastMessages = mapOf("JEE2027GRIND" to ChatMessage("x", "r", "Rohan", "owl", "mock at 9?", now), "NIGHTOWLS234" to ChatMessage("y", "p", "Priya", "cat", "done for today", now)),
        unreadGroups = setOf("JEE2027GRIND", "NIGHTOWLS234"),
        chat = ChatUi(groups[0].id, msgs, loaded = true, seenBy = mapOf("r" to now, "p" to now, "k" to now)),
    )
    render(out, "flowstate-1.6.0-desktop-groups.png") { frame(focusUi, Screen.GROUPS) { GroupsScreen(cloud, GroupsActions()) {} } }

    val sessions = (0 until 40).map { i ->
        val day = today.minusDays((i / 3).toLong())
        SessionEntity(i.toLong() + 1, day.atTime(9 + (i % 3) * 3, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), (25 + (i * 7) % 40) * 60_000L, "POMODORO",
            listOf("Physics", "Maths", "Chemistry")[i % 3], i % 4 != 0, origin = if (i % 2 == 0) "android-1" else "win-1")
    }
    render(out, "flowstate-1.6.0-desktop-stats.png") { frame(focusUi, Screen.STATS) { StatsScreen(sessions, 240, "win-1", true, today) } }
    println("Rendered to ${out.absolutePath}")
    kotlin.system.exitProcess(0)
}

private val renderNow = System.currentTimeMillis()

private fun frame(ui: FocusUi, screen: Screen = Screen.FOCUS, content: @androidx.compose.runtime.Composable () -> Unit): @androidx.compose.runtime.Composable () -> Unit = {
    // A fixed clock, so every render shows the same moment.
    androidx.compose.runtime.CompositionLocalProvider(LocalNow provides androidx.compose.runtime.mutableLongStateOf(renderNow)) {
        FlowstateTheme {
            Shell(screen, {}, { ui.liveTodayMs(renderNow) / (ui.settings.dailyGoalMin * 60_000f) }, 0.6f, if (screen != Screen.GROUPS) setOf(Screen.GROUPS) else emptySet()) { content() }
        }
    }
}

private fun render(dir: File, name: String, content: () -> @androidx.compose.runtime.Composable () -> Unit) {
    val scene = ImageComposeScene(1544, 910, Density(1f), content = content())
    try {
        scene.render(0)
        scene.render(500_000_000L)
        val img = scene.render(1_000_000_000L)
        File(dir, name).writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    } finally { scene.close() }
}
