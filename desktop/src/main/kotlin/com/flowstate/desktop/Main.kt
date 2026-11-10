package com.flowstate.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
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
import com.flowstate.desktop.ui.liveTodayMs
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.StatsCalculator
import com.flowstate.focus.domain.TimerEvent
import kotlinx.coroutines.delay
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
    val alerts = TrayAlerts()
    val model = AppModel(alerts)
    alerts.soundOn = { model.settingsStore.settings.value.sound }
    val iconBytes = Thread.currentThread().contextClassLoader.getResourceAsStream("flowstate_icon.png")?.use { it.readBytes() }

    application {
        val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp), position = WindowPosition.PlatformDefault)
        var visible by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
        val icon = androidx.compose.runtime.remember { iconBytes?.let { BitmapPainter(Image.makeFromEncoded(it).toComposeImageBitmap()) } }

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
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(250); value = System.currentTimeMillis() } }

    LaunchedEffect(screen) { model.cloud.groupsVisible = screen == Screen.GROUPS }

    val today = LocalDate.now()
    val perDay = StatsCalculator.perDay(sessions)
    val todaySessions = sessions.filter { StatsCalculator.dateOf(it.startedAt) == today }
    val focusUi = FocusUi(
        timer = timer, selection = selection, settings = settings,
        recordedTodayMs = perDay[today] ?: 0L, sessionsToday = todaySessions.size,
        streak = StatsCalculator.streak(perDay, today), tasks = tasks, remote = cloud.remote,
        signedIn = cloud.user != null, now = now, today = today,
    )
    val goalMs = settings.dailyGoalMin * 60_000L
    val account = AccountActions(
        onSignIn = model.cloud::signIn, onSignUp = model.cloud::signUp, onReset = model.cloud::resetPassword,
        onSignOut = model.cloud::signOut, onSaveProfile = model.cloud::updateProfile, onClearMessages = model.cloud::clearMessages,
    )
    Shell(
        screen = screen,
        onNavigate = { model.screen.value = it },
        goalFraction = if (goalMs > 0) focusUi.liveTodayMs() / goalMs.toFloat() else 0f,
        waterTime = (now % 100_000L) / 1000f * if (timer.phase == Phase.FOCUS && timer.isActive) 1.6f else 0.6f,
        badges = buildSet {
            if (cloud.unreadGroups.isNotEmpty() && screen != Screen.GROUPS) add(Screen.GROUPS)
            if (update != null && screen != Screen.SETTINGS) add(Screen.SETTINGS)
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.FOCUS -> FocusScreen(
                    focusUi,
                    FocusActions(
                        onMode = model::setMode, onDuration = model::setDuration, onSubject = model::setSubject, onStrict = model::setStrict,
                        onStart = model::startFocus, onBreak = model::startBreak, onPause = model.engine::pause, onResume = model.engine::resume,
                        onStop = { model.engine.stop() }, onForceStop = { model.engine.stop(force = true) }, onSkip = model.engine::skipPhase,
                        onStartFocusFromBreak = model::startFocusFromBreak, onConfirmPresence = model.engine::confirmPresence,
                        onTakeOver = model.cloud::takeOver,
                    ),
                )
                Screen.STATS -> StatsScreen(sessions, settings.dailyGoalMin, DeviceIdentity.deviceId, cloud.user != null, today, onDelete = model.cloud::deleteSession)
                Screen.TASKS -> TasksScreen(tasks, model.tasks::add, { model.tasks.update(it.copy(done = !it.done)) }, model.tasks::delete, model.tasks::clearDone)
                Screen.GROUPS -> GroupsScreen(
                    cloud, now,
                    GroupsActions(
                        onSelect = model.cloud::selectGroup, onCreate = model.cloud::createGroup, onJoin = model.cloud::joinGroup,
                        onLeave = model.cloud::leaveGroup, onSend = model.cloud::sendChat, onClearChatError = model.cloud::clearChatError,
                        onCopy = { runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(it), null) } },
                    ),
                ) { SignInPanel(cloud, account) }
                Screen.SETTINGS -> SettingsScreen(
                    settings, model.settingsStore::update, cloud, account, AppDirs.root.absolutePath,
                    onOpenFolder = { runCatching { Desktop.getDesktop().open(AppDirs.root) } },
                    update = update,
                    onDownloadUpdate = { update?.let { u -> runCatching { Desktop.getDesktop().browse(java.net.URI(u.installerUrl)) } } },
                )
            }
        }
    }
}
