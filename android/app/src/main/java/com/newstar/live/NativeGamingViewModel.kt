package com.newstar.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class NativeGamingUiState(
    val title: String = "",
    val preparing: Boolean = false,
    val streaming: Boolean = false,
    val micAvailable: Boolean = false,
    val micEnabled: Boolean = false,
    val statusMessage: String = "Gaming LIVE Android screen capture",
    val chatText: String = "",
    val chatMessages: List<JSONObject> = emptyList(),
    val reactionCounts: Map<String, Int> = mapOf("like" to 0, "heart" to 0, "clap" to 0, "fire" to 0),
    val chatLoading: Boolean = false,
    val chatSending: Boolean = false,
    val reactionBusy: Boolean = false,
    val chatError: String = "",
    val pkStatus: String = "idle",
    val pkMatchId: String = "",
    val pkOpponentName: String = "",
    val pkOwnScore: Int = 0,
    val pkOpponentScore: Int = 0,
    val pkBusy: Boolean = false
)

class NativeGamingViewModel(private val session: NativeSession) : ViewModel() {
    private val mutableState = MutableStateFlow(NativeGamingUiState())
    val state: StateFlow<NativeGamingUiState> = mutableState.asStateFlow()
    private val refreshMutex = Mutex()
    private var pollingJob: Job? = null
    private var pollingRoomId: String? = null
    private var pkPollingJob: Job? = null
    private var pkPollingRoomId: String? = null

    fun setTitle(value: String) {
        mutableState.update { it.copy(title = value) }
    }

    fun beginPreparing() {
        mutableState.update {
            it.copy(preparing = true, statusMessage = "Cloudflare LIVE room तैयार हो रहा है…", chatError = "")
        }
    }

    fun updateStreamStatus(status: String, message: String?, micAvailable: Boolean?, micEnabled: Boolean?) {
        mutableState.update { current ->
            current.copy(
                statusMessage = message ?: "Gaming LIVE status update",
                preparing = status == "starting" || status == "connecting",
                streaming = when (status) {
                    "connected" -> true
                    "error", "stopped" -> false
                    else -> current.streaming
                },
                micAvailable = micAvailable ?: current.micAvailable,
                micEnabled = micEnabled ?: current.micEnabled
            )
        }
    }

    fun setChatText(value: String) {
        mutableState.update { it.copy(chatText = value.take(500)) }
    }

