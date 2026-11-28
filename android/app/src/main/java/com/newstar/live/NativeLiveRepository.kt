package com.newstar.live

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class NativeLiveCreator(
    val userId: String,
    val username: String,
    val fullName: String,
    val avatarUrl: String
) {
    val displayName: String
        get() = fullName.ifBlank { username.ifBlank { "New Star Creator" } }
}

internal data class NativeLiveMember(
    val participantId: String,
    val userId: String,
    val displayName: String,
    val role: String,
    val status: String,
    val seatNumber: Int?,
    val cameraAllowed: Boolean,
    val microphoneAllowed: Boolean,
    val cameraEnabled: Boolean,
    val microphoneEnabled: Boolean
)

internal object NativeLiveRepository {
    suspend fun searchCreators(query: String, currentUserId: String, session: NativeSession): List<NativeLiveCreator> {
        val term = query.trim()
        require(term.length >= 2) { "Creator खोजने के लिए कम से कम 2 अक्षर लिखें।" }
        val filter = Uri.encode("(username.ilike.*$term*,full_name.ilike.*$term*)")
        val rows: JSONArray = NativeSupabase.get(
            "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url&or=$filter&order=username.asc&limit=20",
            session
        )
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("user_id")
            if (id.isBlank() || id == currentUserId) return@mapNotNull null
            NativeLiveCreator(
                userId = id,
                username = row.optString("username"),
                fullName = row.optString("full_name"),
                avatarUrl = row.optString("avatar_url").takeIf { it.startsWith("https://") }.orEmpty()
            )
        }
    }

    suspend fun listInvitations(session: NativeSession): List<JSONObject> {
        val result = NativeSupabase.invokeFunction(
            "cloudflare-live",
            JSONObject().put("action", "live-invites-list"),
            session
        )
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(result.optString("error", "LIVE invitations लोड नहीं हुए।"))
        }
        val rows = result.optJSONArray("invitations") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }

    suspend fun respondToInvitation(
        inviteId: String,
        accept: Boolean,
        session: NativeSession
    ): JSONObject {
        require(inviteId.isNotBlank()) { "LIVE invitation ID आवश्यक है।" }
        val result = NativeSupabase.invokeFunction(
            "cloudflare-live",
            JSONObject().put("action", "live-invite-respond")
                .put("invite_id", inviteId)
                .put("message", if (accept) "accept" else "decline"),
            session
        )
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(
                result.optString("error", if (accept) "LIVE invitation स्वीकार नहीं हुई।" else "LIVE invitation अस्वीकार नहीं हुई।")
            )
        }
        return result
    }

    suspend fun createInvite(roomId: String, inviteeId: String, session: NativeSession): JSONObject {
        require(roomId.isNotBlank() && inviteeId.isNotBlank())
        return NativeSupabase.invokeFunction(
            "cloudflare-live",
            JSONObject().put("action", "live-invite-create")
                .put("room_id", roomId).put("invitee_id", inviteeId),
            session
        ).also { result ->
            if (!result.optBoolean("ok")) {
                throw IllegalStateException(result.optString("error", "LIVE invitation नहीं भेजा गया।"))
            }
        }
    }

    suspend fun listParticipants(roomId: String, session: NativeSession): List<JSONObject> {
        val result = NativeSupabase.invokeFunction(
            "cloudflare-live",
            JSONObject().put("action", "live-participants-list").put("room_id", roomId),
            session
        )
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(result.optString("error", "LIVE participants लोड नहीं हुए।"))
        }
        val rows = result.optJSONArray("participants") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }

    suspend fun requestSeat(roomId: String, session: NativeSession): JSONObject =
        invokeSeatAction("live-seat-request", roomId, null, session)

    suspend fun leaveSeat(roomId: String, session: NativeSession): JSONObject =
        invokeSeatAction("live-seat-leave", roomId, null, session)

    suspend fun admitGuest(roomId: String, participantId: String, session: NativeSession): JSONObject =
        invokeSeatAction("live-seat-admit", roomId, participantId, session)

    suspend fun removeGuest(roomId: String, participantId: String, session: NativeSession): JSONObject =
        invokeSeatAction("live-seat-remove", roomId, participantId, session)

    suspend fun updateGuestMediaState(
        roomId: String,
        cameraEnabled: Boolean? = null,
        microphoneEnabled: Boolean? = null,
        session: NativeSession
    ): JSONObject {
        require(roomId.isNotBlank()) { "LIVE room ID आवश्यक है।" }
        require(cameraEnabled != null || microphoneEnabled != null) { "Media state आवश्यक है।" }
        val body = JSONObject().put("action", "live-seat-media-state").put("room_id", roomId)
        cameraEnabled?.let { body.put("camera_enabled", it) }
        microphoneEnabled?.let { body.put("microphone_enabled", it) }
        val result = NativeSupabase.invokeFunction("cloudflare-live", body, session)
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(result.optString("error", "LIVE media state सेव नहीं हुई।"))
        }
        return result
    }

    suspend fun listMembers(roomId: String, session: NativeSession): JSONObject {
        require(roomId.isNotBlank()) { "LIVE room ID आवश्यक है।" }
        val result = NativeSupabase.invokeFunction(
            "cloudflare-live",
            JSONObject().put("action", "list-members").put("room_id", roomId),
            session
        )
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(result.optString("error", "LIVE member list लोड नहीं हुई।"))
        }
        return result
    }

    suspend fun setMemberPermissions(
        roomId: String,
        targetUserId: String,
        cameraAllowed: Boolean? = null,
        microphoneAllowed: Boolean? = null,
        session: NativeSession
    ): JSONObject {
        require(roomId.isNotBlank() && targetUserId.isNotBlank())
        require(cameraAllowed != null || microphoneAllowed != null)
        val body = JSONObject().put("action", "set-member-permissions")
            .put("room_id", roomId).put("target_user_id", targetUserId)
        cameraAllowed?.let { body.put("camera_allowed", it) }
        microphoneAllowed?.let { body.put("microphone_allowed", it) }
        return invokeMemberAction(body, session)
    }

    suspend fun setModerator(
        roomId: String,
        targetUserId: String,
        moderator: Boolean,
        session: NativeSession
    ): JSONObject = invokeMemberAction(
        JSONObject().put("action", "set-moderator").put("room_id", roomId)
            .put("target_user_id", targetUserId).put("moderator", moderator),
        session
    )

    suspend fun removeMember(
        roomId: String,
        targetUserId: String,
        session: NativeSession
    ): JSONObject = invokeMemberAction(
        JSONObject().put("action", "remove-member").put("room_id", roomId)
            .put("target_user_id", targetUserId),
        session
    )

    private suspend fun invokeMemberAction(body: JSONObject, session: NativeSession): JSONObject {
        val result = NativeSupabase.invokeFunction("cloudflare-live", body, session)
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(result.optString("error", "LIVE member action पूरी नहीं हुई।"))
        }
        return result
    }


    suspend fun getSfuHealth(session: NativeSession): JSONObject {
        val result = NativeSupabase.invokeFunction(
            "cloudflare-live",
            JSONObject().put("action", "sfu-health"),
            session
        )
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(result.optString("error", "SFU configuration status नहीं मिला।"))
        }
        return result
    }

    suspend fun createSfuSession(roomId: String, role: String, session: NativeSession): String {
        require(roomId.isNotBlank() && role in setOf("publisher", "subscriber"))
        val result = invokeSfuAction(
            JSONObject().put("action", "sfu-create-session").put("room_id", roomId).put("role", role),
            session
        )
        return result.optString("session_id").also { require(it.isNotBlank()) { "Cloudflare SFU session ID नहीं मिला।" } }
    }

    suspend fun publishSfuTracks(
        roomId: String,
        sessionId: String,
        offerSdp: String,
        tracks: JSONArray,
        session: NativeSession
    ): JSONObject = invokeSfuAction(
        JSONObject().put("action", "sfu-publish").put("room_id", roomId)
            .put("session_id", sessionId)
            .put("session_description", JSONObject().put("type", "offer").put("sdp", offerSdp))
            .put("tracks", tracks),
        session
    )

    suspend fun listSfuPublications(roomId: String, session: NativeSession): JSONArray =
        invokeSfuAction(
            JSONObject().put("action", "sfu-list-pubs").put("room_id", roomId),
            session
        ).optJSONArray("publications") ?: JSONArray()

    suspend fun getCreatorProfiles(userIds: Collection<String>, session: NativeSession): Map<String, JSONObject> {
        val ids = userIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val filter = Uri.encode("(${ids.joinToString(",")})")
        val rows = NativeSupabase.get(
            "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url&user_id=in.$filter",
            session
        )
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .associateBy { it.optString("user_id") }
    }

    suspend fun subscribeSfuTracks(
        roomId: String,
        sessionId: String,
        tracks: JSONArray,
        session: NativeSession
    ): JSONObject = invokeSfuAction(
        JSONObject().put("action", "sfu-subscribe").put("room_id", roomId)
            .put("session_id", sessionId).put("tracks", tracks),
        session
    )

    suspend fun renegotiateSfuSession(
        roomId: String,
        sessionId: String,
        answerSdp: String,
        session: NativeSession
    ): JSONObject = invokeSfuAction(
        JSONObject().put("action", "sfu-renegotiate").put("room_id", roomId)
            .put("session_id", sessionId)
            .put("session_description", JSONObject().put("type", "answer").put("sdp", answerSdp)),
        session
    )

    suspend fun abandonSfuSession(roomId: String, sessionId: String, session: NativeSession): JSONObject =
        invokeSfuAction(
            JSONObject().put("action", "sfu-abandon").put("room_id", roomId).put("session_id", sessionId),
            session
        )

    suspend fun unsubscribeSfuTracks(
        roomId: String,
        sessionId: String,
        mids: JSONArray,
        session: NativeSession
    ): JSONObject = invokeSfuAction(
        JSONObject().put("action", "sfu-unsubscribe").put("room_id", roomId)
            .put("session_id", sessionId).put("tracks", mids),
        session
    )

    suspend fun closeSfuSession(
        roomId: String,
        sessionId: String,
        mids: JSONArray,
        session: NativeSession
    ): JSONObject = invokeSfuAction(
        JSONObject().put("action", "sfu-close").put("room_id", roomId)
            .put("session_id", sessionId).put("tracks", mids),
        session
    )

    private suspend fun invokeSfuAction(body: JSONObject, session: NativeSession): JSONObject {
        val result = NativeSupabase.invokeFunction("cloudflare-live", body, session)
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(result.optString("error", "Cloudflare SFU operation पूरी नहीं हुई।"))
        }
        return result
    }

    suspend fun joinPkQueue(roomId: String, session: NativeSession): JSONObject =
        invokePkAction(JSONObject().put("action", "pk-join-queue").put("room_id", roomId), session)

    suspend fun getPkStatus(roomId: String, session: NativeSession): JSONObject =
        invokePkAction(JSONObject().put("action", "pk-status").put("room_id", roomId), session)

    suspend fun leavePkQueue(session: NativeSession): JSONObject =
        invokePkAction(JSONObject().put("action", "pk-leave-queue"), session)

    suspend fun finishPkMatch(matchId: String, session: NativeSession): JSONObject {
        require(matchId.isNotBlank()) { "Battle match ID आवश्यक है।" }
        return invokePkAction(JSONObject().put("action", "pk-finish").put("match_id", matchId), session)
    }

    private suspend fun invokePkAction(body: JSONObject, session: NativeSession): JSONObject {
        val result = NativeSupabase.invokeFunction("cloudflare-live", body, session)
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(result.optString("error", "LIVE PK action पूरी नहीं हुई।"))
        }
        return result
    }

    suspend fun listLiveChat(roomId: String, session: NativeSession): List<JSONObject> {
        require(roomId.isNotBlank()) { "LIVE room ID आवश्यक है।" }
        val result = NativeSupabase.invokeFunction("cloudflare-live",
            JSONObject().put("action", "live-chat-list").put("room_id", roomId), session)
        if (!result.optBoolean("ok")) throw IllegalStateException(result.optString("error", "LIVE chat लोड नहीं हुई।"))
        val rows = result.optJSONArray("messages") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }

    suspend fun sendLiveChatMessage(roomId: String, message: String, session: NativeSession): JSONObject {
        val text = message.trim()
        require(roomId.isNotBlank()) { "LIVE room ID आवश्यक है।" }
        require(text.isNotEmpty() && text.length <= 500) { "Message 1 से 500 characters का होना चाहिए।" }
        val result = NativeSupabase.invokeFunction("cloudflare-live",
            JSONObject().put("action", "live-chat-send").put("room_id", roomId).put("message", text), session)
        if (!result.optBoolean("ok")) throw IllegalStateException(result.optString("error", "LIVE message भेजा नहीं गया।"))
        return result.optJSONObject("message") ?: throw IllegalStateException("Server ने LIVE message की पुष्टि नहीं की।")
    }
    suspend fun getReactionCounts(roomId: String, session: NativeSession): Map<String, Int> {
        require(roomId.isNotBlank()) { "LIVE room ID आवश्यक है।" }
        val result = NativeSupabase.invokeFunction(
            "cloudflare-live",
            JSONObject().put("action", "live-reaction-counts").put("room_id", roomId),
            session
        )
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(result.optString("error", "LIVE reaction totals लोड नहीं हुए।"))
        }
        val counts = result.optJSONObject("counts") ?: JSONObject()
        return listOf("like", "heart", "clap", "fire").associateWith { counts.optInt(it) }
    }

    suspend fun sendReaction(roomId: String, reaction: String, session: NativeSession): Int {
        require(roomId.isNotBlank()) { "LIVE room ID आवश्यक है।" }
        require(reaction in setOf("like", "heart", "clap", "fire")) { "यह LIVE reaction उपलब्ध नहीं है।" }
        val result = NativeSupabase.invokeFunction(
            "cloudflare-live",
            JSONObject().put("action", "live-react")
                .put("room_id", roomId).put("reaction", reaction),
            session
        )
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(result.optString("error", "LIVE reaction भेजा नहीं गया।"))
        }
        return result.optInt("total")
    }

    private suspend fun invokeSeatAction(
        action: String,
        roomId: String,
        participantId: String?,
        session: NativeSession
    ): JSONObject {
        val body = JSONObject().put("action", action).put("room_id", roomId)
        participantId?.let { body.put("participant_id", it) }
        return NativeSupabase.invokeFunction("cloudflare-live", body, session).also { result ->
            if (!result.optBoolean("ok")) {
                throw IllegalStateException(result.optString("error", "LIVE seat action पूरी नहीं हुई।"))
            }
        }
    }

    suspend fun listModerationMembers(roomId: String, session: NativeSession): Pair<String, List<NativeLiveMember>> {
        require(roomId.isNotBlank()) { "LIVE room ID आवश्यक है।" }
        val result = NativeSupabase.invokeFunction(
            "cloudflare-live",
            JSONObject().put("action", "list-members").put("room_id", roomId),
            session
        )
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(result.optString("error", "LIVE participants लोड नहीं हुए।"))
        }
        val rows = result.optJSONArray("members") ?: JSONArray()
        val members = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val userId = row.optString("user_id")
            if (userId.isBlank()) return@mapNotNull null
            NativeLiveMember(
                participantId = row.optString("id"),
                userId = userId,
                displayName = row.optString("display_name").ifBlank { "LIVE Guest" },
                role = row.optString("role").ifBlank { "guest" },
                status = row.optString("status").ifBlank { "requested" },
                seatNumber = row.optInt("seat_number").takeIf { it > 0 },
                cameraAllowed = row.optBoolean("camera_allowed", true),
                microphoneAllowed = row.optBoolean("microphone_allowed", true),
                cameraEnabled = row.optBoolean("camera_enabled"),
                microphoneEnabled = row.optBoolean("microphone_enabled")
            )
        }
        return result.optString("actor_role", "viewer") to members
    }

    suspend fun manageLiveMember(
        roomId: String,
        targetUserId: String,
        action: String,
        enabled: Boolean? = null,
        session: NativeSession
    ) {
        require(roomId.isNotBlank() && targetUserId.isNotBlank()) { "LIVE room और member आवश्यक हैं।" }
        require(action in setOf("set-moderator", "remove-member")) {
            "यह participant action उपलब्ध नहीं है।"
        }
        val body = JSONObject().put("action", action)
            .put("room_id", roomId).put("target_user_id", targetUserId)
        if (action == "set-moderator") body.put("moderator", enabled == true)
        val result = NativeSupabase.invokeFunction("cloudflare-live", body, session)
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(result.optString("error", "LIVE participant action पूरी नहीं हुई।"))
        }
    }

    suspend fun manageLiveMemberPermission(
        roomId: String,
        targetUserId: String,
        permission: String,
        allowed: Boolean,
        session: NativeSession
    ) {
        require(roomId.isNotBlank() && targetUserId.isNotBlank()) { "LIVE room और member आवश्यक हैं।" }
        require(permission == "camera_allowed" || permission == "microphone_allowed") {
            "केवल camera और microphone permission बदली जा सकती है।"
        }
        val body = JSONObject().put("action", "set-member-permissions")
            .put("room_id", roomId).put("target_user_id", targetUserId)
            .put(permission, allowed)
        val result = NativeSupabase.invokeFunction("cloudflare-live", body, session)
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(result.optString("error", "LIVE permission update नहीं हुआ।"))
        }
    }

}
