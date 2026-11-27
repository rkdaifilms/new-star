package com.newstar.live

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.IOException
import java.io.InputStream

data class NewStarSession(val accessToken: String, val refreshToken: String, val userId: String, val email: String)
data class NewStarVideo(val id: String, val userId: String, val username: String, val caption: String, val videoUrl: String, val likes: Int, val views: Int)
data class NewStarComment(val username: String, val comment: String)
data class NewStarCreator(val userId: String, val username: String, val fullName: String, val avatarUrl: String, val bio: String, val about: String, val isPrivate: Boolean, val followerCount: Int)

class NewStarApi {
    companion object {
        const val BASE_URL = "https://uzsusgoetrkujmofxcmb.supabase.co"
        const val ANON_KEY = "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr"
    }

    private fun request(path: String, method: String = "GET", token: String? = null, body: JSONObject? = null): String {
        val c = URL(BASE_URL + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("apikey", ANON_KEY)
            c.setRequestProperty("Accept", "application/json")
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val message = runCatching {
                    val error = JSONObject(text)
                    error.optString("error_description")
                        .ifBlank { error.optString("msg") }
                        .ifBlank { error.optString("message") }
                        .ifBlank { error.optString("error") }
                }.getOrNull()
                throw IllegalStateException(message?.ifBlank { null } ?: "Server request failed (" + code + ")")
            }
            return text
        } finally { c.disconnect() }
    }

    private fun parseSession(raw: String): NewStarSession {
        val j = JSONObject(raw)
        val u = j.optJSONObject("user") ?: throw IllegalStateException("Account details were not returned.")
        val a = j.optString("access_token")
        val r = j.optString("refresh_token")
        val id = u.optString("id")
        if (a.isBlank() || r.isBlank() || id.isBlank()) throw IllegalStateException("Verify your email, then sign in.")
        return NewStarSession(a, r, id, u.optString("email"))
    }

    fun signIn(email: String, password: String) = parseSession(request("/auth/v1/token?grant_type=password", "POST", body = JSONObject().put("email", email.trim()).put("password", password)))
    fun signUp(email: String, password: String): NewStarSession? {
        val raw = request("/auth/v1/signup", "POST", body = JSONObject().put("email", email.trim()).put("password", password))
        return if (JSONObject(raw).has("access_token")) parseSession(raw) else null
    }

    fun signInWithGoogleIdToken(idToken: String): NewStarSession {
        require(idToken.isNotBlank()) { "Google did not return an ID token." }
        val body = JSONObject()
            .put("provider", "google")
            .put("id_token", idToken)
        return parseSession(request("/auth/v1/token?grant_type=id_token", "POST", body = body))
    }

    fun loadFeed(token: String?): List<NewStarVideo> = loadFeedForUsers(token, null)

    fun loadFollowingFeed(token: String, userId: String): List<NewStarVideo> {
        val encodedUser = URLEncoder.encode(userId, "UTF-8")
        val follows = JSONArray(request("/rest/v1/followers?select=following_id&follower_id=eq.$encodedUser", token = token))
        val ids = ArrayList<String>()
        for (i in 0 until follows.length()) {
            val id = follows.optJSONObject(i)?.optString("following_id").orEmpty()
            if (id.isNotBlank()) ids.add(id)
        }
        if (ids.isEmpty()) return emptyList()
        return loadFeedForUsers(token, ids)
    }

    private fun loadFeedForUsers(token: String?, creatorIds: List<String>?): List<NewStarVideo> {
        val basePath = "/rest/v1/videos?select=id,user_id,username,caption,likes_count,views_count,processing_status,visibility,audience_type,created_at&or=(processing_status.eq.published,processing_status.is.null)&or=(visibility.eq.public,visibility.is.null)&or=(audience_type.eq.all,audience_type.is.null)&order=created_at.desc&limit=30"
        val path = if (creatorIds == null) basePath else basePath.substringBefore("&order=") +
            "&user_id=in." + URLEncoder.encode("(" + creatorIds.joinToString(",") + ")", "UTF-8") +
            "&order=created_at.desc&limit=30"
        val rows = JSONArray(request(path, token = token))
        val ids = JSONArray()
        val base = ArrayList<JSONObject>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id")
            if (id.isNotBlank()) { base.add(row); ids.put(id) }
        }
        if (base.isEmpty()) return emptyList()
        val c = URL(BASE_URL + "/functions/v1/video-url").openConnection() as HttpURLConnection
        val urls = try {
            c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 25000; c.doOutput = true
            c.setRequestProperty("apikey", ANON_KEY)
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("video_ids", ids).toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("Secure video access failed (" + code + ").")
            val payload = JSONObject(raw)
            if (!payload.optBoolean("ok")) throw IllegalStateException("Secure video URLs were not authorized.")
            payload.optJSONObject("urls") ?: JSONObject()
        } finally { c.disconnect() }
        return base.mapNotNull { row ->
            val id = row.optString("id"); val videoUrl = urls.optString(id)
            if (videoUrl.isBlank()) null else NewStarVideo(id, row.optString("user_id"), row.optString("username").ifBlank { "New Star Creator" }, row.optString("caption"), videoUrl, row.optInt("likes_count"), row.optInt("views_count"))
        }
    }



    fun uploadVideo(
        context: Context,
        token: String,
        userId: String,
        uri: Uri,
        title: String,
        onProgress: (Int) -> Unit
    ): String {
        val resolver = context.contentResolver
        var displayName = "new-star-video"
        var fileSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex)?.take(180) ?: displayName
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) fileSize = cursor.getLong(sizeIndex)
            }
        }
        if (fileSize <= 0L || fileSize > 5L * 1024L * 1024L * 1024L) {
            throw IllegalStateException("Video must be larger than 0 bytes and no more than 5 GiB.")
        }
        val retriever = MediaMetadataRetriever()
        val durationMs: Long
        val width: Int
        val height: Int
        try {
            retriever.setDataSource(context, uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IllegalStateException("Could not read video duration.")
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        } finally { retriever.release() }
        val durationSeconds = ((durationMs + 999L) / 1000L).toInt()
        if (durationSeconds !in 1..10800) throw IllegalStateException("Video duration must be between 1 second and 3 hours.")
        val mime = resolver.getType(uri) ?: "video/mp4"
        fun b64(value: String) = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val metadata = listOf(
            "maxDurationSeconds " + b64(durationSeconds.toString()),
            "name " + b64(displayName),
            "filetype " + b64(mime)
        ).joinToString(",")
        val create = URL(BASE_URL + "/functions/v1/cloudflare-tus-proxy").openConnection() as HttpURLConnection
        val uploadUrl: URL
        val streamId: String
        try {
            create.requestMethod = "POST"
            create.connectTimeout = 20000
            create.readTimeout = 30000
            create.setRequestProperty("apikey", ANON_KEY)
            create.setRequestProperty("Authorization", "Bearer " + token)
            create.setRequestProperty("Origin", "https://new-star-delta.vercel.app")
            create.setRequestProperty("Tus-Resumable", "1.0.0")
            create.setRequestProperty("Upload-Length", fileSize.toString())
            create.setRequestProperty("Upload-Metadata", metadata)
            val code = create.responseCode
            if (code !in 200..299) {
                val detail = (if (code >= 400) create.errorStream else create.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = runCatching { JSONObject(detail).optString("error") }.getOrNull()
                throw IllegalStateException(message?.ifBlank { null } ?: "Could not start video upload ($code).")
            }
            val location = create.getHeaderField("Location") ?: throw IllegalStateException("Upload service did not return a TUS upload URL.")
            uploadUrl = URL(create.url, location)
            streamId = create.getHeaderField("stream-media-id") ?: throw IllegalStateException("Upload service did not return a Stream video ID.")
        } finally { create.disconnect() }

        var offset = 0L
        val chunkSize = 8 * 1024 * 1024
        onProgress(0)
        while (offset < fileSize) {
            val expected = minOf(chunkSize.toLong(), fileSize - offset).toInt()
            val bytes = ByteArray(expected)
            resolver.openInputStream(uri)?.use { input ->
                var skipped = 0L
                while (skipped < offset) {
                    val step = input.skip(offset - skipped)
                    if (step <= 0L) {
                        if (input.read() == -1) throw IOException("Could not resume reading the selected video.")
                        skipped++
                    } else skipped += step
                }
                var read = 0
                while (read < expected) {
                    val count = input.read(bytes, read, expected - read)
                    if (count < 0) throw IOException("Selected video ended before the expected file size.")
                    read += count
                }
            } ?: throw IllegalStateException("Could not open the selected video.")

            val patch = uploadUrl.openConnection() as HttpURLConnection
            try {
                patch.requestMethod = "PATCH"
                patch.connectTimeout = 30000
                patch.readTimeout = 120000
                patch.doOutput = true
                patch.setFixedLengthStreamingMode(expected)
                patch.setRequestProperty("Tus-Resumable", "1.0.0")
                patch.setRequestProperty("Upload-Offset", offset.toString())
                patch.setRequestProperty("Content-Type", "application/offset+octet-stream")
                patch.outputStream.use { it.write(bytes) }
                val code = patch.responseCode
                if (code !in 200..299) {
                    val detail = (if (code >= 400) patch.errorStream else patch.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IllegalStateException("Video upload stopped at " + (offset * 100 / fileSize) + "% (HTTP " + code + "). " + detail.take(240))
                }
                val newOffset = patch.getHeaderField("Upload-Offset")?.toLongOrNull() ?: (offset + expected)
                if (newOffset != offset + expected) throw IOException("Upload server returned an unexpected byte offset.")
                offset = newOffset
            } finally { patch.disconnect() }
            onProgress(((offset * 100L) / fileSize).toInt().coerceIn(0, 99))
        }

        val safeTitle = title.trim().ifBlank { displayName.substringBeforeLast('.', displayName) }.take(150)
        val caption = safeTitle
        val videoType = if (height > width) "short" else "long"
        val payload = JSONObject()
            .put("user_id", userId)
            .put("video_url", "cloudflare://" + streamId)
            .put("video_provider", "cloudflare")
            .put("provider_video_id", streamId)
            .put("provider_status", "processing")
            .put("processing_status", "processing")
            .put("caption", caption)
            .put("category", "Entertainment & Comedy")
            .put("username", "New Star Creator")
            .put("likes_count", 0)
            .put("views_count", 0)
            .put("audience_type", "all")
            .put("duration_seconds", durationSeconds)
            .put("width", width)
            .put("height", height)
            .put("video_type", videoType)
            .put("visibility", "public")
            .put("media_kind", "video")
            .put("aspect_ratio_setting", "original")
            .put("quality_preference", "original")
            .put("source_quality", width.toString() + "x" + height)
            .put("frame_fit", "contain")
        request("/rest/v1/videos", "POST", token, payload)
        onProgress(100)
        return streamId
    }

    fun toggleLike(token: String, userId: String, videoId: String): Boolean {
        val video = URLEncoder.encode(videoId, "UTF-8")
        val user = URLEncoder.encode(userId, "UTF-8")
        val existing = JSONArray(request("/rest/v1/likes?select=id&video_id=eq.$video&user_id=eq.$user&limit=1", token = token))
        if (existing.length() > 0) {
            val id = URLEncoder.encode(existing.getJSONObject(0).getString("id"), "UTF-8")
            request("/rest/v1/likes?id=eq.$id", "DELETE", token)
            return false
        }
        request("/rest/v1/likes", "POST", token, JSONObject().put("user_id", userId).put("video_id", videoId))
        return true
    }

    fun loadComments(token: String, videoId: String): List<NewStarComment> {
        val video = URLEncoder.encode(videoId, "UTF-8")
        val rows = JSONArray(request("/rest/v1/video_comments?select=username,comment&video_id=eq.$video&order=created_at.desc&limit=40", token = token))
        val result = ArrayList<NewStarComment>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            result.add(NewStarComment(row.optString("username").ifBlank { "New Star user" }, row.optString("comment")))
        }
        return result
    }

    fun addComment(token: String, userId: String, username: String, videoId: String, comment: String) {
        val clean = comment.trim()
        require(clean.isNotEmpty() && clean.length <= 500) { "Comment must be between 1 and 500 characters." }
        request("/rest/v1/video_comments", "POST", token,
            JSONObject().put("user_id", userId).put("username", username).put("video_id", videoId).put("comment", clean))
    }

    fun searchCreators(token: String?, term: String): List<NewStarCreator> {
        val safeTerm = term.trim().replace("*", " ").replace(",", " ").take(60)
        val q = URLEncoder.encode("*" + safeTerm + "*", "UTF-8")
        val raw = request("/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url,bio,about,is_private&or=(username.ilike." + q + ",full_name.ilike." + q + ")&order=username.asc&limit=30", token = token)
        val rows = JSONArray(raw)
        val found = ArrayList<NewStarCreator>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("user_id")
            if (id.isBlank()) continue
            found.add(NewStarCreator(id, row.optString("username"), row.optString("full_name"), row.optString("avatar_url"), row.optString("bio"), row.optString("about"), row.optBoolean("is_private"), 0))
        }
        return found
    }

    fun loadCreatorProfile(token: String?, userId: String): NewStarCreator? {
        val id = URLEncoder.encode(userId, "UTF-8")
        val rows = JSONArray(request("/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url,bio,about,is_private&user_id=eq.$id&limit=1", token = token))
        if (rows.length() == 0) return null
        val row = rows.getJSONObject(0)
        val followers = JSONArray(request("/rest/v1/followers?select=id&following_id=eq.$id&limit=1000", token = token))
        return NewStarCreator(row.optString("user_id"), row.optString("username"), row.optString("full_name"), row.optString("avatar_url"), row.optString("bio"), row.optString("about"), row.optBoolean("is_private"), followers.length())
    }

    fun isFollowing(token: String, followerId: String, targetId: String): Boolean {
        val follower = URLEncoder.encode(followerId, "UTF-8")
        val target = URLEncoder.encode(targetId, "UTF-8")
        val rows = JSONArray(request("/rest/v1/followers?select=id&follower_id=eq.$follower&following_id=eq.$target&limit=1", token = token))
        return rows.length() > 0
    }

    fun toggleFollow(token: String, followerId: String, targetId: String): Boolean {
        require(followerId != targetId) { "You cannot follow your own account." }
        val follower = URLEncoder.encode(followerId, "UTF-8")
        val target = URLEncoder.encode(targetId, "UTF-8")
        val existing = JSONArray(request("/rest/v1/followers?select=id&follower_id=eq.$follower&following_id=eq.$target&limit=1", token = token))
        if (existing.length() > 0) {
            request("/rest/v1/followers?follower_id=eq.$follower&following_id=eq.$target", "DELETE", token)
            return false
        }
        request("/rest/v1/followers", "POST", token,
            JSONObject().put("follower_id", followerId).put("following_id", targetId))
        return true
    }

    fun loadCreatorVideos(token: String?, creatorId: String): List<NewStarVideo> =
        loadFeedForUsers(token, listOf(creatorId))
}
