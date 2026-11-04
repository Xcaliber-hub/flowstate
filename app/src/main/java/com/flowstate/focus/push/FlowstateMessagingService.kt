package com.flowstate.focus.push

import com.flowstate.focus.data.groups.GroupsRepository
import com.flowstate.focus.data.prefs.SettingsRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Receives group chat pushes. They are high-priority data messages, so this runs even when the app is
 * closed, and the app decides here whether to show anything: never for my own messages, never while that
 * chat is on screen, and never once "Chat notifications" is off in Settings.
 */
@AndroidEntryPoint
class FlowstateMessagingService : FirebaseMessagingService() {
    @Inject lateinit var groups: GroupsRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var sync: ChatPushSync

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        if (d["type"] != "chat") return
        val groupId = d["groupId"] ?: return
        val enabled = runCatching { runBlocking { settings.settings.first().chatNotifications } }.getOrDefault(true)
        if (!enabled) return
        val myUid = groups.currentUid ?: runCatching {
            com.google.firebase.auth.FirebaseAuth.getInstance(com.google.firebase.FirebaseApp.getInstance("flowstate-groups")).currentUser?.uid
        }.getOrNull()
        if (d["uid"] != null && d["uid"] == myUid) return
        if (groups.isChatOnScreen(groupId)) return
        ChatNotifications.show(
            this,
            groupId = groupId,
            groupName = d["groupName"].orEmpty().ifBlank { "Group chat" },
            fromName = d["name"].orEmpty().ifBlank { "Someone" },
            text = d["text"].orEmpty(),
            sentAt = d["sentAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
        )
    }

    /** Topic subscriptions belong to the app install, so a fresh token means subscribing again. */
    override fun onNewToken(token: String) { sync.resubscribeAll() }
}
