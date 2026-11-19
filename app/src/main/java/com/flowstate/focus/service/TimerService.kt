package com.flowstate.focus.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.flowstate.focus.MainActivity
import com.flowstate.focus.R
import com.flowstate.focus.domain.AmbientMix
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerMode
import com.flowstate.focus.domain.TimerState
import com.flowstate.focus.service.ambient.AmbientController
import com.flowstate.focus.service.ambient.AmbientHold
import com.flowstate.focus.service.ambient.AmbientState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The one foreground service of Flowstate. It keeps the process alive while a session runs and/or the
 * ambient mix is switched on, and shows a single ongoing notification for both.
 *
 * 1.7.2: the ambient sounds used to have their own media-playback service. Android 12+ refuses to start a
 * foreground service from the background, so when a Pomodoro focus block started on its own with the
 * screen off ("Follow the timer › With focus") that second service could not start, and during a session
 * there were two ongoing notifications. Now this service, already running for the session, simply also
 * hosts the sound: it is declared as specialUse|mediaPlayback and stays up while either is active.
 *
 * The notification uses the system chronometer, so the countdown ticks on its own and the service
 * only touches the notification when the state actually changes (start, pause, phase change).
 * That is what keeps battery use low.
 */
@AndroidEntryPoint
class TimerService : Service() {

    @Inject lateinit var engine: TimerEngine
    @Inject lateinit var ambient: AmbientController
    // 1.8.8: widget quick starts read the user's Pomodoro setup and strict default, and never start a session
    // while another device of the account holds the timer.
    @Inject lateinit var settingsRepo: com.flowstate.focus.data.prefs.SettingsRepository
    @Inject lateinit var timerLock: com.flowstate.focus.data.sync.TimerLockState

