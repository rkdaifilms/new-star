package com.newstar.live

import android.content.Context
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
    val pkBusy: Boolean = false,
    val members: List<JSONObject> = emptyList(),
    val actorRole: String = "viewer",
    val moderationLoading: Boolean = false,
    val moderationBusyUserId: String = "",
    val moderationError: String = "",
    val creatorQuery: String = "",
    val creatorSearchResults: List<NativeLiveCreator> = emptyList(),
    val creatorSearching: Boolean = false,
    val inviteSendingUserId: String = "",
    val invitationError: String = "",
    val invitations: List<JSONObject> = emptyList(),
    val invitationsLoading: Boolean = false,
    val invitationBusyId: String = "",
    val invitationStatusMessage: String = "",
    val guestRoomId: String = "",
    val guestSeatStatus: String = "",
    val guestSeatNumber: Int = 0,
    val guestCameraAllowed: Boolean = false,
    val guestMicrophoneAllowed: Boolean = false,
    val guestPublisherReady: Boolean = false,
    val guestPublisherStarting: Boolean = false,
    val guestPublisherCameraEnabled: Boolean = true,
    val guestPublisherMicEnabled: Boolean = false,
    val guestPublisherMicAvailable: Boolean = false,
    val guestMediaUpdating: Boolean = false,
    val guestPublisherError: String = ""
)

class NativeGamingViewModel(private val session: NativeSession) : ViewModel() {
    private val mutableState = MutableStateFlow(NativeGamingUiState())
    val state: StateFlow<NativeGamingUiState> = mutableState.asStateFlow()
    private val refreshMutex = Mutex()
    private var pollingJob: Job? = null
    private var pollingRoomId: String? = null
    private var pkPollingJob: Job? = null
    private var pkPollingRoomId: String? = null
    private var guestPublisher: NativeSfuPublisher? = null
    private var guestSeatRefreshBusy = false

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

