package com.flowstate.focus.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowstate.focus.data.groups.ChatMessage
import com.flowstate.focus.data.groups.GroupsRepository
import com.flowstate.focus.data.groups.EmojiPing
import com.flowstate.focus.domain.TimerState
import com.flowstate.focus.service.TimerEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val repository: GroupsRepository,
    engine: TimerEngine,
) : ViewModel() {
    val state = repository.state
    val chat = repository.chat
    val timer: StateFlow<TimerState> = engine.state

    fun signIn(email: String, password: String) = repository.signIn(email.trim(), password)
    fun signUp(name: String, email: String, password: String) = repository.signUp(name.trim(), email.trim(), password)
    fun resetPassword(email: String) = repository.resetPassword(email.trim())
    fun signOut() = repository.signOut()
    fun updateProfile(name: String, avatarId: String) = repository.updateProfile(name.trim(), avatarId)
    fun createGroup(name: String) = repository.createGroup(name.trim())
    fun joinGroup(code: String) = repository.joinGroup(code.trim())
    fun selectGroup(id: String) = repository.selectGroup(id)
    fun leaveGroup() = repository.leaveGroup()
    fun clearError() = repository.clearError()
    fun sendEmoji(groupId: String, toUid: String, emoji: String) = repository.sendEmoji(groupId, toUid, emoji)
    fun refreshPings() = repository.refreshPings()
    fun dismissPing(ping: EmojiPing) = repository.dismissPing(ping)
    fun openChat(groupId: String) = repository.openChat(groupId)
    fun closeChat() = repository.closeChat()
    fun setChatVisible(visible: Boolean) = repository.setChatVisible(visible)
    fun loadOlderChat() = repository.loadOlderChat()
    fun sendChat(groupId: String, text: String, replyTo: ChatMessage? = null): Boolean = repository.sendChat(groupId, text, replyTo)
    fun deleteChat(groupId: String, messageId: String) = repository.deleteChat(groupId, messageId)
    fun clearChatError() = repository.clearChatError()
}
