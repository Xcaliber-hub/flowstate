package com.flowstate.focus.ui.ambient

import androidx.lifecycle.ViewModel
import com.flowstate.focus.domain.AmbientSound
import com.flowstate.focus.domain.FollowTimer
import com.flowstate.focus.domain.TimerState
import com.flowstate.focus.service.TimerEngine
import com.flowstate.focus.service.ambient.AmbientController
import com.flowstate.focus.service.ambient.AmbientState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** What the ambient bar and mixer can do; the ViewModel implements it, previews can fake it. */
interface AmbientActions {
    fun toggle()
    fun toggleSound(s: AmbientSound)
    fun setLevel(s: AmbientSound, v: Float)
    fun setMaster(v: Float)
    fun commit()
    fun setAutoPause(on: Boolean)
    fun setFollow(f: FollowTimer)
}

@HiltViewModel
class AmbientViewModel @Inject constructor(
    private val controller: AmbientController,
    engine: TimerEngine,
) : ViewModel(), AmbientActions {
    val state: StateFlow<AmbientState> = controller.state
    val timer: StateFlow<TimerState> = engine.state

    override fun toggle() = controller.toggle()
    override fun toggleSound(s: AmbientSound) = controller.updateMix { it.toggle(s) }
    override fun setLevel(s: AmbientSound, v: Float) = controller.updateMix(save = false) { it.withLevel(s, v) }
    override fun setMaster(v: Float) = controller.updateMix(save = false) { it.copy(master = v.coerceIn(0f, 1f)) }
    /** Saves the mix once a slider is let go. */
    override fun commit() = controller.updateMix { it }
    override fun setAutoPause(on: Boolean) = controller.updateMix { it.copy(autoPauseOnBreaks = on) }
    override fun setFollow(f: FollowTimer) = controller.updateMix { it.copy(follow = f) }
}
