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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> engine.pause()
            ACTION_RESUME -> engine.resume()
            ACTION_STOP -> engine.stop()
            ACTION_CHECK_IN_OK -> engine.confirmPresence()
            ACTION_AMBIENT_STOP -> ambient.pause()
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
        if (!observing) {
            observing = true
            scope.launch {
                // Only what the notification shows: dragging a volume slider must not repost it many times a second.
                val shown = ambient.state.map { it.copy(mix = AmbientMix(enabled = it.mix.active.toSet()), stopAt = 0L) }.distinctUntilChanged()
                combine(engine.state, shown) { t, a -> t to a }.distinctUntilChanged().collect { (t, a) ->
                    if (!t.isActive && !a.on) {
                        foreground = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        nm.notify(Notifications.ID_TIMER, buildNotification(t, a))
                    }
                }
            }
        }
        return START_STICKY
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
