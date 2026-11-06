package com.flowstate.focus.domain

import androidx.compose.runtime.Immutable

/**
 * Ambient sounds. Rain, Café, Waves and Crickets are recorded loops (1.7.1, see NOTICE.md); Library,
 * Brown noise and Fireplace are synthesised on the phone. The mix is saved by [name], so new sounds
 * are only ever appended.
 */
enum class AmbientSound(val label: String) {
    RAIN("Rain"),
    LIBRARY("Library"),
    BROWN("Brown noise"),
    CAFE("Café"),
    FIRE("Fireplace"),
    WAVES("Waves"),
    CRICKETS("Crickets"),
}

/** "Follow the timer": play freely, play only through focus blocks, or stop after 30 minutes. */
enum class FollowTimer(val label: String) {
    OFF("Off"),
    WITH_FOCUS("With focus"),
    MINUTES_30("30 min");

    val description: String get() = when (this) {
        OFF -> "Plays until you pause it."
        WITH_FOCUS -> "Starts with each focus block and fades out when it ends."
        MINUTES_30 -> "Fades out 30 minutes after you press play."
    }
}

const val DEFAULT_SOUND_LEVEL = 0.6f

/**
 * The saved mix. [levels] keeps each sound's volume (0..1) even while it is switched off, so
 * turning a chip back on restores where it was.
 */
@Immutable
data class AmbientMix(
    val enabled: Set<AmbientSound> = setOf(AmbientSound.RAIN),
    val levels: Map<AmbientSound, Float> = emptyMap(),
    val master: Float = 0.8f,
    val autoPauseOnBreaks: Boolean = true,
    val follow: FollowTimer = FollowTimer.OFF,
) {
    fun level(s: AmbientSound): Float = levels[s] ?: DEFAULT_SOUND_LEVEL
    fun isOn(s: AmbientSound): Boolean = s in enabled && level(s) > 0.005f

    /** Sounds in display order that are on. */
    val active: List<AmbientSound> get() = AmbientSound.entries.filter { isOn(it) }

    /** "Rain + Library", "Rain + 2 more", or "Nothing selected". */
    val name: String get() {
        val on = active
        return when (on.size) {
            0 -> "No sounds on"
            1 -> on[0].label
            2 -> "${on[0].label} + ${on[1].label}"
            else -> "${on[0].label} + ${on.size - 1} more"
        }
    }

    fun toggle(s: AmbientSound): AmbientMix =
        if (isOn(s)) copy(enabled = enabled - s)
        else copy(enabled = enabled + s, levels = if (level(s) <= 0.005f) levels + (s to DEFAULT_SOUND_LEVEL) else levels)

    /** Dragging a slider also switches the sound on; dragging it to zero switches it off. */
    fun withLevel(s: AmbientSound, v: Float): AmbientMix {
        val level = v.coerceIn(0f, 1f)
        return if (level <= 0.005f) copy(enabled = enabled - s, levels = levels + (s to 0f))
        else copy(enabled = enabled + s, levels = levels + (s to level))
    }
}
