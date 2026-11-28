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
import org.json.JSONObject

internal data class NativeLiveChatUiState(
    val messages: List<JSONObject> = emptyList(),
    val draft: String = "",
    val loading: Boolean = true,
    val sending: Boolean = false,
    val error: String = "",
    val reactionCounts: Map<String, Int> = emptyMap(),
    val reactionBusy: String? = null,
    val reactionNotice: String = ""
)

internal class NativeLiveChatViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(NativeLiveChatUiState())
    val state: StateFlow<NativeLiveChatUiState> = mutableState.asStateFlow()

    private var pollingJob: Job? = null
    private var activeRoomId: String? = null
    private var activeSession: NativeSession? = null

    fun start(roomId: String, session: NativeSession) {
        require(roomId.isNotBlank())
        if (activeRoomId == roomId &&
            activeSession?.userId == session.userId &&
            pollingJob?.isActive == true
        ) return

        stop()
        activeRoomId = roomId
        activeSession = session
        mutableState.value = NativeLiveChatUiState(loading = true)
        pollingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val messages = NativeLiveRepository.listLiveChat(roomId, session)
                    val counts = NativeLiveRepository.getReactionCounts(roomId, session)
                    mutableState.update {
                        it.copy(
                            messages = messages.takeLast(100),
                            loading = false,
                            error = ""
                        ).let { loaded -> loaded.copy(reactionCounts = counts) }
                    }
                } catch (error: Exception) {
                    mutableState.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: "LIVE chat अभी लोड नहीं हुई।"
                        )
                    }
                }
                delay(4_000)
            }
        }
    }

    fun setDraft(value: String) {
        mutableState.update { it.copy(draft = value.take(500)) }
    }

    fun sendMessage() {
        val roomId = activeRoomId ?: return
        val session = activeSession ?: return
        val message = mutableState.value.draft.trim()
        if (message.isEmpty() || mutableState.value.sending) return

        mutableState.update { it.copy(sending = true, error = "") }
        viewModelScope.launch {
            try {
                val sent = NativeLiveRepository.sendLiveChatMessage(roomId, message, session)
                mutableState.update { current ->
                    val id = sent.optString("id")
                    val alreadyPresent = current.messages.any { it.optString("id") == id }
                    current.copy(
                        messages = if (alreadyPresent) current.messages else (current.messages + sent).takeLast(100),
                        draft = if (current.draft.trim() == message) "" else current.draft,
                        error = ""
                    )
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(error = error.message ?: "LIVE message नहीं भेजा गया।")
                }
            } finally {
                mutableState.update { it.copy(sending = false) }
            }
        }
    }

    fun sendReaction(reaction: String) {
        val roomId = activeRoomId ?: return
        val session = activeSession ?: return
        if (reaction !in setOf("like", "heart", "clap", "fire") ||
            mutableState.value.reactionBusy != null
        ) return

        mutableState.update { it.copy(reactionBusy = reaction, reactionNotice = "") }
        viewModelScope.launch {
            try {
                val total = NativeLiveRepository.sendReaction(roomId, reaction, session)
                mutableState.update {
                    it.copy(reactionCounts = it.reactionCounts + (reaction to total))
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(reactionNotice = error.message ?: "Reaction भेजा नहीं गया।")
                }
            } finally {
                mutableState.update { it.copy(reactionBusy = null) }
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        activeRoomId = null
        activeSession = null
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
