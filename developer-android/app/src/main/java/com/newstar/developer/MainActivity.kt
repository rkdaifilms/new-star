package com.newstar.developer

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.graphics.Color
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread

private const val SUPABASE_URL = "https://uzsusgoetrkujmofxcmb.supabase.co"
private const val SUPABASE_KEY = "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr"

private data class AdminSession(val access: String, val refresh: String, val expiresAt: Long)

private object AdminSessionStore {
    private const val PREFS = "newstar_developer_secure"
    private const val ENTRY = "session"
    private const val ALIAS = "newstar_developer_aes_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return gen.generateKey()
    }

    fun save(context: Context, session: AdminSession) {
        val plain = JSONObject().put("access", session.access).put("refresh", session.refresh)
            .put("expires", session.expiresAt).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(plain)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ENTRY, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun load(context: Context): AdminSession? = try {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ENTRY, null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        val j = JSONObject(String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8))
        AdminSession(j.getString("access"), j.getString("refresh"), j.getLong("expires"))
    } catch (_: Exception) { null }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ENTRY).apply()
    }
}

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private var session: AdminSession? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 13, 20)
        window.navigationBarColor = Color.rgb(10, 13, 20)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(10, 13, 20))
        }
        status = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 13f }
        session = AdminSessionStore.load(this)
        render()
        if (session != null) loadDashboard()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float = 15f, color: Int = Color.WHITE): TextView =
        TextView(this).apply { text = value; textSize = size; setTextColor(color); setPadding(0, dp(5), 0, dp(5)) }

    private fun button(value: String, action: () -> Unit): Button =
        Button(this).apply { text = value; isAllCaps = false; setOnClickListener { action() } }

    private fun render() {
        root.removeAllViews()
        root.addView(label("NEW STAR  •  DEVELOPER", 21f))
        root.addView(label("Native Android Owner Console", 13f, Color.LTGRAY))
        root.addView(status)
        if (session == null) {
            val email = EditText(this).apply {
                hint = "Owner email"
                setSingleLine(true)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
            }
            val password = EditText(this).apply {
                hint = "Password"
                inputType = 129
                setSingleLine(true)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
            }
            root.addView(email, matchWrap())
            root.addView(password, matchWrap())
            root.addView(button("Secure owner login") {
                val e = email.text.toString().trim()
                val p = password.text.toString()
                if (!e.contains("@") || p.length < 6) {
                    status.text = "ईमेल और सही पासवर्ड दर्ज करें।"
                } else login(e, p)
            }, matchWrap())
            root.addView(label("केवल server-verified owner account को dashboard access मिलेगा।", 12f, Color.LTGRAY))
        } else {
            root.addView(button("Refresh dashboard") { loadDashboard() }, matchWrap())
            root.addView(button("Logout") {
                AdminSessionStore.clear(this)
                session = null
                render()
            }, matchWrap())
            val scroll = ScrollView(this)
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }
            scroll.addView(body)
            root.addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            dashboardBody = body
        }
        setContentView(root)
    }

    private var dashboardBody: LinearLayout? = null

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(8)
    }

    private fun login(email: String, password: String) {
        if (busy) return
        busy = true
        status.text = "Signing in securely…"
        thread {
            try {
                val json = request("POST", "/auth/v1/token?grant_type=password",
                    JSONObject().put("email", email).put("password", password))
                val now = System.currentTimeMillis() / 1000
                val s = AdminSession(json.getString("access_token"), json.getString("refresh_token"),
                    now + json.optLong("expires_in", 3600))
                runOnUiThread {
                    session = s
                    AdminSessionStore.save(this, s)
                    busy = false
                    render()
                    loadDashboard()
                }
            } catch (e: Exception) {
                runOnUiThread { busy = false; status.text = e.message ?: "Login failed" }
            }
        }
    }

    private fun authorizedSession(): AdminSession {
        var s = session ?: error("Login required")
        if (s.expiresAt > System.currentTimeMillis() / 1000 + 60) return s
        val json = request("POST", "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", s.refresh))
        s = AdminSession(json.getString("access_token"), json.getString("refresh_token"),
            System.currentTimeMillis() / 1000 + json.optLong("expires_in", 3600))
        session = s
        AdminSessionStore.save(this, s)
        return s
    }

    private fun loadDashboard() {
        if (busy) return
        busy = true
        status.text = "Owner authorization और dashboard data जाँचा जा रहा है…"
        thread {
            try {
                val s = authorizedSession()
                val json = request("GET", "/functions/v1/admin-overview", token = s.access)
                if (!json.optBoolean("ok")) error(json.optString("error", "Owner access denied"))
                runOnUiThread {
                    busy = false
                    status.text = "Verified role: " + json.optString("role", "unknown") +
                        "  •  " + json.optString("generated_at")
                    renderDashboard(json)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    status.text = e.message ?: "Dashboard load failed"
                    if (status.text.contains("403") || status.text.contains("401") ||
                        status.text.contains("Invalid session")) {
                        AdminSessionStore.clear(this)
                        session = null
                        render()
                    }
                }
            }
        }
    }

    private fun renderDashboard(data: JSONObject) {
        val body = dashboardBody ?: return
        body.removeAllViews()
        body.addView(label("LIVE PLATFORM METRICS", 17f))
        val metrics = data.optJSONObject("metrics") ?: JSONObject()
        val keys = listOf("profiles", "videos", "live_rooms", "withdrawal_requests",
            "star_campaigns", "star_video_promotions", "watch_sessions_total",
            "watch_sessions_active", "watch_sessions_completed", "watch_sessions_rejected",
            "qualified_unique_view_records_total", "watch_risk_signals_needs_review")
        keys.forEach { key ->
            val value = if (metrics.isNull(key)) "Unavailable" else metrics.opt(key).toString()
            body.addView(label(key.replace('_', ' ').uppercase() + "   " + value, 14f))
        }
        body.addView(label("FINANCIAL SUMMARY", 17f))
        val financials = data.optJSONObject("financials") ?: JSONObject()
        listOf("paid_orders_count", "creator_gift_gross_coins", "creator_gift_net_coins",
            "platform_gift_fee_coins", "gift_commission_ledger_matches").forEach { key ->
            body.addView(label(key.replace('_', ' ') + "   " +
                if (financials.isNull(key)) "Unavailable" else financials.opt(key).toString(), 14f))
        }
        body.addView(label("LATEST WITHDRAWALS", 17f))
        val withdrawals = data.optJSONArray("withdrawals")
        if (withdrawals == null || withdrawals.length() == 0) body.addView(label("No withdrawal records"))
        else for (i in 0 until minOf(withdrawals.length(), 50)) {
            val row = withdrawals.optJSONObject(i) ?: continue
            body.addView(label(
                "Amount: ${row.optString("amount")} ${row.optString("currency")}  •  " +
                "${row.optString("status")}\nCreator: ${row.optString("creator_id")}\n" +
                row.optString("created_at"), 13f, Color.LTGRAY))
        }
        body.addView(label("CONTENT REPORTS", 17f))
        val reports = data.optJSONArray("content_reports")
        if (reports == null || reports.length() == 0) body.addView(label("No content reports"))
        else for (i in 0 until minOf(reports.length(), 50)) {
            val row = reports.optJSONObject(i) ?: continue
            body.addView(label(
                "${row.optString("target_type")} • ${row.optString("reason")}\n" +
                "${row.optString("status")} • ${row.optString("created_at")}", 13f, Color.LTGRAY))
        }
        body.addView(label("यह स्क्रीन वास्तविक server data दिखाती है। कोई demo counters नहीं।", 12f, Color.LTGRAY))
    }

    private fun request(method: String, path: String, body: JSONObject? = null, token: String? = null): JSONObject {
        val conn = (URL(SUPABASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 60000
            setRequestProperty("apikey", SUPABASE_KEY)
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            val msg = try {
                val err = JSONObject(text)
                err.optString("msg").ifBlank { err.optString("message").ifBlank { err.optString("error") } }
            } catch (_: Exception) { text }
            throw IllegalStateException("HTTP $code: " + msg.ifBlank { "Request failed" })
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