    fun startPolling(roomId: String) {
        if (roomId.isBlank() || pollingRoomId == roomId && pollingJob?.isActive == true) return
        stopPolling()
        pollingRoomId = roomId
        pollingJob = viewModelScope.launch {
            refreshChat(roomId, showLoading = true)
            while (true) {
                delay(5_000)
                refreshChat(roomId, showLoading = false)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        pollingRoomId = null
    }

    fun refreshChat(showLoading: Boolean = true) {
        val roomId = pollingRoomId ?: return
        viewModelScope.launch { refreshChat(roomId, showLoading) }
    }

    private suspend fun refreshChat(roomId: String, showLoading: Boolean) {
        refreshMutex.withLock {
            if (showLoading) mutableState.update { it.copy(chatLoading = true) }
            try {
                val messages = NativeLiveRepository.listLiveChat(roomId, session)
                val counts = NativeLiveRepository.getReactionCounts(roomId, session)
                mutableState.update {
                    it.copy(
                        chatMessages = messages.takeLast(100),
                        reactionCounts = counts,
                        chatError = ""
                    )
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(chatError = error.message ?: "LIVE chat refresh नहीं हुई।") }
            } finally {
                if (showLoading) mutableState.update { it.copy(chatLoading = false) }
            }
        }
    }

    fun sendMessage(roomId: String) {
        val message = mutableState.value.chatText.trim()
        if (roomId.isBlank() || message.isBlank() || mutableState.value.chatSending) return
        mutableState.update { it.copy(chatSending = true, chatError = "") }
        viewModelScope.launch {
            try {
                val sent = NativeLiveRepository.sendLiveChatMessage(roomId, message, session)
                mutableState.update { current ->
                    current.copy(
                        chatMessages = (current.chatMessages + sent)
                            .distinctBy { it.optString("id") }.takeLast(100),
                        chatText = "",
                        chatError = ""
                    )
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(chatError = error.message ?: "Message भेजा नहीं गया।") }
            } finally {
                mutableState.update { it.copy(chatSending = false) }
            }
        }
    }

    fun sendReaction(roomId: String, reaction: String) {
        if (roomId.isBlank() || mutableState.value.reactionBusy) return
        mutableState.update { it.copy(reactionBusy = true, chatError = "") }
        viewModelScope.launch {
            try {
                val total = NativeLiveRepository.sendReaction(roomId, reaction, session)
                mutableState.update { current ->
                    current.copy(reactionCounts = current.reactionCounts + (reaction to total), chatError = "")
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(chatError = error.message ?: "Reaction भेजा नहीं गया।") }
            } finally {
                mutableState.update { it.copy(reactionBusy = false) }
            }
        }
    }

    fun joinPkQueue(roomId: String) {
        if (roomId.isBlank() || mutableState.value.pkBusy || mutableState.value.pkStatus != "idle") return
        mutableState.update { it.copy(pkBusy = true, chatError = "") }
        viewModelScope.launch {
            try {
                val response = NativeLiveRepository.joinPkQueue(roomId, session)
                val returnedStatus = response.optString("status").takeIf { it in setOf("waiting", "matched") } ?: "waiting"
                mutableState.update { it.copy(pkStatus = returnedStatus, chatError = "") }
                startPkPolling(roomId)
                refreshPkStatus(roomId)
            } catch (error: Exception) {
                mutableState.update { it.copy(chatError = error.message ?: "PK matchmaking में शामिल नहीं हो सके।") }
            } finally {
                mutableState.update { it.copy(pkBusy = false) }
            }
        }
    }

    fun startPkPolling(roomId: String) {
        if (roomId.isBlank() || pkPollingRoomId == roomId && pkPollingJob?.isActive == true) return
        stopPkPolling()
        pkPollingRoomId = roomId
        pkPollingJob = viewModelScope.launch {
            while (true) {
                refreshPkStatus(roomId)
                if (mutableState.value.pkStatus == "idle") break
                delay(3_500)
            }
            pkPollingJob = null
            pkPollingRoomId = null
        }
    }

    private suspend fun refreshPkStatus(roomId: String) {
        try {
            val result = NativeLiveRepository.getPkStatus(roomId, session)
            val status = result.optString("status").takeIf { it in setOf("waiting", "matched") } ?: "idle"
            mutableState.update {
                it.copy(
                    pkStatus = status,
                    pkMatchId = if (status == "matched") result.optString("match_id") else "",
                    pkOpponentName = if (status == "matched") result.optString("opponent_name", "LIVE creator") else "",
                    pkOwnScore = if (status == "matched") result.optInt("own_score") else 0,
                    pkOpponentScore = if (status == "matched") result.optInt("opponent_score") else 0,
                    chatError = ""
                )
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(chatError = error.message ?: "PK battle status लोड नहीं हुआ।") }
        }
    }

    fun cancelPk() {
        if (mutableState.value.pkBusy) return
        val current = mutableState.value
        if (current.pkStatus == "idle") return
        mutableState.update { it.copy(pkBusy = true, chatError = "") }
        viewModelScope.launch {
            try {
                if (current.pkStatus == "matched" && current.pkMatchId.isNotBlank()) {
                    NativeLiveRepository.finishPkMatch(current.pkMatchId, session)
                } else {
                    NativeLiveRepository.leavePkQueue(session)
                }
                stopPkPolling()
                mutableState.update {
                    it.copy(
                        pkStatus = "idle",
                        pkMatchId = "",
                        pkOpponentName = "",
                        pkOwnScore = 0,
                        pkOpponentScore = 0,
                        chatError = ""
                    )
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(chatError = error.message ?: "PK search या battle बंद नहीं हुई।") }
            } finally {
                mutableState.update { it.copy(pkBusy = false) }
            }
        }
    }

    fun stopPkPolling() {
        pkPollingJob?.cancel()
        pkPollingJob = null
        pkPollingRoomId = null
    }

    fun stopLive(onStop: () -> Unit) {
        if (mutableState.value.pkBusy) return
        mutableState.update { it.copy(pkBusy = true) }
        viewModelScope.launch {
            try {
                val current = mutableState.value
                when (current.pkStatus) {
                    "matched" -> if (current.pkMatchId.isNotBlank()) {
                        NativeLiveRepository.finishPkMatch(current.pkMatchId, session)
                    }
                    "waiting" -> NativeLiveRepository.leavePkQueue(session)
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(chatError = error.message ?: "PK cleanup पूरा नहीं हुआ; LIVE बंद किया जा रहा है।") }
            } finally {
                stopPolling()
                stopPkPolling()
                onStop()
                resetAfterStop()
            }
        }
    }

    fun resetAfterStop() {
        stopPolling()
        stopPkPolling()
        mutableState.update {
            it.copy(
                preparing = false,
                streaming = false,
                micAvailable = false,
                micEnabled = false,
                chatMessages = emptyList(),
                reactionCounts = mapOf("like" to 0, "heart" to 0, "clap" to 0, "fire" to 0),
                chatLoading = false,
                chatSending = false,
                reactionBusy = false,
                chatError = "",
                pkStatus = "idle",
                pkMatchId = "",
                pkOpponentName = "",
                pkOwnScore = 0,
                pkOpponentScore = 0,
                pkBusy = false
            )
        }
    }

    override fun onCleared() {
        stopPolling()
        stopPkPolling()
        super.onCleared()
    }

    class Factory(private val session: NativeSession) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NativeGamingViewModel::class.java)) {
                "Unsupported ViewModel type: ${modelClass.name}"
            }
            return NativeGamingViewModel(session) as T
        }
    }
}
