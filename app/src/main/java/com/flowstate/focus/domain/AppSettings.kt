package com.flowstate.focus.domain

import androidx.compose.runtime.Immutable

enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

@Immutable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val accentIndex: Int = 0,
    val liquidUi: Boolean = true,
    /** Experimental: a vertical navigation rail on the right edge instead of the bottom bar. */
    val sideNav: Boolean = false,
    val focusMin: Int = 25,
    val shortBreakMin: Int = 5,
    val longBreakMin: Int = 15,
    val rounds: Int = 4,
    val countdownMin: Int = 45,
    val dailyGoalMin: Int = 120,
    val keepScreenOn: Boolean = true,
    val strictByDefault: Boolean = false,
    val focusGuard: Boolean = true,
    /** Asks "Still focusing?" every [checkInMinutes] of a focus block and pauses if nobody answers. */
    val inactivityCheck: Boolean = true,
    /** How much running focus time passes between check-ins. One of [CHECK_IN_CHOICES]. */
    val checkInMinutes: Int = 60,
    val blockedPackages: Set<String> = emptySet(),
)

/** The check-in intervals offered in Settings, in minutes. */
val CHECK_IN_CHOICES = listOf(30, 60, 90, 120, 180)

/** Snaps a stored value to an offered interval, so an odd or old value never breaks scheduling. */
fun normalizeCheckInMinutes(minutes: Int): Int = CHECK_IN_CHOICES.minBy { kotlin.math.abs(it - minutes) }

/** "30 min", "1 hr", "1.5 hr", "2 hr", "3 hr". */
fun checkInLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} hr"
    else -> "${minutes / 60}.${(minutes % 60) * 10 / 60} hr"
}
