package com.flowstate.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.flowstate.desktop.store.AppDirs
import com.flowstate.desktop.store.DeviceIdentity
import com.flowstate.desktop.timer.TimerAlerts
import com.flowstate.desktop.ui.AccountActions
import com.flowstate.desktop.ui.FlowstateTheme
import com.flowstate.desktop.ui.FocusActions
import com.flowstate.desktop.ui.FocusScreen
import com.flowstate.desktop.ui.FocusUi
import com.flowstate.desktop.ui.GroupsActions
import com.flowstate.desktop.ui.GroupsScreen
import com.flowstate.desktop.ui.SettingsScreen
import com.flowstate.desktop.ui.Shell
import com.flowstate.desktop.ui.SignInPanel
import com.flowstate.desktop.ui.StatsScreen
import com.flowstate.desktop.ui.TasksScreen
import com.flowstate.desktop.ui.LocalNow
import com.flowstate.desktop.ui.liveTodayMs
import com.flowstate.desktop.ui.nextTickDelayMs
import com.flowstate.desktop.ui.rememberTickingClock
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.StatsCalculator
import com.flowstate.focus.domain.TimerEvent
import org.jetbrains.skia.Image
import java.awt.Desktop
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.datatransfer.StringSelection
import java.time.LocalDate
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

/** Phase-end alerts: a Windows notification from the tray icon plus a short chime. */
class TrayAlerts : TimerAlerts {
    @Volatile var tray: TrayIcon? = null
    @Volatile var soundOn: () -> Boolean = { true }

    override fun alert(event: TimerEvent) {
        val (title, text) = when (event) {
            TimerEvent.FOCUS_DONE_BREAK_NEXT -> "Focus block done" to "Nice work. Your break has started."
            TimerEvent.BREAK_DONE_FOCUS_NEXT -> "Break's over" to "The next focus block has started."
            TimerEvent.SESSION_DONE -> "Session complete" to "Your focus session is finished."
            TimerEvent.BREAK_DONE -> "Break's over" to "Time to get back to it."
        }
        notify(title, text)
        if (soundOn()) chime()
    }
    override fun checkIn(timeoutMs: Long) {
        notify("Still focusing?", "Open Flowstate and confirm, or the timer pauses in ${timeoutMs / 60_000} minutes.")
        if (soundOn()) chime()
    }
    override fun cancelCheckIn() = Unit
    override fun autoPaused() = notify("Timer paused", "Nobody answered the check-in, so the timer paused.")

    private fun notify(title: String, text: String) {
        runCatching { tray?.displayMessage(title, text, TrayIcon.MessageType.INFO) }
    }

    /** Two soft sine tones, generated so no audio file is bundled. */
    private fun chime() {
        Thread {
            runCatching {
                val rate = 44_100f
                val tones = listOf(880.0 to 0.16, 1318.5 to 0.28)
                val samples = tones.sumOf { (it.second * rate).toInt() }
                val buf = ByteArray(samples * 2)
                var i = 0
                for ((freq, secs) in tones) {
                    val n = (secs * rate).toInt()
                    for (k in 0 until n) {
                        val env = minOf(1.0, k / (0.01 * rate)) * (1.0 - k / n.toDouble())
                        val v = (sin(2 * PI * freq * k / rate) * env * 0.35 * Short.MAX_VALUE).toInt()
                        buf[i++] = (v and 0xFF).toByte(); buf[i++] = ((v shr 8) and 0xFF).toByte()
                    }
                }
                val format = AudioFormat(rate, 16, 1, true, false)
                val line = AudioSystem.getSourceDataLine(format)
                line.open(format); line.start(); line.write(buf, 0, buf.size); line.drain(); line.close()
            }
        }.apply { isDaemon = true }.start()
    }
}

