package com.flowstate.focus.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flowstate.focus.domain.normalizeCheckInMinutes
import com.flowstate.focus.domain.AppSettings
import com.flowstate.focus.domain.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private object Keys {
    val THEME = stringPreferencesKey("theme")
    val DYNAMIC = booleanPreferencesKey("dynamic")
    val ACCENT = intPreferencesKey("accent")
    val LIQUID = booleanPreferencesKey("liquid")
    val SIDE_NAV = booleanPreferencesKey("side_nav")
    val FOCUS = intPreferencesKey("focus_min")
    val SHORT = intPreferencesKey("short_min")
    val LONG = intPreferencesKey("long_min")
    val ROUNDS = intPreferencesKey("rounds")
    val COUNTDOWN = intPreferencesKey("countdown_min")
    val GOAL = intPreferencesKey("goal_min")
    val KEEP_ON = booleanPreferencesKey("keep_on")
    val STRICT = booleanPreferencesKey("strict_default")
    val GUARD = booleanPreferencesKey("guard")
    val INACTIVITY = booleanPreferencesKey("inactivity_check")
    val CHECK_IN_MINUTES = intPreferencesKey("check_in_minutes")
    val BLOCKED = stringSetPreferencesKey("blocked")
}

private fun Preferences.toSettings(): AppSettings {
    val d = AppSettings()
    return AppSettings(
        themeMode = this[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
        dynamicColor = this[Keys.DYNAMIC] ?: d.dynamicColor,
        accentIndex = this[Keys.ACCENT] ?: d.accentIndex,
        liquidUi = this[Keys.LIQUID] ?: d.liquidUi,
        sideNav = this[Keys.SIDE_NAV] ?: d.sideNav,
        focusMin = this[Keys.FOCUS] ?: d.focusMin,
        shortBreakMin = this[Keys.SHORT] ?: d.shortBreakMin,
        longBreakMin = this[Keys.LONG] ?: d.longBreakMin,
        rounds = this[Keys.ROUNDS] ?: d.rounds,
        countdownMin = this[Keys.COUNTDOWN] ?: d.countdownMin,
        dailyGoalMin = this[Keys.GOAL] ?: d.dailyGoalMin,
        keepScreenOn = this[Keys.KEEP_ON] ?: d.keepScreenOn,
        strictByDefault = this[Keys.STRICT] ?: d.strictByDefault,
        focusGuard = this[Keys.GUARD] ?: d.focusGuard,
        inactivityCheck = this[Keys.INACTIVITY] ?: d.inactivityCheck,
        checkInMinutes = normalizeCheckInMinutes(this[Keys.CHECK_IN_MINUTES] ?: d.checkInMinutes),
        blockedPackages = this[Keys.BLOCKED] ?: d.blockedPackages,
    )
}

private fun MutablePreferences.write(s: AppSettings) {
    this[Keys.THEME] = s.themeMode.name
    this[Keys.DYNAMIC] = s.dynamicColor
    this[Keys.ACCENT] = s.accentIndex
    this[Keys.LIQUID] = s.liquidUi
    this[Keys.SIDE_NAV] = s.sideNav
    this[Keys.FOCUS] = s.focusMin
    this[Keys.SHORT] = s.shortBreakMin
    this[Keys.LONG] = s.longBreakMin
    this[Keys.ROUNDS] = s.rounds
    this[Keys.COUNTDOWN] = s.countdownMin
    this[Keys.GOAL] = s.dailyGoalMin
    this[Keys.KEEP_ON] = s.keepScreenOn
    this[Keys.STRICT] = s.strictByDefault
    this[Keys.GUARD] = s.focusGuard
    this[Keys.INACTIVITY] = s.inactivityCheck
    this[Keys.CHECK_IN_MINUTES] = normalizeCheckInMinutes(s.checkInMinutes)
    this[Keys.BLOCKED] = s.blockedPackages
}

/** DataStore-backed settings. Every change goes through [update] so reads and writes stay atomic. */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { it.write(transform(it.toSettings())) }
    }
}
