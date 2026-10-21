package com.flowstate.focus

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.flowstate.focus.service.Notifications
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FlowstateApp : Application() {
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
    }
}
