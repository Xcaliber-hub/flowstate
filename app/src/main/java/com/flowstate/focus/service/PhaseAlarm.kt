package com.flowstate.focus.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 1.7.2: wakes the phone when a timed phase ends.
 *
 * The engine sleeps in a coroutine until the phase ends, but coroutine delays run on the monotonic clock,
 * which stops while the phone is in deep sleep. With the screen off a 25 minute block could therefore end
 * (and its "Focus complete" alert and the next phase arrive) many minutes late. An alarm at the phase's
 * end wakes the CPU on time; whichever of the two fires first finishes the phase, the other finds
 * nothing to do.
 */
internal class PhaseAlarm(private val context: Context) {
    private val am by lazy { context.getSystemService(AlarmManager::class.java) }

    private fun pending(flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, REQUEST_CODE,
        Intent(context, PhaseAlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or flags,
    )

    fun set(atMillis: Long) {
        val pi = pending(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } else {
                // Without the exact-alarm permission: still allowed in Doze, a few minutes late at worst.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        }.onFailure { Log.w("Flowstate", "Phase alarm not set", it) }
    }

    fun cancel() {
        val pi = pending(PendingIntent.FLAG_NO_CREATE) ?: return
        runCatching { am.cancel(pi) }
        pi.cancel()
    }

    companion object {
        const val ACTION = "com.flowstate.focus.PHASE_ALARM"
        private const val REQUEST_CODE = 20
    }
}

@AndroidEntryPoint
class PhaseAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: TimerEngine

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PhaseAlarm.ACTION) return
        engine.onPhaseAlarm()
        // An exact alarm may start a foreground service from the background: bring the notification back
        // if the process had been killed and the session continues.
        if (engine.state.value.isActive) TimerService.ensureRunning(context)
    }
}
