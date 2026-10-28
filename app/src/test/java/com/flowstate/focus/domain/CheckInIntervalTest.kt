package com.flowstate.focus.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CheckInIntervalTest {
    @Test fun labels() {
        assertEquals(listOf("30 min", "1 hr", "1.5 hr", "2 hr", "3 hr"), CHECK_IN_CHOICES.map(::checkInLabel))
    }

    @Test fun oddStoredValuesSnapToAChoice() {
        assertEquals(30, normalizeCheckInMinutes(5))
        assertEquals(90, normalizeCheckInMinutes(95))
        assertEquals(180, normalizeCheckInMinutes(600))
        assertEquals(60, normalizeCheckInMinutes(60))
    }

    @Test fun defaultIsOneHour() {
        assertEquals(60, AppSettings().checkInMinutes)
    }
}
