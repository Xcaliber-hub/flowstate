package com.flowstate.focus.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.flowstate.focus.MainActivity
import com.flowstate.focus.R
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive while a session runs and shows the ongoing notification.
 *
 * The notification uses the system chronometer, so the countdown ticks on its own and the service
 * only touches the notification when the state actually changes (start, pause, phase change).
 * That is what keeps battery use low.
 */
@AndroidEntryPoint
class TimerService : Service() {

    @Inject lateinit var engine: TimerEngine

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
        }
        ServiceCompat.startForeground(
            this,
            Notifications.ID_TIMER,
            buildNotification(engine.state.value),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (!observing) {
            observing = true
            scope.launch {
                engine.state.collect { s ->
                    if (s.status == Status.IDLE) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        nm.notify(Notifications.ID_TIMER, buildNotification(s))
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(s: TimerState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when (s.phase) {
            Phase.FOCUS -> if (s.subject.isNotBlank()) "Focus: ${s.subject}" else "Focus"
            Phase.SHORT_BREAK -> if (s.standaloneBreak) "Break" else "Short break"
            Phase.LONG_BREAK -> "Long break"
            Phase.IDLE -> "Flowstate"
        }
        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (s.status) {
            Status.RUNNING -> {
                builder.setShowWhen(true).setUsesChronometer(true)
                if (s.countsUp) {
                    builder.setWhen(s.phaseStartedAt)
                } else {
                    builder.setChronometerCountDown(true).setWhen(s.endAt)
                }
                if (s.mode == com.flowstate.focus.domain.TimerMode.POMODORO && s.phase == Phase.FOCUS) {
                    builder.setContentText("Round ${s.round + 1}")
                }
            }
            Status.PAUSED -> builder.setContentText(if (s.autoPaused) "Paused: you seemed away" else "Paused")
            Status.IDLE -> Unit
        }

        // A strict focus block has no way out from the notification either.
        val locked = s.strict && s.phase == Phase.FOCUS
        if (!locked && s.status != Status.IDLE) {
            if (s.status == Status.RUNNING) builder.addAction(0, "Pause", action(ACTION_PAUSE, 1))
            else builder.addAction(0, "Resume", action(ACTION_RESUME, 2))
            builder.addAction(0, if (s.isBreak) "End break" else "Stop", action(ACTION_STOP, 3))
        }
        return builder.build()
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
    }
}
