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
    const val ID_CHECK_IN = 4
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
            TimerEvent.BREAK_DONE -> "Break over" to "Ready when you are. Start your next focus block."
        }
        post(Notifications.ID_ALERT, title, text)
    }

    /** "Still focusing?" with an "I'm here" button. It disappears on its own when the answer window closes. */
    fun checkIn(timeoutMs: Long) {
        val here = PendingIntent.getService(
            context, 10,
            Intent(context, TimerService::class.java).setAction(TimerService.ACTION_CHECK_IN_OK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("Still focusing?")
            .setContentText("Tap I'm here, or the timer pauses in 2 minutes.")
            .setContentIntent(openApp())
            .addAction(0, "I'm here", here)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter(timeoutMs)
            .build()
        nm.notify(Notifications.ID_CHECK_IN, n)
    }

    fun cancelCheckIn() = nm.cancel(Notifications.ID_CHECK_IN)

    fun autoPaused() = post(Notifications.ID_CHECK_IN, "Timer paused", "You seemed away, so we stopped counting. Tap to resume.")

    fun nudge() = post(Notifications.ID_NUDGE, "Back to focus", "A focus session is still running.")

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun post(id: Int, title: String, text: String) {
        val open = openApp()
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
