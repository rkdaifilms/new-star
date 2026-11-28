package com.newstar.live

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val SUPABASE_URL = "https://uzsusgoetrkujmofxcmb.supabase.co"
internal const val SUPABASE_KEY = "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr"

data class NativeSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
    val email: String
)

object NativeSessionStore {
    private const val PREFS = "newstar_secure_session"
    private const val ENTRY = "session"
    private const val KEY_ALIAS = "newstar_session_aes_v1"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun save(context: Context, session: NativeSession) {
        val plain = JSONObject().apply {
            put("access", session.accessToken)
            put("refresh", session.refreshToken)
            put("expires", session.expiresAt)
            put("user", session.userId)
            put("email", session.email)
        }.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain)
        val packed = cipher.iv + encrypted
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ENTRY, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun load(context: Context): NativeSession? {
        return try {
            val packed = Base64.decode(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ENTRY, null),
                Base64.NO_WRAP
            )
            require(packed.size > 12)
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            NativeSession(
                json.getString("access"), json.getString("refresh"), json.getLong("expires"),
                json.getString("user"), json.optString("email")
            )
        } catch (_: Exception) { null }
    }

    fun savePendingOAuth(context: Context, state: String, verifier: String) {
        val plain = JSONObject().put("state", state).put("verifier", verifier).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val packed = cipher.iv + cipher.doFinal(plain)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pending_oauth", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun consumePendingOAuth(context: Context): Pair<String, String>? {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val encoded = prefs.getString("pending_oauth", null) ?: return null
            prefs.edit().remove("pending_oauth").apply()
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8))
            json.getString("state") to json.getString("verifier")
        } catch (_: Exception) { null }
    }

    fun savePendingReferral(context: Context, referrerId: String) {
        require(referrerId.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) {
            "Referral ID अमान्य है।"
        }
        val plain = JSONObject()
            .put("referrer", referrerId)
            .put("saved_at", System.currentTimeMillis())
            .toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val packed = cipher.iv + cipher.doFinal(plain)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pending_referral", Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    fun pendingReferral(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encoded = prefs.getString("pending_referral", null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, packed.copyOfRange(0, 12))
            )
            val json = JSONObject(String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8))
            val savedAt = json.optLong("saved_at")
            val age = System.currentTimeMillis() - savedAt
            if (savedAt <= 0L || age < 0L || age > 24L * 60L * 60L * 1000L) {
                clearPendingReferral(context)
                return null
            }
            json.optString("referrer").takeIf {
                it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            } ?: run {
                clearPendingReferral(context)
                null
            }
        } catch (_: Exception) {
            clearPendingReferral(context)
            null
        }
    }

    fun clearPendingReferral(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("pending_referral").apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ENTRY).apply()
    }
}

object NativeSupabase {
    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val connection = (URL("$SUPABASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 60000
            setRequestProperty("apikey", SUPABASE_KEY)
            setRequestProperty("Accept", "application/json")
            token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            extraHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            val message = try { JSONObject(response).optString("message").ifBlank {
                JSONObject(response).optString("error_description").ifBlank { JSONObject(response).optString("error") }
            } } catch (_: Exception) { response }
            throw IllegalStateException(message.ifBlank { "Supabase request failed ($code)" })
        }
        return response
    }


    suspend fun uploadPublicObject(
        bucket: String, path: String, bytes: ByteArray, mimeType: String, session: NativeSession
    ): String = withContext(Dispatchers.IO) {
        require(bucket.matches(Regex("[A-Za-z0-9_-]+"))) { "Storage bucket अमान्य है।" }
        require(bytes.isNotEmpty()) { "फोटो खाली है।" }
        val safePath = path.split("/").joinToString("/") { Uri.encode(it) }
        val connection = (URL("$SUPABASE_URL/storage/v1/object/$bucket/$safePath").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("apikey", SUPABASE_KEY)
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Content-Type", mimeType)
            setRequestProperty("x-upsert", "false")
        }
        connection.outputStream.use { it.write(bytes) }
        val code = connection.responseCode
        val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("Photo upload failed ($code): ${response.take(180)}")
        "$SUPABASE_URL/storage/v1/object/public/$bucket/$safePath"
    }