    private val scope = com.flowstate.focus.util.AppScopes.background(Dispatchers.Main.immediate, "TimerService")
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private var observing = false
    /** 1.8.7: newest start request; stopping with it never cancels a start that is still on its way. */
    @Volatile private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> engine.pause()
            ACTION_RESUME -> engine.resume()
            ACTION_STOP -> engine.stop()
            ACTION_CHECK_IN_OK -> engine.confirmPresence()
            ACTION_AMBIENT_STOP -> ambient.pause()
            ACTION_SKIP -> engine.skipPhase()
            ACTION_QUICK_START -> quickStart(intent)
            ACTION_BREAK -> startBreakFromWidget(intent)
        }
        // startForegroundService() obliges startForeground() even when there is nothing left to show; the
        // observer below then takes the service down again straight away.
        val started = runCatching {
            ServiceCompat.startForeground(this, Notifications.ID_TIMER, buildNotification(engine.state.value, ambient.state.value), foregroundTypes())
        }
        if (started.isFailure) {
            // A sticky restart from the background can be refused on Android 12+. Nothing to keep alive then.
            Log.w("Flowstate", "Could not enter the foreground", started.exceptionOrNull())
            foreground = false
            stopSelf()
            return START_NOT_STICKY
        }
        foreground = true
        lastStartId = startId
        if (observing && !engine.state.value.isActive && !ambient.state.value.on) {
            // Nothing to show any more and the observer below has already reported idle (it only reacts to
            // changes), so this start request stops the service itself.
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            scope.launch {
                // Only what the notification shows: dragging a volume slider must not repost it many times a second.
                val shown = ambient.state.map { it.copy(mix = AmbientMix(enabled = it.mix.active.toSet()), stopAt = 0L) }.distinctUntilChanged()
                combine(engine.state, shown) { t, a -> t to a }.distinctUntilChanged().collect { (t, a) ->
                    if (!t.isActive && !a.on) {
                        // 1.8.7: stopSelf() also discarded a startForegroundService() request that was already
                        // on its way (e.g. "Start" tapped as the last session ended), and Android 9+ then
                        // crashes the app with "startForegroundService() did not then call startForeground()".
                        // stopSelfResult(lastStartId) leaves the service running when a newer start exists.
                        foreground = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(lastStartId)
                    } else {
                        nm.notify(Notifications.ID_TIMER, buildNotification(t, a))
                    }
                }
            }
        }
        return START_STICKY
    }

    /**
     * 1.8.8: a session started from a widget. Runs synchronously, before startForeground() below, so the service
     * already sees the running timer and stays up. Ignored while a session is active here or on another device
     * (the widget opens the app in that case, this is only the backstop).
     */
    private fun quickStart(intent: Intent) {
        if (engine.state.value.isActive || timerLock.remote.value != null) return
        val st = currentSettings()
        val mode = com.flowstate.focus.domain.TimerMode.fromName(intent.getStringExtra(EXTRA_MODE))
        val minutes = intent.getIntExtra(EXTRA_MINUTES, st.focusMin).coerceIn(1, MAX_WIDGET_MINUTES)
        val plan = when (mode) {
            TimerMode.POMODORO -> com.flowstate.focus.domain.Plan(minutes * 60_000L, st.shortBreakMin * 60_000L, st.longBreakMin * 60_000L, st.rounds)
            TimerMode.COUNTDOWN -> com.flowstate.focus.domain.Plan(focusMs = minutes * 60_000L)
            TimerMode.STOPWATCH -> com.flowstate.focus.domain.Plan()
        }
        // Same rule as the Focus screen's Start: strict mode follows Settings › Strict by default.
        engine.start(mode, plan, st.strictByDefault, "")
    }

    private fun startBreakFromWidget(intent: Intent) {
        if (engine.state.value.isActive || timerLock.remote.value != null) return
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 5).coerceIn(1, 60)
        engine.startBreak(minutes * 60_000L, TimerMode.POMODORO)
    }

    /** Settings for a widget start. Usually cached; on a cold start (widget tapped with the app closed) read once. */
    private fun currentSettings(): com.flowstate.focus.domain.AppSettings =
        settingsCache.value ?: runCatching {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(2_000L) { settingsRepo.settings.first() }
            }
        }.getOrNull() ?: com.flowstate.focus.domain.AppSettings()

    private val settingsCache by lazy {
        settingsRepo.settings.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    }

    override fun onCreate() {
        super.onCreate()
        settingsCache // start collecting right away, so a later widget start does not have to wait
    }

    override fun onDestroy() {
        foreground = false
        scope.cancel()
        super.onDestroy()
    }

    /** Both types are claimed up front, so the sound never needs a (refusable) type change from the background. */
    private fun foregroundTypes(): Int = when {
        Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        else -> 0
    }

    private fun buildNotification(s: TimerState, a: AmbientState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (!s.isActive) {
            // Sounds only, no session.
            builder.setContentTitle("Ambient · ${a.mix.name}")
                .setContentText(ambientText(a.hold))
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            if (a.on) builder.addAction(0, "Stop sounds", action(ACTION_AMBIENT_STOP, 12))
            return builder.build()
        }

        val title = when (s.phase) {
            Phase.FOCUS -> if (s.subject.isNotBlank()) "Focus: ${s.subject}" else "Focus"
            Phase.SHORT_BREAK -> if (s.standaloneBreak) "Break" else "Short break"
            Phase.LONG_BREAK -> "Long break"
            Phase.IDLE -> "Flowstate"
        }
        builder.setContentTitle(title).setCategory(NotificationCompat.CATEGORY_PROGRESS)

        var text: String? = null
        when (s.status) {
            Status.RUNNING -> {
                builder.setShowWhen(true).setUsesChronometer(true)
                if (s.countsUp) {
                    builder.setWhen(s.phaseStartedAt)
                } else {
                    builder.setChronometerCountDown(true).setWhen(s.endAt)
                }
                if (s.mode == TimerMode.POMODORO && s.phase == Phase.FOCUS) text = "Round ${s.round + 1}"
            }
            Status.PAUSED -> text = if (s.autoPaused) "Paused: you seemed away" else "Paused"
            Status.IDLE -> Unit
        }
        if (a.on) {
            val sound = if (a.audible) "${a.mix.name} playing" else "${a.mix.name}: ${ambientText(a.hold).replaceFirstChar { it.lowercase() }}"
            text = if (text == null) sound else "$text · $sound"
        }
        text?.let { builder.setContentText(it) }

        // A strict focus block has no way out from the notification either.
        val locked = s.strict && s.phase == Phase.FOCUS
        if (!locked) {
            if (s.status == Status.RUNNING) builder.addAction(0, "Pause", action(ACTION_PAUSE, 1))
            else builder.addAction(0, "Resume", action(ACTION_RESUME, 2))
            builder.addAction(0, if (s.isBreak) "End break" else "Stop", action(ACTION_STOP, 3))
        }
        if (a.on) builder.addAction(0, "Stop sounds", action(ACTION_AMBIENT_STOP, 12))
        return builder.build()
    }

    private fun ambientText(hold: AmbientHold) = when (hold) {
        AmbientHold.BREAK -> "Paused for your break"
        AmbientHold.TIMER_PAUSED -> "Paused with the timer"
        AmbientHold.WAITING_FOR_FOCUS -> "Starts with your next focus block"
        AmbientHold.NO_SOUNDS -> "No sounds on"
        AmbientHold.NONE -> "Playing"
    }

    private fun action(name: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, TimerService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val ACTION_PAUSE = "com.flowstate.focus.PAUSE"
        const val ACTION_RESUME = "com.flowstate.focus.RESUME"
        const val ACTION_STOP = "com.flowstate.focus.STOP"
        const val ACTION_CHECK_IN_OK = "com.flowstate.focus.CHECK_IN_OK"
        const val ACTION_AMBIENT_STOP = "com.flowstate.focus.AMBIENT_STOP"
        /** 1.8.8 widget actions. */
        const val ACTION_SKIP = "com.flowstate.focus.SKIP"
        const val ACTION_QUICK_START = "com.flowstate.focus.QUICK_START"
        const val ACTION_BREAK = "com.flowstate.focus.START_BREAK"
        const val EXTRA_MODE = "com.flowstate.focus.extra.MODE"
        const val EXTRA_MINUTES = "com.flowstate.focus.extra.MINUTES"
        private const val MAX_WIDGET_MINUTES = 180

        /** True while this service is in the foreground (set on the main thread). */
        @Volatile private var foreground = false

        /**
         * Brings the service up for the ambient sound if it is not already in the foreground. When it is
         * already up (a session is running) this does nothing, which is what makes the background case
         * work. A refused start from the background is ignored; it is retried on the next change.
         */
        fun ensureRunning(context: Context) {
            if (foreground) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java)) }
                .onFailure { Log.w("Flowstate", "Could not start the session service", it) }
        }
    }
}
