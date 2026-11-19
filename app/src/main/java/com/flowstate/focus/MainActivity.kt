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
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.flowstate.focus.data.prefs.SettingsRepository
import com.flowstate.focus.domain.AppSettings
import com.flowstate.focus.ui.FlowstateAppUi
import com.flowstate.focus.ui.focus.CheckInPrompt
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
    /** Group whose chat a tapped notification asked to open. */
    private var openChatGroup by androidx.compose.runtime.mutableStateOf<String?>(null)
    /** Group whose page a tapped Group focus widget asked to open. */
    private var openGroupId by androidx.compose.runtime.mutableStateOf<String?>(null)
    /** 1.8.8: screen a tapped widget asked to open ([ROUTE_TASKS], …), and whether to open "Add task" there. */
    private var openRoute by androidx.compose.runtime.mutableStateOf<String?>(null)
    private var openAddTask by androidx.compose.runtime.mutableStateOf(false)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(com.flowstate.focus.push.ChatNotifications.EXTRA_OPEN_CHAT)?.let { openChatGroup = it }
        intent.getStringExtra(com.flowstate.focus.widgets.GroupFocusWidgetProvider.EXTRA_OPEN_GROUP)?.let { openGroupId = it }
        readWidgetRoute(intent)
    }

    private fun readWidgetRoute(intent: android.content.Intent?) {
        val route = intent?.getStringExtra(EXTRA_OPEN_ROUTE) ?: return
        openAddTask = intent.getBooleanExtra(EXTRA_ADD_TASK, false)
        openRoute = route
    }

    override fun onResume() {
        super.onResume()
        com.flowstate.focus.widgets.WidgetRefresh.requestAll(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { vm.settings.value == null }
        if (savedInstanceState == null) {
            openChatGroup = intent?.getStringExtra(com.flowstate.focus.push.ChatNotifications.EXTRA_OPEN_CHAT)
            openGroupId = intent?.getStringExtra(com.flowstate.focus.widgets.GroupFocusWidgetProvider.EXTRA_OPEN_GROUP)
            readWidgetRoute(intent)
        }
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
                    FlowstateAppUi(
                        s, openChatGroup, openGroupId,
                        onChatGroupHandled = { openChatGroup = null }, onGroupHandled = { openGroupId = null },
                        openRoute = openRoute, openAddTask = openAddTask,
                        onRouteHandled = { openRoute = null; openAddTask = false },
                    )
                    CheckInPrompt()
                    UpdatePrompt()
                }
            }
        }
    }

    companion object {
        /** 1.8.8: a widget asks for a screen. One of the ROUTE_* values. */
        const val EXTRA_OPEN_ROUTE = "com.flowstate.focus.OPEN_ROUTE"
        /** With [ROUTE_TASKS]: open the Add task sheet (the Today's tasks widget's "+ Add task"). */
        const val EXTRA_ADD_TASK = "com.flowstate.focus.ADD_TASK"
        const val ROUTE_FOCUS = "focus"
        const val ROUTE_TASKS = "tasks"
        const val ROUTE_SYLLABUS = "syllabus"
        const val ROUTE_EXAMS = "exams"
        const val ROUTE_STATS = "stats"
        const val ROUTE_GROUPS = "groups"
    }
}
