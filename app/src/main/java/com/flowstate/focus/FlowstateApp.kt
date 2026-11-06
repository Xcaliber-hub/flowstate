package com.flowstate.focus

import android.app.Application
import javax.inject.Inject
import com.flowstate.focus.data.groups.GroupsRepository
import android.app.NotificationChannel
import android.app.NotificationManager
import com.flowstate.focus.service.Notifications
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FlowstateApp : Application() {
    // Keep live group presence in sync even when the Groups tab is not open.
    @Inject lateinit var groupsRepository: GroupsRepository
    @Inject lateinit var chatPush: com.flowstate.focus.push.ChatPushSync
    // 1.6.0: one-device timer lock and synced sessions (only active while signed in).
    @Inject lateinit var cloudSync: com.flowstate.focus.data.sync.CloudSync
    // 1.7.0: ambient sounds follow the focus timer even while the Focus screen is closed.
    @Inject lateinit var ambient: com.flowstate.focus.service.ambient.AmbientController
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(Notifications.CHANNEL_TIMER, getString(R.string.channel_timer), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(Notifications.CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(Notifications.CHANNEL_CHAT, getString(R.string.channel_chat), NotificationManager.IMPORTANCE_HIGH)
        )
        initPushApp()
        chatPush.start()
        cloudSync.start()
        ambient.start()
    }

    /**
     * FCM only works through the default Firebase app, while groups use the named "flowstate-groups" app.
     * Both point at the same project; the default one also carries the sender id that FCM needs.
     */
    private fun initPushApp() {
        val appId = getString(R.string.flowstate_firebase_application_id)
        val apiKey = getString(R.string.flowstate_firebase_api_key)
        val project = getString(R.string.flowstate_firebase_project_id)
        val sender = getString(R.string.flowstate_firebase_sender_id)
        if (listOf(appId, apiKey, project, sender).any { it.isBlank() }) return
        if (com.google.firebase.FirebaseApp.getApps(this).any { it.name == com.google.firebase.FirebaseApp.DEFAULT_APP_NAME }) return
        runCatching {
            com.google.firebase.FirebaseApp.initializeApp(
                this,
                com.google.firebase.FirebaseOptions.Builder()
                    .setApplicationId(appId).setApiKey(apiKey).setProjectId(project).setGcmSenderId(sender).build(),
            )
        }
    }
}
