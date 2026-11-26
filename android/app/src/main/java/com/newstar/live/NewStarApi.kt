package com.newstar.live

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class NewStarSession(val accessToken: String, val refreshToken: String, val userId: String, val email: String)
data class NewStarVideo(val id: String, val userId: String, val username: String, val caption: String, val videoUrl: String, val likes: Int, val views: Int)

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
                val message = runCatching { JSONObject(text).optString("msg").ifBlank { JSONObject(text).optString("message") } }.getOrNull()
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

    fun loadFeed(token: String?): List<NewStarVideo> {
        val path = "/rest/v1/videos?select=id,user_id,username,caption,likes_count,views_count,processing_status,visibility,audience_type,created_at&or=(processing_status.eq.published,processing_status.is.null)&or=(visibility.eq.public,visibility.is.null)&or=(audience_type.eq.all,audience_type.is.null)&order=created_at.desc&limit=30"
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
            c.setRequestProperty("Authorization", "Bearer " + (token ?: ANON_KEY))
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

    fun searchCreators(token: String?, term: String): List<String> {
        val q = URLEncoder.encode("*" + term + "*", "UTF-8")
        val raw = request("/rest/v1/creator_public_profiles?select=username,full_name&or=(username.ilike." + q + ",full_name.ilike." + q + ")&limit=20", token = token)
        val rows = JSONArray(raw)
        val found = ArrayList<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            found.add("@" + row.optString("username") + if (row.optString("full_name").isBlank()) "" else " · " + row.optString("full_name"))
        }
        return found
    }
}
