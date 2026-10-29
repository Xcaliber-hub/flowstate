package com.flowstate.focus.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The maths behind the daily-goal water bar, kept free of Android so it can be unit tested.
 *
 * The water surface is modelled as one angle (radians, positive when it rises toward the right edge
 * of the screen) driven by a damped spring toward the angle that keeps it level with the real world.
 * The spring is under-damped on purpose: tip the phone and the water overshoots, sloshes back and
 * settles, which is the "tilt animation".
 */
object WaterPhysics {
    /** The surface never tilts further than this inside the bar, so it always reads as a level. */
    const val MAX_TILT_RAD = (38.0 * PI / 180.0).toFloat()
    private const val STIFFNESS = 55f
    private const val DAMPING = 5.5f

    data class Slosh(val angle: Float = 0f, val velocity: Float = 0f)

    /** Fraction of today's goal reached, 0..1, counting the focus block that is running right now. */
    fun fillFraction(recordedTodayMs: Long, liveMs: Long, goalMin: Int): Float {
        if (goalMin <= 0) return 0f
        val done = (recordedTodayMs + liveMs.coerceAtLeast(0L)).toFloat()
        return (done / (goalMin * 60_000f)).coerceIn(0f, 1f)
    }

    /**
     * The surface angle that stays level with the ground, from the gravity sensor's x and y
     * (m/s², device axes). With the phone tipped right-edge-down, x goes negative and the water
     * piles up on the right, so the surface rises toward the right. Lying flat, x and y shrink and
     * the target eases back to level instead of jumping on sensor noise.
     */
    fun targetAngle(gx: Float, gy: Float): Float {
        val inPlane = sqrt(gx * gx + gy * gy)
        if (inPlane < 0.5f) return 0f
        val roll = atan2(-gx, gy)
        val confidence = min(1f, inPlane / 4f)
        return (roll * confidence).coerceIn(-MAX_TILT_RAD, MAX_TILT_RAD)
    }

    /** Advances the slosh by [dtSec], clamped so a dropped frame cannot blow the spring up. */
    fun step(s: Slosh, target: Float, dtSec: Float): Slosh {
        val dt = dtSec.coerceIn(0f, 1f / 20f)
        val accel = STIFFNESS * (target - s.angle) - DAMPING * s.velocity
        val v = s.velocity + accel * dt
        val a = (s.angle + v * dt).coerceIn(-MAX_TILT_RAD, MAX_TILT_RAD)
        return Slosh(a, v)
    }

    /** Ripple height as a share of the bar width: calm when idle, livelier while focusing and when sloshing. */
    fun waveAmplitude(running: Boolean, velocity: Float): Float =
        ((if (running) 0.05f else 0.018f) + abs(velocity) * 0.025f).coerceAtMost(0.14f)
}
