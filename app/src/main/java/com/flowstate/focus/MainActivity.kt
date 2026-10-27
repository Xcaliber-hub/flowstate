package com.flowstate.focus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.flowstate.focus.data.prefs.SettingsRepository
import com.flowstate.focus.domain.AppSettings
import com.flowstate.focus.ui.FlowstateAppUi
import com.flowstate.focus.ui.theme.FlowstateTheme
import com.flowstate.focus.ui.update.UpdatePrompt
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(repo: SettingsRepository) : ViewModel() {
    /** Null until settings load, so the splash screen can cover the first frame and avoid a theme flash. */
    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { vm.settings.value == null }
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            settings?.let { s ->
                val dark = s.themeMode.isDark(isSystemInDarkTheme())
                DisposableEffect(dark) {
                    val transparent = android.graphics.Color.TRANSPARENT
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(transparent, transparent) { dark },
                        navigationBarStyle = SystemBarStyle.auto(transparent, transparent) { dark },
                    )
                    onDispose { }
                }
                FlowstateTheme(s, dark) {
                    FlowstateAppUi(s)
                    UpdatePrompt()
                }
            }
        }
    }
}
