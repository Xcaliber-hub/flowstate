package com.flowstate.focus

import android.app.Application
import javax.inject.Inject
import com.flowstate.focus.data.groups.GroupsRepository
import android.app.NotificationChannel
import android.app.NotificationManager
import com.flowstate.focus.service.Notifications
import dagger.hilt.android.HiltAndroidApp
import com.flowstate.focus.widgets.WidgetAlarms
import com.flowstate.focus.widgets.WidgetKind
import com.flowstate.focus.widgets.WidgetRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 1.8.8: the timer fields the widgets show. */
private data class TimerWidgetKey(
    val status: com.flowstate.focus.domain.Status,
    val phase: com.flowstate.focus.domain.Phase,
    val mode: com.flowstate.focus.domain.TimerMode,
    val round: Int,
    val phaseStartedAt: Long,
    val phaseDurationMs: Long,
    val pausedAt: Long,
    val strict: Boolean,
    val subject: String,
    val standaloneBreak: Boolean,
    val autoPaused: Boolean,
)

@HiltAndroidApp
class FlowstateApp : Application() {
    // Keep live group presence in sync even when the Groups tab is not open.
    @Inject lateinit var groupsRepository: GroupsRepository
    @Inject lateinit var chatPush: com.flowstate.focus.push.ChatPushSync
    // 1.6.0: one-device timer lock and synced sessions (only active while signed in).
    @Inject lateinit var cloudSync: com.flowstate.focus.data.sync.CloudSync
    // 1.7.0: ambient sounds follow the focus timer even while the Focus screen is closed.
    @Inject lateinit var ambient: com.flowstate.focus.service.ambient.AmbientController
    // 1.8.8: what the ten widgets show, watched here so they refresh when it changes (never on a timer loop).
    @Inject lateinit var timerEngine: com.flowstate.focus.service.TimerEngine
    @Inject lateinit var timerLock: com.flowstate.focus.data.sync.TimerLockState
    @Inject lateinit var settingsRepository: com.flowstate.focus.data.prefs.SettingsRepository
    @Inject lateinit var taskRepository: com.flowstate.focus.data.repo.TaskRepository
    @Inject lateinit var examRepository: com.flowstate.focus.data.exams.ExamRepository
    @Inject lateinit var syllabusRepository: com.flowstate.focus.data.syllabus.SyllabusRepository
    @Inject lateinit var sessionRepository: com.flowstate.focus.data.repo.SessionRepository
    @Inject lateinit var widgetData: com.flowstate.focus.widgets.FlowstateWidgetDataSource
    /** Kept as a field: SharedPreferences only holds its listeners weakly. */
    private var syllabusPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    // 1.8.7 app scope: an exception in a collector is logged and never crashes the app.
    private val widgetScope = com.flowstate.focus.util.AppScopes.background(Dispatchers.Default, "FlowstateApp.widgets")
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(Notifications.CHANNEL_TIMER, getString(R.string.channel_timer), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(Notifications.CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(Notifications.CHANNEL_CHAT, getString(R.string.channel_chat), NotificationManager.IMPORTANCE_HIGH)
        )
        initPushApp()
        chatPush.start()
        cloudSync.start()
        ambient.start()
        watchWidgetSources()
    }

