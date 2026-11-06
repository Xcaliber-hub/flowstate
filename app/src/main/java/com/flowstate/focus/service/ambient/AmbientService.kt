package com.flowstate.focus.service.ambient

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.flowstate.focus.MainActivity
import com.flowstate.focus.R
import com.flowstate.focus.service.Notifications
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A media-playback foreground service that keeps the ambient mix playing with the screen off,
 * also when no focus session is running. It stops itself as soon as the mix is switched off.
 */
@AndroidEntryPoint
class AmbientService : Service() {
    @Inject lateinit var controller: AmbientController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> controller.toggle()
            ACTION_STOP -> controller.pause()
        }
        ServiceCompat.startForeground(
            this, Notifications.ID_AMBIENT, build(controller.state.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        if (!observing) {
            observing = true
            scope.launch {
                controller.state.collect { s ->
                    if (!s.on) {
                        running = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(android.app.NotificationManager::class.java).notify(Notifications.ID_AMBIENT, build(s))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    private fun build(s: AmbientState): Notification {
        val open = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (s.hold) {
            AmbientHold.BREAK -> "Paused for your break"
            AmbientHold.TIMER_PAUSED -> "Paused with the timer"
            AmbientHold.WAITING_FOR_FOCUS -> "Starts with your next focus block"
            AmbientHold.NO_SOUNDS -> "No sounds on"
            AmbientHold.NONE -> "Playing"
        }
        return NotificationCompat.Builder(this, Notifications.CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("Ambient · ${s.mix.name}")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop sounds", action(ACTION_STOP, 12))
            .build()
    }

    private fun action(name: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, AmbientService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_TOGGLE = "com.flowstate.focus.AMBIENT_TOGGLE"
        const val ACTION_STOP = "com.flowstate.focus.AMBIENT_STOP"
        @Volatile private var running = false

        /**
         * Starts the service if it is not up yet. Android may refuse a start from the background
         * (for example a Pomodoro block starting on its own); the timer's own foreground service keeps
         * the sound alive in that case, so the refusal is ignored.
         */
        fun ensureRunning(context: Context) {
            if (running) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, AmbientService::class.java))
                running = true
            }
        }
    }
}
