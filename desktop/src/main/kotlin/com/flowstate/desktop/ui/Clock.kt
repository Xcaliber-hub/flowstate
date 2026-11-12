package com.flowstate.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerState
import kotlinx.coroutines.delay

/**
 * The app's clock tick (1.8.1 performance fix).
 *
 * Until 1.8.0 the window read `now` at the top of App every 250 ms, so the whole window recomposed,
 * re-laid out and repainted four times a second, even with the timer idle. Now the clock is one State
 * handed down unread; only the few leaf composables that show a time read it, through [derivedFromNow],
 * and the tick itself comes only as often as something on screen can change (see [nextTickDelayMs]).
 */
val LocalNow = staticCompositionLocalOf<State<Long>> { mutableLongStateOf(System.currentTimeMillis()) }

/** A clock State that ticks after each [nextDelay] (given the current time). Read it only through [derivedFromNow]. */
@Composable
fun rememberTickingClock(nextDelay: (Long) -> Long): State<Long> {
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(nextDelay) {
        while (true) {
            delay(nextDelay(System.currentTimeMillis()).coerceIn(50L, 60_000L))
            clock.longValue = System.currentTimeMillis()
        }
    }
    return clock
}

/**
 * A value computed from the current time. The caller recomposes only when the result changes (by equals),
 * not on every tick, so quantise the result to what is shown (seconds, minutes). The tick only triggers
 * the check; the value always uses the real time, so a timer that just started is right on its first frame.
 */
@Composable
fun <T> derivedFromNow(calc: (Long) -> T): T {
    val clock = LocalNow.current
    val latest = rememberUpdatedState(calc)
    val state = remember(clock) {
        derivedStateOf(structuralEqualityPolicy()) {
            val tick = clock.value
            latest.value(maxOf(tick, System.currentTimeMillis()))
        }
    }
    return state.value
}

/**
 * How long until the next visible change: the next whole second of a running timer (this PC's, the other
 * device's, or a friend's live clock on the Groups board), otherwise the next minute (week countdown, day
 * rollover). An idle window therefore draws about once a minute instead of four times a second.
 * Other clocks on screen tick once a second too, at most a second behind their own boundary.
 */
fun nextTickDelayMs(now: Long, local: TimerState, remote: TimerState?, friendLive: TimerState?): Long = when {
    local.status == Status.RUNNING -> untilNextSecond(local, now)
    remote != null && remote.status == Status.RUNNING -> untilNextSecond(remote, now)
    friendLive != null && friendLive.status == Status.RUNNING -> untilNextSecond(friendLive, now)
    else -> 60_000L - now % 60_000L + 5L
}

/** Milliseconds until [t]'s clock text changes (plus a few ms, so the new second is already showing). */
fun untilNextSecond(t: TimerState, now: Long): Long {
    val part = if (t.countsUp) 1000L - t.elapsedAt(now).coerceAtLeast(0) % 1000L
    else (t.remainingAt(now).coerceAtLeast(0) % 1000L).let { if (it == 0L) 1000L else it }
    return part + 5L
}
