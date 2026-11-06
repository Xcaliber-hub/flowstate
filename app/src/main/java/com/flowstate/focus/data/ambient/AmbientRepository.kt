package com.flowstate.focus.data.ambient

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flowstate.focus.domain.AmbientMix
import com.flowstate.focus.domain.AmbientSound
import com.flowstate.focus.domain.FollowTimer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ambientStore: DataStore<Preferences> by preferencesDataStore(name = "ambient")

private object AmbientKeys {
    val ENABLED = stringSetPreferencesKey("enabled")
    val MASTER = floatPreferencesKey("master")
    val AUTO_PAUSE = booleanPreferencesKey("auto_pause_breaks")
    val FOLLOW = stringPreferencesKey("follow")
    fun level(s: AmbientSound) = floatPreferencesKey("level_${s.name.lowercase()}")
}

/** The saved ambient mix, in its own DataStore file so app settings stay untouched. */
@Singleton
class AmbientRepository @Inject constructor(@ApplicationContext private val context: Context) {

    val mix: Flow<AmbientMix> = context.ambientStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            val d = AmbientMix()
            AmbientMix(
                enabled = p[AmbientKeys.ENABLED]?.mapNotNull { n -> AmbientSound.entries.firstOrNull { it.name == n } }?.toSet() ?: d.enabled,
                levels = AmbientSound.entries.mapNotNull { s -> p[AmbientKeys.level(s)]?.let { s to it } }.toMap(),
                master = p[AmbientKeys.MASTER] ?: d.master,
                autoPauseOnBreaks = p[AmbientKeys.AUTO_PAUSE] ?: d.autoPauseOnBreaks,
                follow = p[AmbientKeys.FOLLOW]?.let { n -> FollowTimer.entries.firstOrNull { it.name == n } } ?: d.follow,
            )
        }

    suspend fun save(m: AmbientMix) {
        context.ambientStore.edit { p ->
            p[AmbientKeys.ENABLED] = m.enabled.map { it.name }.toSet()
            m.levels.forEach { (s, v) -> p[AmbientKeys.level(s)] = v.coerceIn(0f, 1f) }
            p[AmbientKeys.MASTER] = m.master.coerceIn(0f, 1f)
            p[AmbientKeys.AUTO_PAUSE] = m.autoPauseOnBreaks
            p[AmbientKeys.FOLLOW] = m.follow.name
        }
    }
}
