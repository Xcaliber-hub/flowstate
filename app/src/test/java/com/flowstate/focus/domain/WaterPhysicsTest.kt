package com.flowstate.focus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WaterPhysicsTest {
    @Test fun fillCountsTheRunningBlockAndCapsAtFull() {
        assertEquals(0.5f, WaterPhysics.fillFraction(30 * 60_000L, 30 * 60_000L, 120), 1e-4f)
        assertEquals(1f, WaterPhysics.fillFraction(200 * 60_000L, 0L, 120), 0f)
        assertEquals(0f, WaterPhysics.fillFraction(10_000L, 0L, 0), 0f)
    }

    @Test fun uprightPhoneIsLevel() {
        assertEquals(0f, WaterPhysics.targetAngle(0f, 9.81f), 1e-4f)
    }

    @Test fun rightEdgeDownRaisesTheRightSide() {
        assertTrue(WaterPhysics.targetAngle(-3f, 9f) > 0f)
        assertTrue(WaterPhysics.targetAngle(3f, 9f) < 0f)
    }

    @Test fun flatPhoneAndHardTiltStayCalmAndClamped() {
        assertEquals(0f, WaterPhysics.targetAngle(0.1f, 0.2f), 0f)
        assertEquals(WaterPhysics.MAX_TILT_RAD, WaterPhysics.targetAngle(-9.8f, 0.5f), 1e-4f)
    }

    @Test fun sloshOvershootsThenSettles() {
        var s = WaterPhysics.Slosh()
        val target = 0.4f
        var maxAngle = 0f
        repeat(600) { s = WaterPhysics.step(s, target, 1f / 60f); maxAngle = maxOf(maxAngle, s.angle) }
        assertTrue("should overshoot for a slosh", maxAngle > target)
        assertTrue("should settle", abs(s.angle - target) < 0.01f)
    }
}