fun main() {
    // 1.8.1: one copy per user. A second launch shows the running window instead (see SingleInstance).
    val instance = com.flowstate.desktop.store.SingleInstance.acquire(com.flowstate.desktop.store.AppDirs.root) ?: return
    val activate = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    instance.listen { activate.tryEmit(Unit) }
    val alerts = TrayAlerts()
    val model = AppModel(alerts)
    alerts.soundOn = { model.settingsStore.settings.value.sound }
    applyRenderApiChoice(model.settingsStore.settings.value.renderApi)
    val iconBytes = Thread.currentThread().contextClassLoader.getResourceAsStream("flowstate_icon.png")?.use { it.readBytes() }

    application {
        val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp), position = WindowPosition.PlatformDefault)
        var visible by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
        val icon = androidx.compose.runtime.remember { iconBytes?.let { BitmapPainter(Image.makeFromEncoded(it).toComposeImageBitmap()) } }

        LaunchedEffect(Unit) {
            activate.collect { visible = true; windowState.isMinimized = false }
        }
        LaunchedEffect(Unit) {
            alerts.tray = installTray(iconBytes, onOpen = { visible = true; windowState.isMinimized = false }, onQuit = ::exitApplication)
        }

        Window(
            onCloseRequest = {
                if (model.settingsStore.settings.value.closeToTray && alerts.tray != null) visible = false else exitApplication()
            },
            state = windowState,
            visible = visible,
            title = "Flowstate",
            icon = icon,
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(1180, 760)
                WindowsChrome.applyDark(window)
                // Which renderer Skiko actually picked (it falls back on its own, e.g. to software when the
                // GPU driver is blocklisted). Shown in Settings, since software rendering is the usual cause of lag.
                model.renderApi.value = window.renderApi.name
                window.onRenderApiChanged { model.renderApi.value = window.renderApi.name }
                window.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) { model.cloud.windowFocused = true }
                    override fun windowLostFocus(e: java.awt.event.WindowEvent?) { model.cloud.windowFocused = false }
                })
            }
            FlowstateTheme { App(model) }
        }
    }
}

private fun installTray(iconBytes: ByteArray?, onOpen: () -> Unit, onQuit: () -> Unit): TrayIcon? = runCatching {
    if (!SystemTray.isSupported() || iconBytes == null) return null
    val image = Toolkit.getDefaultToolkit().createImage(iconBytes)
    val menu = java.awt.PopupMenu().apply {
        add(java.awt.MenuItem("Open Flowstate").apply { addActionListener { onOpen() } })
        addSeparator()
        add(java.awt.MenuItem("Quit").apply { addActionListener { onQuit() } })
    }
    TrayIcon(image, "Flowstate", menu).apply {
        isImageAutoSize = true
        addActionListener { onOpen() }
        SystemTray.getSystemTray().add(this)
    }
}.getOrNull()

