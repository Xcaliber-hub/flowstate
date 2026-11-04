package com.flowstate.focus.push

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.flowstate.focus.MainActivity
import com.flowstate.focus.R
import com.flowstate.focus.service.Notifications

/** One conversation-style notification per group, stacking the newest messages like a messenger does. */
object ChatNotifications {
    const val EXTRA_OPEN_CHAT = "com.flowstate.focus.OPEN_CHAT_GROUP"
    private const val MAX_LINES = 7

    private fun idFor(groupId: String) = 10_000 + (groupId.hashCode() and 0x0FFFFFFF)

    fun show(context: Context, groupId: String, groupName: String, fromName: String, text: String, sentAt: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        val id = idFor(groupId)
        val me = Person.Builder().setName("You").build()
        // Carry over the lines already showing so a burst of messages reads as one thread.
        val previous = nm.activeNotifications.firstOrNull { it.id == id }?.notification
            ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(groupName)
            .setGroupConversation(true)
        previous?.messages?.takeLast(MAX_LINES - 1)?.forEach { style.addMessage(it) }
        style.addMessage(text, sentAt, Person.Builder().setName(fromName).build())

        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN_CHAT, groupId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setWhen(sentAt)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    /** Clears [groupId]'s notification, e.g. when its chat opens. */
    fun clear(context: Context, groupId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(idFor(groupId)) }
    }
}