    fun refreshMembers(roomId: String) {
        if (roomId.isBlank() || mutableState.value.moderationLoading) return
        mutableState.update { it.copy(moderationLoading = true, moderationError = "") }
        viewModelScope.launch {
            try {
                val result = NativeLiveRepository.listMembers(roomId, session)
                val rows = result.optJSONArray("members")
                val members = if (rows == null) emptyList() else
                    (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                mutableState.update {
                    it.copy(
                        members = members,
                        actorRole = result.optString("actor_role", "viewer"),
                        moderationError = ""
                    )
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(moderationError = error.message ?: "LIVE participants लोड नहीं हुए।") }
            } finally {
                mutableState.update { it.copy(moderationLoading = false) }
            }
        }
    }

    fun admitGuestSeat(roomId: String, member: JSONObject) {
        val participantId = member.optString("id")
        if (roomId.isBlank() || participantId.isBlank() || mutableState.value.moderationBusyUserId.isNotBlank()) return
        mutableState.update { it.copy(moderationBusyUserId = member.optString("user_id"), moderationError = "") }
        viewModelScope.launch {
            try {
                NativeLiveRepository.admitGuest(roomId, participantId, session)
                val result = NativeLiveRepository.listMembers(roomId, session)
                val rows = result.optJSONArray("members")
                val updated = if (rows == null) emptyList() else
                    (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                mutableState.update {
                    it.copy(
                        members = updated,
                        actorRole = result.optString("actor_role", it.actorRole),
                        moderationError = ""
                    )
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(moderationError = error.message ?: "Guest seat assign नहीं हो सकी।")
                }
            } finally {
                mutableState.update { it.copy(moderationBusyUserId = "") }
            }
        }
    }

    fun setMemberCamera(roomId: String, member: JSONObject) {
        updateMember(roomId, member) {
            NativeLiveRepository.setMemberPermissions(
                roomId = roomId,
                targetUserId = member.optString("user_id"),
                cameraAllowed = !member.optBoolean("camera_allowed"),
                session = session
            )
        }
    }

    fun setMemberMicrophone(roomId: String, member: JSONObject) {
        updateMember(roomId, member) {
            NativeLiveRepository.setMemberPermissions(
                roomId = roomId,
                targetUserId = member.optString("user_id"),
                microphoneAllowed = !member.optBoolean("microphone_allowed"),
                session = session
            )
        }
    }

    fun toggleMemberModerator(roomId: String, member: JSONObject) {
        updateMember(roomId, member) {
            NativeLiveRepository.setModerator(
                roomId = roomId,
                targetUserId = member.optString("user_id"),
                moderator = member.optString("role") != "moderator",
                session = session
            )
        }
    }

    fun removeMember(roomId: String, member: JSONObject) {
        updateMember(roomId, member) {
            NativeLiveRepository.removeMember(roomId, member.optString("user_id"), session)
        }
    }

    private fun updateMember(roomId: String, member: JSONObject, action: suspend () -> JSONObject) {
        val userId = member.optString("user_id")
        if (roomId.isBlank() || userId.isBlank() || mutableState.value.moderationBusyUserId.isNotBlank()) return
        mutableState.update { it.copy(moderationBusyUserId = userId, moderationError = "") }
        viewModelScope.launch {
            try {
                action()
                val result = NativeLiveRepository.listMembers(roomId, session)
                val rows = result.optJSONArray("members")
                val updated = if (rows == null) emptyList() else
                    (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                mutableState.update {
                    it.copy(
                        members = updated,
                        actorRole = result.optString("actor_role", it.actorRole),
                        moderationError = ""
                    )
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(moderationError = error.message ?: "LIVE moderation action पूरी नहीं हुई।") }
            } finally {
                mutableState.update { it.copy(moderationBusyUserId = "") }
            }
        }
    }

    fun setCreatorQuery(value: String) {
        mutableState.update { it.copy(creatorQuery = value.take(80), invitationError = "") }
    }

    fun searchCreators() {
        val query = mutableState.value.creatorQuery.trim()
        if (query.length < 2 || mutableState.value.creatorSearching) return
        mutableState.update { it.copy(creatorSearching = true, invitationError = "") }
        viewModelScope.launch {
            try {
                val creators = NativeLiveRepository.searchCreators(query, session.userId, session)
                mutableState.update { it.copy(creatorSearchResults = creators, invitationError = "") }
            } catch (error: Exception) {
                mutableState.update { it.copy(creatorSearchResults = emptyList(), invitationError = error.message ?: "Creator खोज नहीं हो सकी।") }
            } finally {
                mutableState.update { it.copy(creatorSearching = false) }
            }
        }
    }

    fun sendCreatorInvite(roomId: String, creatorId: String) {
        if (roomId.isBlank() || creatorId.isBlank() || mutableState.value.inviteSendingUserId.isNotBlank()) return
        mutableState.update { it.copy(inviteSendingUserId = creatorId, invitationError = "", invitationStatusMessage = "") }
        viewModelScope.launch {
            try {
                val result = NativeLiveRepository.createInvite(roomId, creatorId, session)
                val alreadyPending = result.optBoolean("already_pending")
                mutableState.update {
                    it.copy(invitationStatusMessage = if (alreadyPending) "इस creator को invitation पहले से भेजी गई है।" else "LIVE invitation भेज दी गई।")
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(invitationError = error.message ?: "LIVE invitation नहीं भेजी गई।") }
            } finally {
                mutableState.update { it.copy(inviteSendingUserId = "") }
            }
        }
    }

    fun refreshInvitations() {
        if (mutableState.value.invitationsLoading) return
        mutableState.update { it.copy(invitationsLoading = true, invitationError = "") }
        viewModelScope.launch {
            try {
                val rows = NativeLiveRepository.listInvitations(session)
                mutableState.update { it.copy(invitations = rows, invitationError = "") }
            } catch (error: Exception) {
                mutableState.update { it.copy(invitationError = error.message ?: "LIVE invitations लोड नहीं हुईं।") }
            } finally {
                mutableState.update { it.copy(invitationsLoading = false) }
            }
        }
    }

    fun respondToCreatorInvite(inviteId: String, roomId: String, accept: Boolean) {
        if (inviteId.isBlank() || mutableState.value.invitationBusyId.isNotBlank()) return
        if (accept && roomId.isBlank()) {
            mutableState.update { it.copy(invitationError = "Invitation में LIVE room ID नहीं मिला।") }
            return
        }
        mutableState.update { it.copy(invitationBusyId = inviteId, invitationError = "", invitationStatusMessage = "") }
        viewModelScope.launch {
            try {
                NativeLiveRepository.respondToInvitation(inviteId, accept, session)
                if (accept) {
                    mutableState.update {
                        it.copy(
                            guestRoomId = roomId,
                            guestSeatStatus = "requested",
                            guestSeatNumber = 0,
                            guestCameraAllowed = false,
                            guestMicrophoneAllowed = false,
                            guestPublisherError = ""
                        )
                    }
                    refreshGuestSeat(roomId)
                }
                refreshInvitations()
                mutableState.update {
                    it.copy(
                        invitationStatusMessage = if (accept)
                            "Invitation स्वीकार हुई। Host seat approve करेगा; camera permission मिलने पर आप जुड़ सकते हैं।"
                        else "Invitation अस्वीकार कर दी गई।"
                    )
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(invitationError = error.message ?: "Invitation response सेव नहीं हुआ।") }
            } finally {
                mutableState.update { it.copy(invitationBusyId = "") }
            }
        }
    }

    fun refreshGuestSeat(roomId: String) {
        if (roomId.isBlank() || guestSeatRefreshBusy) return
        guestSeatRefreshBusy = true
        viewModelScope.launch {
            try {
                val participants = NativeLiveRepository.listParticipants(roomId, session)
                val own = participants.firstOrNull { it.optString("user_id") == session.userId }
                if (own != null) {
                    val status = own.optString("status")
                    val cameraAllowed = own.optBoolean("camera_allowed")
                    val microphoneAllowed = own.optBoolean("microphone_allowed")
                    mutableState.update {
                        it.copy(
                            guestSeatStatus = status,
                            guestSeatNumber = own.optInt("seat_number"),
                            guestCameraAllowed = cameraAllowed,
                            guestMicrophoneAllowed = microphoneAllowed
                        )
                    }
                    if (status != "admitted" || !cameraAllowed) {
                        stopGuestPublisherInternal()
                    }
                    if (status == "removed" || status == "left") {
                        mutableState.update { it.copy(guestPublisherError = "Host ने guest seat बंद कर दी है।") }
                    }
                }
            } catch (error: Exception) {
                val message = error.message ?: "Guest seat status refresh नहीं हुआ।"
                if (message.contains("Active LIVE room not found", ignoreCase = true)) {
                    stopGuestPublisherInternal()
                    mutableState.update {
                        it.copy(guestSeatStatus = "ended", guestPublisherError = "Host की LIVE समाप्त हो गई है।")
                    }
                } else {
                    mutableState.update { it.copy(guestPublisherError = message) }
                }
            } finally {
                guestSeatRefreshBusy = false
            }
        }
    }

    fun startGuestPublisher(context: Context, roomId: String, microphoneGranted: Boolean) {
        val current = mutableState.value
        if (roomId.isBlank() || current.guestSeatStatus != "admitted" || !current.guestCameraAllowed ||
            current.guestPublisherStarting || guestPublisher != null
        ) return
        mutableState.update { it.copy(guestPublisherStarting = true, guestPublisherError = "") }
        viewModelScope.launch {
            try {
                val publisher = NativeSfuPublisher.start(
                    context.applicationContext,
                    roomId,
                    session,
                    microphoneGranted && mutableState.value.guestMicrophoneAllowed
                )
                guestPublisher = publisher
                val micOn = microphoneGranted && mutableState.value.guestMicrophoneAllowed
                mutableState.update {
                    it.copy(
                        guestPublisherReady = true,
                        guestPublisherCameraEnabled = true,
                        guestPublisherMicEnabled = micOn,
                        guestPublisherMicAvailable = publisher.hasMicrophoneTrack,
                        guestPublisherError = "आपका camera LIVE seat पर publish हो रहा है।"
                    )
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        guestPublisherReady = false,
                        guestPublisherMicEnabled = false,
                        guestPublisherError = error.message ?: "Guest camera publish नहीं हो पाया।"
                    )
                }
            } finally {
                mutableState.update { it.copy(guestPublisherStarting = false) }
            }
        }
    }

    fun attachGuestPreview(renderer: org.webrtc.SurfaceViewRenderer) {
        guestPublisher?.attachPreview(renderer)
    }

    fun detachGuestPreview(renderer: org.webrtc.SurfaceViewRenderer) {
        guestPublisher?.detachPreview(renderer)
    }

    fun setGuestCameraEnabled(enabled: Boolean) {
        val current = mutableState.value
        val roomId = current.guestRoomId
        if (roomId.isBlank() || guestPublisher == null || current.guestMediaUpdating ||
            current.guestPublisherCameraEnabled == enabled
        ) return
        mutableState.update { it.copy(guestMediaUpdating = true, guestPublisherError = "") }
        viewModelScope.launch {
            try {
                NativeLiveRepository.updateGuestMediaState(
                    roomId = roomId,
                    cameraEnabled = enabled,
                    session = session
                )
                guestPublisher?.setCameraEnabled(enabled)
                mutableState.update {
                    it.copy(guestPublisherCameraEnabled = enabled, guestPublisherError = "")
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(guestPublisherError = error.message ?: "Camera state सेव नहीं हुई।")
                }
            } finally {
                mutableState.update { it.copy(guestMediaUpdating = false) }
            }
        }
    }

    fun setGuestMicrophoneEnabled(enabled: Boolean) {
        val current = mutableState.value
        val roomId = current.guestRoomId
        if (enabled && !current.guestMicrophoneAllowed) {
            mutableState.update { it.copy(guestPublisherError = "Host ने microphone permission नहीं दी है।") }
            return
        }
        if (enabled && !current.guestPublisherMicAvailable) {
            mutableState.update { it.copy(guestPublisherError = "Android microphone permission दें और camera publisher दोबारा शुरू करें।") }
            return
        }
        if (roomId.isBlank() || guestPublisher == null || current.guestMediaUpdating ||
            current.guestPublisherMicEnabled == enabled
        ) return
        mutableState.update { it.copy(guestMediaUpdating = true, guestPublisherError = "") }
        viewModelScope.launch {
            try {
                NativeLiveRepository.updateGuestMediaState(
                    roomId = roomId,
                    microphoneEnabled = enabled,
                    session = session
                )
                guestPublisher?.setMicrophoneEnabled(enabled)
                mutableState.update {
                    it.copy(guestPublisherMicEnabled = enabled, guestPublisherError = "")
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(guestPublisherError = error.message ?: "Microphone state सेव नहीं हुई।")
                }
            } finally {
                mutableState.update { it.copy(guestMediaUpdating = false) }
            }
        }
    }

    private suspend fun stopGuestPublisherInternal() {
        val publisher = guestPublisher ?: return
        guestPublisher = null
        runCatching { publisher.stop() }
        mutableState.update {
            it.copy(
                guestPublisherReady = false,
                guestPublisherCameraEnabled = false,
                guestPublisherMicEnabled = false,
                guestPublisherMicAvailable = false,
                guestMediaUpdating = false,
                guestPublisherStarting = false
            )
        }
    }

    fun stopGuestPublisher() {
        if (guestPublisher == null) return
        viewModelScope.launch { stopGuestPublisherInternal() }
    }

    fun leaveGuestSeat(roomId: String) {
        if (roomId.isBlank()) return
        viewModelScope.launch {
            try {
                stopGuestPublisherInternal()
                if (mutableState.value.guestSeatStatus == "admitted") {
                    NativeLiveRepository.leaveSeat(roomId, session)
                }
                mutableState.update {
                    it.copy(
                        guestRoomId = "",
                        guestSeatStatus = "",
                        guestSeatNumber = 0,
                        guestCameraAllowed = false,
                        guestMicrophoneAllowed = false,
                        guestPublisherError = "आप LIVE guest seat से निकल गए।"
                    )
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(guestPublisherError = error.message ?: "Guest seat छोड़ना पूरा नहीं हुआ।") }
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
                pkBusy = false,
                members = emptyList(),
                actorRole = "viewer",
                moderationLoading = false,
                moderationBusyUserId = "",
                moderationError = "",
                creatorQuery = "",
                creatorSearchResults = emptyList(),
                creatorSearching = false,
                inviteSendingUserId = "",
                invitationError = "",
                invitations = emptyList(),
                invitationsLoading = false,
                invitationBusyId = "",
                invitationStatusMessage = "",
                guestRoomId = "",
                guestSeatStatus = "",
                guestSeatNumber = 0,
                guestCameraAllowed = false,
                guestMicrophoneAllowed = false,
                guestPublisherReady = false,
                guestPublisherStarting = false,
                guestPublisherCameraEnabled = false,
                guestPublisherMicEnabled = false,
                guestPublisherError = ""
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
