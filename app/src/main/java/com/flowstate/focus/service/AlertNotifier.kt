package com.flowstate.focus.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.flowstate.focus.MainActivity
import com.flowstate.focus.R
import com.flowstate.focus.domain.TimerEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

object Notifications {
    const val CHANNEL_TIMER = "timer"
    const val CHANNEL_ALERTS = "alerts"
    const val ID_TIMER = 1
    const val ID_ALERT = 2
    const val ID_NUDGE = 3
}

/**
 * Posts heads-up notifications for phase changes and Focus Guard nudges. It is independent of
 * [TimerService] so an alert still arrives when the service is shutting down at the end of a session.
 */
@Singleton
class AlertNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    fun alert(event: TimerEvent) {
        val (title, text) = when (event) {
            TimerEvent.FOCUS_DONE_BREAK_NEXT -> "Focus complete" to "Nice work. Time for a break."
            TimerEvent.BREAK_DONE_FOCUS_NEXT -> "Break over" to "Your next focus block has started."
            TimerEvent.SESSION_DONE -> "Session complete" to "You stayed with it to the end."
        }
        post(Notifications.ID_ALERT, title, text)
    }

    fun nudge() = post(Notifications.ID_NUDGE, "Back to focus", "A focus session is still running.")

    private fun post(id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter(60_000L)
            .build()
        nm.notify(id, notification)
    }
}
