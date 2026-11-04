package com.flowstate.focus.push

import android.content.Context
import android.util.Log
import com.flowstate.focus.R
import com.flowstate.focus.data.groups.GroupsRepository
import com.flowstate.focus.data.prefs.SettingsRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps this phone subscribed to one FCM topic per group it belongs to ("chat_<groupId>"), and to none
 * when signed out or when Chat notifications is off. The relay sends each message to its group's topic.
 */
@Singleton
class ChatPushSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val groups: GroupsRepository,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("chat_push_topics", Context.MODE_PRIVATE)
    @Volatile private var desired: Set<String> = emptySet()

    val available: Boolean
        get() = context.getString(R.string.flowstate_push_url).isNotBlank() &&
            FirebaseApp.getApps(context).any { it.name == FirebaseApp.DEFAULT_APP_NAME }

    fun start() {
        if (!available) return
        scope.launch {
            combine(
                groups.state.map { s -> if (s.user == null) emptySet() else s.groups.map { it.id }.toSet() },
                settings.settings.map { it.chatNotifications },
            ) { ids, on -> if (on) ids.map { topic(it) }.toSet() else emptySet() }
                .distinctUntilChanged()
                .collect { want -> desired = want; apply(want) }
        }
    }

    fun resubscribeAll() {
        if (!available) return
        scope.launch { prefs.edit().clear().apply(); apply(desired) }
    }

    private suspend fun apply(want: Set<String>) {
        val have = prefs.getStringSet(KEY, emptySet()).orEmpty()
        val fcm = FirebaseMessaging.getInstance()
        val now = have.toMutableSet()
        (want - have).forEach { t -> runCatching { fcm.subscribeToTopic(t).await(); now += t }.onFailure { Log.w("Flowstate", "Subscribe $t failed", it) } }
        (have - want).forEach { t -> runCatching { fcm.unsubscribeFromTopic(t).await(); now -= t }.onFailure { Log.w("Flowstate", "Unsubscribe $t failed", it) } }
        prefs.edit().putStringSet(KEY, now).apply()
    }

    private fun topic(groupId: String) = "chat_" + groupId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    private companion object { const val KEY = "subscribed" }
}
