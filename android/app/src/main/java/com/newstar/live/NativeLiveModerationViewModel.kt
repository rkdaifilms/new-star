package com.newstar.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class NativeLiveModerationUiState(
    val members: List<NativeLiveMember> = emptyList(),
    val actorRole: String = "viewer",
    val loading: Boolean = false,
    val busyUserId: String? = null,
    val error: String = "",
    val notice: String = ""
)

internal class NativeLiveModerationViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(NativeLiveModerationUiState())
    val state: StateFlow<NativeLiveModerationUiState> = mutableState.asStateFlow()
    private var pollJob: Job? = null
    private var roomId: String? = null
    private var session: NativeSession? = null

    fun start(activeRoomId: String, activeSession: NativeSession) {
        if (activeRoomId.isBlank()) return
        if (roomId == activeRoomId && session?.userId == activeSession.userId && pollJob?.isActive == true) return
        stop()
        roomId = activeRoomId
        session = activeSession
        mutableState.value = NativeLiveModerationUiState(loading = true)
        pollJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(5_000)
            }
        }
    }

    fun refresh() {
        val activeRoom = roomId ?: return
        val activeSession = session ?: return
        if (mutableState.value.loading && mutableState.value.members.isNotEmpty()) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = "") }
            try {
                val (role, members) = NativeLiveRepository.listModerationMembers(activeRoom, activeSession)
                mutableState.update { it.copy(members = members, actorRole = role, loading = false, error = "") }
            } catch (error: Exception) {
                mutableState.update { it.copy(loading = false, error = error.message ?: "LIVE participants लोड नहीं हुए।") }
            }
        }
    }

    fun setPermission(userId: String, permission: String, allowed: Boolean) {
        perform(userId) {
            NativeLiveRepository.manageLiveMemberPermission(
                roomId ?: return@perform,
                userId,
                permission,
                allowed,
                session ?: return@perform
            )
        }
    }

    fun admitGuest(member: NativeLiveMember) {
        if (member.participantId.isBlank() || member.status != "requested") return
        perform(member.userId) {
            NativeLiveRepository.admitGuest(
                roomId ?: return@perform,
                member.participantId,
                session ?: return@perform
            )
        }
    }

    fun setModerator(userId: String, moderator: Boolean) {
        perform(userId) {
            NativeLiveRepository.manageLiveMember(
                roomId ?: return@perform, userId, "set-moderator", moderator, session ?: return@perform
            )
        }
    }

    fun removeMember(userId: String) {
        perform(userId) {
            NativeLiveRepository.manageLiveMember(
                roomId ?: return@perform, userId, "remove-member", session = session ?: return@perform
            )
        }
    }

    private fun perform(userId: String, action: suspend () -> Unit) {
        if (mutableState.value.busyUserId != null) return
        mutableState.update { it.copy(busyUserId = userId, error = "", notice = "") }
        viewModelScope.launch {
            try {
                action()
                mutableState.update { it.copy(notice = "Participant settings updated.") }
                val activeRoom = roomId
                val activeSession = session
                if (activeRoom != null && activeSession != null) {
                    val (role, members) = NativeLiveRepository.listModerationMembers(activeRoom, activeSession)
                    mutableState.update { it.copy(members = members, actorRole = role) }
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(error = error.message ?: "Participant action पूरी नहीं हुई।") }
            } finally {
                mutableState.update { it.copy(busyUserId = null) }
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        roomId = null
        session = null
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
