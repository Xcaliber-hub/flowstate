package com.flowstate.focus.ui.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowstate.focus.data.update.ApkInstaller
import com.flowstate.focus.data.update.ReleaseInfo
import com.flowstate.focus.data.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed interface UpdateState {
    data object Hidden : UpdateState
    data class Available(val release: ReleaseInfo, val currentVersion: String) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data class NeedsPermission(val release: ReleaseInfo, val apk: File) : UpdateState
    data class Failed(val release: ReleaseInfo) : UpdateState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checker: UpdateChecker,
    private val installer: ApkInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Hidden)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val currentVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    init {
        viewModelScope.launch {
            val release = checker.latestRelease() ?: return@launch
            if (!UpdateChecker.isNewer(release.version, currentVersion)) return@launch
            if (prefs.getString(KEY_SKIPPED, null) == release.version) return@launch
            _state.value = UpdateState.Available(release, currentVersion)
        }
    }

    fun update() {
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release
            else -> return
        }
        _state.value = UpdateState.Downloading(release, 0f)
        viewModelScope.launch {
            val apk = installer.download(release) { p ->
                _state.update { if (it is UpdateState.Downloading) it.copy(progress = p) else it }
            }
            when {
                apk == null -> _state.value = UpdateState.Failed(release)
                installer.canInstall() -> { installer.install(apk); _state.value = UpdateState.Hidden }
                else -> _state.value = UpdateState.NeedsPermission(release, apk)
            }
        }
    }

    fun openPermissionSettings() = installer.openInstallPermissionSettings()

    /** Called when the user returns from the settings screen. */
    fun retryInstall() {
        val s = _state.value as? UpdateState.NeedsPermission ?: return
        if (installer.canInstall()) { installer.install(s.apk); _state.value = UpdateState.Hidden }
    }

    /** "Later" hides the pop-up until the next launch. */
    fun later() { _state.value = UpdateState.Hidden }

    /** "Skip this version" hides it until an even newer release appears. */
    fun skip() {
        (_state.value as? UpdateState.Available)?.let { prefs.edit().putString(KEY_SKIPPED, it.release.version).apply() }
        _state.value = UpdateState.Hidden
    }

    private companion object { const val KEY_SKIPPED = "skipped_version" }
}
