package com.flowstate.focus.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowstate.focus.data.prefs.SettingsRepository
import com.flowstate.focus.data.repo.SessionRepository
import com.flowstate.focus.domain.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
    private val sessions: SessionRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    /** Writes every session to the file the user picked, as CSV. */
    fun export(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val rows = sessions.all()
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
                out.appendLine("started_at,duration_minutes,mode,subject,completed")
                rows.forEach { s ->
                    val subject = "\"" + s.subject.replace("\"", "\"\"") + "\""
                    out.appendLine("${Instant.ofEpochMilli(s.startedAt)},${s.durationMs / 60_000.0},${s.mode},$subject,${s.completed}")
                }
            }
        }
    }
}
