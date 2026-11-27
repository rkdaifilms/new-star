package com.newstar.live

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

internal data class NativeLiveCreator(
    val userId: String,
    val username: String,
    val fullName: String,
    val avatarUrl: String
) {
    val displayName: String
        get() = fullName.ifBlank { username.ifBlank { "New Star Creator" } }
}

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
}
