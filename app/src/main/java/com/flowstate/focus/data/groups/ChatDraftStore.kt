package com.flowstate.focus.data.groups

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 1.8.7: the unsent message of each group chat, kept on this phone.
 *
 * Until 1.8.6 the draft lived only in the chat screen's saved UI state, which is thrown away whenever the
 * chat destination is popped: the screen popping itself while the group/account reloads after the process
 * was killed in the background, a chat notification or Group focus widget re-navigating to Groups, or the
 * user simply going back. The draft is now written here as it is typed and read back when the chat opens.
 * A message the server rejects is put back here too, so it survives the user having left the chat.
 * Cleared on sign-out.
 */
@Singleton
class ChatDraftStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("chat_drafts", Context.MODE_PRIVATE)

    fun get(groupId: String): String = prefs.getString(key(groupId), null).orEmpty()

    fun set(groupId: String, text: String) {
        val k = key(groupId)
        if (text.isBlank()) {
            if (prefs.contains(k)) prefs.edit().remove(k).apply()
        } else if (prefs.getString(k, null) != text) {
            prefs.edit().putString(k, text.take(CHAT_MAX_CHARS)).apply()
        }
    }

    /** Puts [text] back as the draft unless the user has already typed something newer. */
    fun restoreIfEmpty(groupId: String, text: String) {
        if (get(groupId).isBlank()) set(groupId, text)
    }

    fun clearAll() { prefs.edit().clear().apply() }

    private fun key(groupId: String) = "draft_$groupId"
}