    /**
     * 1.8.8: refreshes each widget when what it shows changes. Every source is mapped to the few fields the widgets
     * read and passed through distinctUntilChanged, so unrelated changes (a volume slider, a chat message) do not
     * redraw anything. The time on the timer widgets ticks by itself (Chronometer); only state changes come here.
     */
    private fun watchWidgetSources() {
        val app = this
        WidgetAlarms.scheduleMidnight(app)
        // Timer: start, pause, resume, phase change, stop, round. Also arms the once-a-minute ring step.
        widgetScope.launch {
            timerEngine.state
                .map { s -> TimerWidgetKey(s.status, s.phase, s.mode, s.round, s.phaseStartedAt, s.phaseDurationMs, s.pausedAt, s.strict, s.subject, s.standaloneBreak, s.autoPaused) }
                .distinctUntilChanged()
                .collect { key ->
                    WidgetRefresh.request(app, WidgetKind.TIMER)
                    WidgetAlarms.syncTimerStep(app, key.status == com.flowstate.focus.domain.Status.RUNNING)
                }
        }
        // Another device takes or releases the timer: the timer widgets must switch between starting and opening the app.
        widgetScope.launch {
            timerLock.remote
                .map { r -> r?.let { it.deviceId to it.state.status } }
                .distinctUntilChanged()
                .drop(1)
                .collect { WidgetRefresh.request(app, WidgetKind.TIMER) }
        }
        // Theme: dark/light mode, accent preset, dynamic colour. Compared with what the widgets were last drawn
        // with, so a change made while the process was dead is also caught on the next start.
        widgetScope.launch {
            settingsRepository.settings
                .map { widgetData.palette(it) }
                .distinctUntilChanged()
                .collect { checkWidgetTheme(it) }
        }
        widgetScope.launch {
            settingsRepository.settings.map { it.dailyGoalMin }.distinctUntilChanged().drop(1)
                .collect { WidgetRefresh.request(app, WidgetKind.HISTORY) }
        }
        widgetScope.launch {
            settingsRepository.settings.map { it.focusMin to it.strictByDefault }.distinctUntilChanged().drop(1)
                .collect { WidgetRefresh.request(app, WidgetKind.TIMER) }
        }
        widgetScope.launch {
            taskRepository.observe().distinctUntilChanged().drop(1)
                .collect { WidgetRefresh.request(app, setOf(WidgetKind.TASKS)) }
        }
        widgetScope.launch {
            examRepository.exams.distinctUntilChanged().drop(1)
                .collect { WidgetRefresh.request(app, setOf(WidgetKind.EXAM)) }
        }
        widgetScope.launch {
            syllabusRepository.progress.distinctUntilChanged().drop(1)
                .collect { WidgetRefresh.request(app, setOf(WidgetKind.EXAM)) }
        }
        // Session inserts and deletes, including sessions pulled from the cloud.
        widgetScope.launch {
            val since = System.currentTimeMillis() - 35L * 24 * 60 * 60_000L
            sessionRepository.sessionsSince(since).map { rows -> rows.map { it.id to it.durationMs } }.distinctUntilChanged().drop(1)
                .collect { WidgetRefresh.request(app, WidgetKind.HISTORY) }
        }
        // The exam picked on the Syllabus tab (SharedPreferences, not a flow).
        val prefs = getSharedPreferences(com.flowstate.focus.ui.syllabus.SyllabusViewModel.PREFS, MODE_PRIVATE)
        syllabusPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == com.flowstate.focus.ui.syllabus.SyllabusViewModel.KEY_EXAM) WidgetRefresh.request(app, setOf(WidgetKind.EXAM))
        }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
    }

    /** Redraws every widget when the colours they should use differ from the ones they were last drawn with. */
    private fun checkWidgetTheme(palette: com.flowstate.focus.widgets.WidgetPalette) {
        val signature = "${palette.dark}|${palette.accent}"
        val store = getSharedPreferences(WIDGET_THEME_PREFS, MODE_PRIVATE)
        if (store.getString(KEY_WIDGET_THEME, null) == signature) return
        store.edit().putString(KEY_WIDGET_THEME, signature).apply()
        WidgetRefresh.requestAll(this)
    }

    /**
     * System dark mode (with the app on "System") and, on Android 12+, a new wallpaper accent arrive as a
     * configuration change. Rotation and other changes leave the palette as it is, so they redraw nothing.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::settingsRepository.isInitialized) return
        widgetScope.launch {
            checkWidgetTheme(widgetData.palette(settingsRepository.settings.first()))
        }
    }

    private companion object {
        const val WIDGET_THEME_PREFS = "flowstate_widget_theme"
        const val KEY_WIDGET_THEME = "palette"
    }

    /**
     * FCM only works through the default Firebase app, while groups use the named "flowstate-groups" app.
     * Both point at the same project; the default one also carries the sender id that FCM needs.
     */
    private fun initPushApp() {
        val appId = getString(R.string.flowstate_firebase_application_id)
        val apiKey = getString(R.string.flowstate_firebase_api_key)
        val project = getString(R.string.flowstate_firebase_project_id)
        val sender = getString(R.string.flowstate_firebase_sender_id)
        if (listOf(appId, apiKey, project, sender).any { it.isBlank() }) return
        if (com.google.firebase.FirebaseApp.getApps(this).any { it.name == com.google.firebase.FirebaseApp.DEFAULT_APP_NAME }) return
        runCatching {
            com.google.firebase.FirebaseApp.initializeApp(
                this,
                com.google.firebase.FirebaseOptions.Builder()
                    .setApplicationId(appId).setApiKey(apiKey).setProjectId(project).setGcmSenderId(sender).build(),
            )
        }
    }
}
