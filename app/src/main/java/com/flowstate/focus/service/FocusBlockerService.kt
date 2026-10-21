package com.flowstate.focus.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.flowstate.focus.MainActivity
import com.flowstate.focus.data.prefs.SettingsRepository
import com.flowstate.focus.domain.AppSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sends you back to Flowstate when a blocked app comes to the foreground during a focus block.
 *
 * It blocks by package name, which is the most stable approach: package names do not change when
 * other apps redesign their screens. It listens only for window changes and never reads screen
 * content (canRetrieveWindowContent is false).
 *
 *  - Normal session: apps in the user's block list are blocked, other apps trigger a Focus Guard nudge.
 *  - Strict session: every app is blocked except Flowstate, system UI, the keyboard and the phone app.
 */
@AndroidEntryPoint
class FocusBlockerService : AccessibilityService() {

    @Inject lateinit var engine: TimerEngine
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var alerts: AlertNotifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settings = AppSettings()
    private var lastNudgeAt = 0L

    private val strictAllowed: Set<String> by lazy {
        val keyboards = getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList?.map { it.packageName }.orEmpty()
        val dialer = getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        (keyboards + listOfNotNull(dialer) + ALWAYS_ALLOWED + packageName).toSet()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        scope.launch { settingsRepo.settings.collect { settings = it } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in ALWAYS_ALLOWED) return
        val state = engine.state.value
        if (!state.isFocusRunning) return

        val blocked = if (state.strict) pkg !in strictAllowed else pkg in settings.blockedPackages
        when {
            blocked -> returnToApp()
            settings.focusGuard -> nudge()
        }
    }

    private fun returnToApp() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun nudge() {
        val now = System.currentTimeMillis()
        if (now - lastNudgeAt < NUDGE_INTERVAL_MS) return
        lastNudgeAt = now
        alerts.nudge()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        val ALWAYS_ALLOWED = setOf("com.android.systemui", "android")
        const val NUDGE_INTERVAL_MS = 30_000L
    }
}
