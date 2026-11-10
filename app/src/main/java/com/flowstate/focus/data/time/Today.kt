package com.flowstate.focus.data.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 1.7.2: the current local date as a flow, so screens roll over at midnight instead of keeping the day
 * they were opened on (D-Day countdowns, "Today", streaks, the daily goal).
 *
 * Listens to the system's minute tick (sent only while the screen is on, so it costs nothing in the
 * background) plus date, time and time-zone changes, and emits only when the date actually changes.
 */
fun todayFlow(context: Context): Flow<LocalDate> = callbackFlow {
    trySend(LocalDate.now())
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) { trySend(LocalDate.now()) }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_TIME_TICK)
        addAction(Intent.ACTION_DATE_CHANGED)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }
    val app = context.applicationContext
    // System broadcasts only; RECEIVER_NOT_EXPORTED is fine for them.
    ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    awaitClose { runCatching { app.unregisterReceiver(receiver) } }
}.distinctUntilChanged()

/** Injectable form of [todayFlow] for view models. */
@Singleton
class TodayProvider @Inject constructor(@ApplicationContext private val context: Context) {
    val today: Flow<LocalDate> get() = todayFlow(context)
}

/** The current date for a composable; recomposes when the date changes while the screen is visible. */
@Composable
fun rememberToday(): State<LocalDate> {
    val context = LocalContext.current
    val flow = remember(context) { todayFlow(context) }
    return flow.collectAsStateWithLifecycle(initialValue = LocalDate.now())
}
