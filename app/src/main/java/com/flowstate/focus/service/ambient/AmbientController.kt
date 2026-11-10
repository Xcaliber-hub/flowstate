package com.flowstate.focus.service.ambient

import android.content.Context
import androidx.compose.runtime.Immutable
import com.flowstate.focus.data.ambient.AmbientRepository
import com.flowstate.focus.domain.AmbientMix
import com.flowstate.focus.domain.AmbientSound
import com.flowstate.focus.domain.FollowTimer
import com.flowstate.focus.domain.Phase
import com.flowstate.focus.domain.Status
import com.flowstate.focus.domain.TimerState
import com.flowstate.focus.service.TimerEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Why the mix is quiet even though it is switched on. */
enum class AmbientHold { NONE, BREAK, TIMER_PAUSED, WAITING_FOR_FOCUS, NO_SOUNDS }

@Immutable
data class AmbientState(
    val mix: AmbientMix = AmbientMix(),
    /** The user (or "Follow the timer") has it switched on. */
    val on: Boolean = false,
    /** Sound is actually coming out right now. */
    val audible: Boolean = false,
    val hold: AmbientHold = AmbientHold.NONE,
    /** For "30 min": when it fades out (epoch ms), else 0. */
    val stopAt: Long = 0L,
)

/**
 * Ties the ambient mix to the user's play button, the saved mix and the focus timer:
 *  - Auto-pause on breaks: silent through any break, back when focus resumes.
 *  - Follow the timer › With focus: switches on as a focus block starts and fades out when it ends;
 *    pausing the timer pauses the sound.
 *  - Follow the timer › 30 min: fades out 30 minutes after play.
 */
@Singleton
class AmbientController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: AmbientRepository,
    private val engine: TimerEngine,
    private val player: AmbientPlayer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mix = MutableStateFlow(AmbientMix())
    private val on = MutableStateFlow(false)
    private val stopAt = MutableStateFlow(0L)
    private val _state = MutableStateFlow(AmbientState())
    val state: StateFlow<AmbientState> = _state.asStateFlow()
    private var started = false
    private var loaded = false
    private var fadeOutMs = 1500
    private var stopTimer: Job? = null

    fun start() {
        if (started) return
        started = true
        player.onFocusLost = { scope.launch { pause() } }
        scope.launch {
            mix.value = repo.mix.first()
            loaded = true
            var previous = engine.state.value
            combine(mix, on, stopAt, engine.state) { m, o, s, t -> Quad(m, o, s, t) }.collect { (m, o, s, t) ->
                if (m.follow == FollowTimer.WITH_FOCUS) followEdges(previous, t)
                previous = t
                apply(m, on.value, s, t)
            }
        }
    }

    private data class Quad(val m: AmbientMix, val o: Boolean, val s: Long, val t: TimerState)

    private fun focusRunning(t: TimerState) = t.phase == Phase.FOCUS && t.status == Status.RUNNING

    /** With focus: on when a new focus block starts, a long fade out when the block ends. */
    private fun followEdges(prev: TimerState, now: TimerState) {
        val startedBlock = !(prev.phase == Phase.FOCUS && prev.isActive)
        val endedBlock = prev.phase == Phase.FOCUS && prev.isActive && (now.phase != Phase.FOCUS || !now.isActive)
        if (startedBlock && focusRunning(now)) { fadeOutMs = 1500; on.value = true }
        if (endedBlock) { fadeOutMs = 4000; on.value = false }
    }

    private fun apply(m: AmbientMix, isOn: Boolean, until: Long, t: TimerState) {
        val hold = when {
            m.active.isEmpty() -> AmbientHold.NO_SOUNDS
            m.autoPauseOnBreaks && t.isBreak -> AmbientHold.BREAK
            m.follow == FollowTimer.WITH_FOCUS && t.phase == Phase.FOCUS && t.status == Status.PAUSED -> AmbientHold.TIMER_PAUSED
            m.follow == FollowTimer.WITH_FOCUS && !isOn && !t.isFocusInProgress -> AmbientHold.WAITING_FOR_FOCUS
            else -> AmbientHold.NONE
        }
        val audible = isOn && hold == AmbientHold.NONE
        val gains = FloatArray(AmbientSound.entries.size) { i ->
            val s = AmbientSound.entries[i]
            if (m.isOn(s)) m.level(s) * m.level(s) else 0f
        }
        val master = m.master * m.master
        for (i in gains.indices) gains[i] *= master
        player.setTargets(gains)
        player.setAudible(audible, if (audible) 1200 else fadeOutMs)
        _state.value = AmbientState(m, isOn, audible, hold, if (m.follow == FollowTimer.MINUTES_30 && isOn) until else 0L)
        if (isOn) com.flowstate.focus.service.TimerService.ensureRunning(context)
    }

    fun toggle() = if (on.value) pause() else play()

    fun play() {
        if (mix.value.active.isEmpty()) mix.value = mix.value.toggle(AmbientSound.RAIN).also(::persist)
        fadeOutMs = 1500
        armStopTimer(mix.value.follow)
        on.value = true
    }

    fun pause() {
        fadeOutMs = 1500
        stopTimer?.cancel(); stopAt.value = 0L
        on.value = false
    }

    /** Changes the mix at once; [save] false while a slider is still being dragged. */
    fun updateMix(save: Boolean = true, transform: (AmbientMix) -> AmbientMix) {
        val before = mix.value
        val next = transform(before)
        if (next != before) {
            if (next.follow != before.follow && on.value) armStopTimer(next.follow)
            mix.value = next
        }
        if (save) persist(next)
    }

    private fun persist(m: AmbientMix) {
        if (!loaded) return
        scope.launch { repo.save(m) }
    }

    private fun armStopTimer(follow: FollowTimer) {
        stopTimer?.cancel()
        if (follow != FollowTimer.MINUTES_30) { stopAt.value = 0L; return }
        val at = System.currentTimeMillis() + 30 * 60_000L
        stopAt.value = at
        stopTimer = scope.launch {
            while (System.currentTimeMillis() < at) delay(minOf(15_000L, at - System.currentTimeMillis()).coerceAtLeast(1L))
            fadeOutMs = 4000
            stopAt.value = 0L
            on.value = false
        }
    }
}
