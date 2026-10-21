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
    val focusMin: Int = 25,
    val shortBreakMin: Int = 5,
    val longBreakMin: Int = 15,
    val rounds: Int = 4,
    val countdownMin: Int = 45,
    val dailyGoalMin: Int = 120,
    val keepScreenOn: Boolean = true,
    val strictByDefault: Boolean = false,
    val focusGuard: Boolean = true,
    val blockedPackages: Set<String> = emptySet(),
)