    suspend fun uploadPrivateObject(
        bucket: String, path: String, bytes: ByteArray, mimeType: String, session: NativeSession
    ): String = withContext(Dispatchers.IO) {
        require(bucket == "message-media") { "Private upload bucket अमान्य है।" }
        require(bytes.isNotEmpty() && bytes.size <= 20 * 1024 * 1024) { "Attachment 20 MB से छोटी होनी चाहिए।" }
        require(mimeType.startsWith("image/") || mimeType.startsWith("video/") ||
            mimeType == "application/pdf" || mimeType.startsWith("audio/")) {
            "यह file type भेजने की अनुमति नहीं है।"
        }
        val safePath = path.split("/").joinToString("/") { Uri.encode(it) }
        val connection = (URL("$SUPABASE_URL/storage/v1/object/$bucket/$safePath").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("apikey", SUPABASE_KEY)
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Content-Type", mimeType)
            setRequestProperty("x-upsert", "false")
        }
        connection.outputStream.use { it.write(bytes) }
        val code = connection.responseCode
        val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("Private media upload failed ($code): ${response.take(180)}")
        path
    }

    suspend fun createSignedObjectUrl(
        bucket: String, path: String, session: NativeSession, expiresInSeconds: Int = 3600
    ): String = withContext(Dispatchers.IO) {
        require(bucket == "message-media") { "Private media bucket अमान्य है।" }
        require(path.isNotBlank() && !path.startsWith("/") && !path.contains("..")) {
            "Attachment path अमान्य है।"
        }
        val encodedPath = path.split("/").joinToString("/") { Uri.encode(it) }
        val response = request(
            "POST",
            "/storage/v1/object/sign/$bucket/$encodedPath",
            JSONObject().put("expiresIn", expiresInSeconds.coerceIn(60, 86400)),
            session.accessToken
        )
        val signed = JSONObject(response).optString("signedURL")
        require(signed.isNotBlank()) { "Signed attachment URL नहीं मिला।" }
        if (signed.startsWith("https://")) signed else "$SUPABASE_URL/storage/v1${if (signed.startsWith("/")) signed else "/$signed"}"
    }

