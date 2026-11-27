package com.newstar.live

import android.content.Context
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

private const val SUPABASE_URL = "https://uzsusgoetrkujmofxcmb.supabase.co"
private const val SUPABASE_KEY = "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr"

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

    suspend fun post(path: String, body: JSONObject, session: NativeSession): String =
        withContext(Dispatchers.IO) { request("POST", path, body, session.accessToken, mapOf("Prefer" to "return=representation")) }

    suspend fun patch(path: String, body: JSONObject, session: NativeSession): String =
        withContext(Dispatchers.IO) { request("PATCH", path, body, session.accessToken, mapOf("Prefer" to "return=representation")) }

    suspend fun deleteSession(context: Context, session: NativeSession) = withContext(Dispatchers.IO) {
        try { request("POST", "/auth/v1/logout", token = session.accessToken) } finally {
            NativeSessionStore.clear(context)
        }
    }
}
