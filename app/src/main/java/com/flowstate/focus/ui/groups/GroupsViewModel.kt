package com.flowstate.focus.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowstate.focus.data.groups.GroupsRepository
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
    val timer: StateFlow<TimerState> = engine.state

    fun signIn(email: String, password: String) = repository.signIn(email.trim(), password)
    fun signUp(name: String, email: String, password: String) = repository.signUp(name.trim(), email.trim(), password)
    fun resetPassword(email: String) = repository.resetPassword(email.trim())
    fun signOut() = repository.signOut()
    fun updateProfile(name: String) = repository.updateProfile(name.trim())
    fun createGroup(name: String) = repository.createGroup(name.trim())
    fun joinGroup(code: String) = repository.joinGroup(code.trim())
    fun selectGroup(id: String) = repository.selectGroup(id)
    fun leaveGroup() = repository.leaveGroup()
    fun clearError() = repository.clearError()
}