@Composable
fun App(model: AppModel) {
    val screen by model.screen.collectAsState()
    val timer by model.engine.state.collectAsState()
    val settings by model.settingsStore.settings.collectAsState()
    val sessions by model.sessions.all.collectAsState()
    val tasks by model.tasks.tasks.collectAsState()
    val cloud by model.cloud.state.collectAsState()
    val selection by model.selection.collectAsState()
    val update by model.update.collectAsState()
    val renderApi by model.renderApi.collectAsState()
    // 1.8.1: the clock is not read here. Until 1.8.0 `now` was read at this level every 250 ms, which
    // recomposed and repainted the whole window four times a second, idle or not. Only the leaves that
    // show a time read it now (see LocalNow / derivedFromNow).
    // A friend's running clock on the Groups board also needs a tick every second.
    val friendLive = if (screen != Screen.GROUPS) null
        else cloud.members.firstOrNull { it.isLiveFocusing(System.currentTimeMillis()) && it.live?.status == Status.RUNNING }?.live
    val tickPlan = remember(timer, cloud.remote, friendLive) {
        { now: Long -> nextTickDelayMs(now, timer, cloud.remote?.state, friendLive) }
    }
    val clock = rememberTickingClock(tickPlan)
    val today by remember(clock) { derivedStateOf(structuralEqualityPolicy()) { StatsCalculator.dateOf(maxOf(clock.value, System.currentTimeMillis())) } }

    LaunchedEffect(screen) { model.cloud.groupsVisible = screen == Screen.GROUPS }

    val perDay = remember(sessions) { StatsCalculator.perDay(sessions) }
    val recordedTodayMs = perDay[today] ?: 0L
    val sessionsToday = remember(sessions, today) { sessions.count { StatsCalculator.dateOf(it.startedAt) == today } }
    val streak = remember(perDay, today) { StatsCalculator.streak(perDay, today) }
    val focusUi = remember(timer, selection, settings, recordedTodayMs, sessionsToday, streak, tasks, cloud.remote, cloud.user != null, today) {
        FocusUi(
            timer = timer, selection = selection, settings = settings,
            recordedTodayMs = recordedTodayMs, sessionsToday = sessionsToday,
            streak = streak, tasks = tasks, remote = cloud.remote,
            signedIn = cloud.user != null, today = today,
        )
    }
    val goalMs = settings.dailyGoalMin * 60_000L
    val goalFraction: () -> Float = remember(recordedTodayMs, timer, goalMs, clock) {
        { if (goalMs > 0) liveTodayMs(recordedTodayMs, timer, clock.value) / goalMs.toFloat() else 0f }
    }
    val account = remember(model) {
        AccountActions(
            onSignIn = model.cloud::signIn, onSignUp = model.cloud::signUp, onReset = model.cloud::resetPassword,
            onSignOut = model.cloud::signOut, onSaveProfile = model.cloud::updateProfile, onClearMessages = model.cloud::clearMessages,
        )
    }
    val focusActions = remember(model) {
        FocusActions(
            onMode = model::setMode, onDuration = model::setDuration, onSubject = model::setSubject, onStrict = model::setStrict,
            onStart = model::startFocus, onBreak = model::startBreak, onPause = model.engine::pause, onResume = model.engine::resume,
            onStop = { model.engine.stop() }, onForceStop = { model.engine.stop(force = true) }, onSkip = model.engine::skipPhase,
            onStartFocusFromBreak = model::startFocusFromBreak, onConfirmPresence = model.engine::confirmPresence,
            onTakeOver = model.cloud::takeOver,
        )
    }
    val groupsActions = remember(model) {
        GroupsActions(
            onSelect = model.cloud::selectGroup, onCreate = model.cloud::createGroup, onJoin = model.cloud::joinGroup,
            onLeave = model.cloud::leaveGroup, onSend = model.cloud::sendChat, onClearChatError = model.cloud::clearChatError,
            onCopy = { runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(it), null) } },
        )
    }
    CompositionLocalProvider(LocalNow provides clock) {
    Shell(
        screen = screen,
        onNavigate = { model.screen.value = it },
        goalFraction = goalFraction,
        // The water surface drifts only while a timer runs (one step per clock second); idle it is still,
        // so nothing on screen changes and nothing repaints.
        waterSpeed = when {
            timer.status != Status.RUNNING -> 0f
            timer.phase == Phase.FOCUS -> 1.6f
            else -> 0.6f
        },
        badges = buildSet {
            if (cloud.unreadGroups.isNotEmpty() && screen != Screen.GROUPS) add(Screen.GROUPS)
            if (update != null && screen != Screen.SETTINGS) add(Screen.SETTINGS)
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.FOCUS -> FocusScreen(focusUi, focusActions)
                Screen.STATS -> StatsScreen(sessions, settings.dailyGoalMin, DeviceIdentity.deviceId, cloud.user != null, today, onDelete = model.cloud::deleteSession)
                Screen.TASKS -> TasksScreen(tasks, model.tasks::add, { model.tasks.update(it.copy(done = !it.done)) }, model.tasks::delete, model.tasks::clearDone)
                Screen.GROUPS -> GroupsScreen(cloud, groupsActions) { SignInPanel(cloud, account) }
                Screen.SETTINGS -> SettingsScreen(
                    settings, model.settingsStore::update, cloud, account, AppDirs.root.absolutePath,
                    onOpenFolder = { runCatching { Desktop.getDesktop().open(AppDirs.root) } },
                    update = update,
                    onDownloadUpdate = { update?.let { u -> runCatching { Desktop.getDesktop().browse(java.net.URI(u.installerUrl)) } } },
                    renderApi = renderApi,
                )
            }
        }
    }
    }
}

/**
 * Applies the renderer chosen in Settings (1.8.1). Skiko reads `skiko.renderApi` when the window is created,
 * so this runs before `application {}` and takes effect on the next launch after a change. An explicit
 * -Dskiko.renderApi or SKIKO_RENDER_API environment variable still wins. Skiko falls back on its own
 * (Direct3D, then OpenGL, then software) when the chosen API can't start.
 */
internal fun applyRenderApiChoice(choice: String) {
    if (System.getProperty("skiko.renderApi") != null || !System.getenv("SKIKO_RENDER_API").isNullOrBlank()) return
    val api = RENDER_API_CHOICES[choice] ?: return
    System.setProperty("skiko.renderApi", api)
}

/** Settings value to Skiko render API name; "AUTO" leaves Skiko's default (Direct3D on Windows). */
internal val RENDER_API_CHOICES: Map<String, String?> = linkedMapOf(
    "AUTO" to null,
    "DIRECT3D" to "DIRECT3D",
    "OPENGL" to "OPENGL",
    "SOFTWARE" to "SOFTWARE_FAST",
)