    suspend fun currentUser(session: NativeSession): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(request("GET", "/auth/v1/user", token = session.accessToken))
    }

    suspend fun updateUserMetadata(data: JSONObject, session: NativeSession): JSONObject =
        withContext(Dispatchers.IO) {
            JSONObject(request("PUT", "/auth/v1/user", JSONObject().put("data", data), session.accessToken))
        }

    suspend fun updateAuthUser(data: JSONObject, session: NativeSession): JSONObject =
        withContext(Dispatchers.IO) {
            JSONObject(request("PUT", "/auth/v1/user", data, session.accessToken))
        }

    suspend fun signUp(email: String, password: String, referrerId: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("email", email).put("password", password)
            referrerId?.takeIf {
                it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            }?.let { payload.put("data", JSONObject().put("referrer_id", it)) }
            JSONObject(request("POST", "/auth/v1/signup", payload))
        }

    suspend fun invokeFunction(name: String, body: JSONObject, session: NativeSession): JSONObject =
        withContext(Dispatchers.IO) {
            JSONObject(request(
                "POST", "/functions/v1/$name", body, session.accessToken
            ))
        }

    suspend fun signIn(context: Context, email: String, password: String): NativeSession =
        withContext(Dispatchers.IO) {
            val result = JSONObject(request(
                "POST", "/auth/v1/token?grant_type=password",
                JSONObject().put("email", email).put("password", password)
            ))
            val user = result.getJSONObject("user")
            val session = NativeSession(
                result.getString("access_token"), result.getString("refresh_token"),
                System.currentTimeMillis() / 1000 + result.optLong("expires_in", 3600),
                user.getString("id"), user.optString("email", email)
            )
            NativeSessionStore.save(context, session)
            session
        }

    suspend fun exchangeCode(context: Context, code: String, verifier: String): NativeSession =
        withContext(Dispatchers.IO) {
            val result = JSONObject(request(
                "POST", "/auth/v1/token?grant_type=pkce",
                JSONObject().put("auth_code", code).put("code_verifier", verifier)
            ))
            val user = result.getJSONObject("user")
            val session = NativeSession(
                result.getString("access_token"), result.getString("refresh_token"),
                System.currentTimeMillis() / 1000 + result.optLong("expires_in", 3600),
                user.getString("id"), user.optString("email")
            )
            NativeSessionStore.save(context, session)
            session
        }

    suspend fun refresh(context: Context, session: NativeSession): NativeSession =
        withContext(Dispatchers.IO) {
            val result = JSONObject(request(
                "POST", "/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", session.refreshToken)
            ))
            val user = result.optJSONObject("user")
            val updated = NativeSession(
                result.getString("access_token"), result.getString("refresh_token"),
                System.currentTimeMillis() / 1000 + result.optLong("expires_in", 3600),
                user?.optString("id")?.takeIf { it.isNotBlank() } ?: session.userId,
                user?.optString("email")?.takeIf { it.isNotBlank() } ?: session.email
            )
            NativeSessionStore.save(context, updated)
            updated
        }

    suspend fun validSession(context: Context): NativeSession? = withContext(Dispatchers.IO) {
        val saved = NativeSessionStore.load(context) ?: return@withContext null
        if (saved.expiresAt > System.currentTimeMillis() / 1000 + 90) return@withContext saved
        try { refresh(context, saved) } catch (_: Exception) {
            NativeSessionStore.clear(context)
            null
        }
    }

    suspend fun get(path: String, session: NativeSession? = null): JSONArray =
        withContext(Dispatchers.IO) { JSONArray(request("GET", path, token = session?.accessToken)) }

    suspend fun getRange(path: String, session: NativeSession, from: Int, to: Int): JSONArray =
        withContext(Dispatchers.IO) {
            require(from >= 0 && to >= from)
            JSONArray(request(
                "GET", path, token = session.accessToken,
                extraHeaders = mapOf("Range-Unit" to "items", "Range" to "$from-$to")
            ))
        }

    suspend fun post(path: String, body: JSONObject, session: NativeSession): String =
        withContext(Dispatchers.IO) { request("POST", path, body, session.accessToken, mapOf("Prefer" to "return=representation")) }

    suspend fun upsert(
        path: String,
        body: JSONObject,
        session: NativeSession,
        ignoreDuplicates: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val resolution = if (ignoreDuplicates) "ignore-duplicates" else "merge-duplicates"
        request(
            "POST",
            path,
            body,
            session.accessToken,
            mapOf("Prefer" to "resolution=$resolution,return=representation")
        )
    }

    suspend fun patch(path: String, body: JSONObject, session: NativeSession): String =
        withContext(Dispatchers.IO) { request("PATCH", path, body, session.accessToken, mapOf("Prefer" to "return=representation")) }

    suspend fun delete(path: String, session: NativeSession): String =
        withContext(Dispatchers.IO) { request("DELETE", path, token = session.accessToken) }

    suspend fun deleteReturning(path: String, session: NativeSession): String =
        withContext(Dispatchers.IO) {
            request(
                "DELETE", path, token = session.accessToken,
                extraHeaders = mapOf("Prefer" to "return=representation")
            )
        }

    suspend fun removeVideoObject(path: String, session: NativeSession) = withContext(Dispatchers.IO) {
        require(path.startsWith("${session.userId}/") && !path.split("/").any { it == ".." || it.isBlank() }) {
            "Video storage path owner से match नहीं करता।"
        }
        request(
            "DELETE",
            "/storage/v1/object/videos",
            JSONObject().put("prefixes", JSONArray().put(path)),
            session.accessToken
        )
    }

    suspend fun deleteSession(context: Context, session: NativeSession) = withContext(Dispatchers.IO) {
        try { request("POST", "/auth/v1/logout", token = session.accessToken) } finally {
            NativeSessionStore.clear(context)
        }
    }

    suspend fun deleteAllSessions(context: Context, session: NativeSession) = withContext(Dispatchers.IO) {
        try { request("POST", "/auth/v1/logout?scope=global", token = session.accessToken) } finally {
            NativeSessionStore.clear(context)
        }
    }
}
