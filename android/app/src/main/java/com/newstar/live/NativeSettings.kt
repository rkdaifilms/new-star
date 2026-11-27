package com.newstar.live

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

private val nsLanguages = listOf(
    "hi" to "हिन्दी — Hindi", "ne" to "नेपाली — Nepali", "en" to "English",
    "zh" to "中文 — Chinese", "es" to "Español — Spanish", "fr" to "Français — French",
    "ar" to "العربية — Arabic", "ja" to "日本語 — Japanese", "de" to "Deutsch — German",
    "ru" to "Русский — Russian", "pt" to "Português — Portuguese", "it" to "Italiano — Italian",
    "ko" to "한국어 — Korean", "tr" to "Türkçe — Turkish", "bn" to "বাংলা — Bengali",
    "ur" to "اردو — Urdu", "id" to "Bahasa Indonesia", "vi" to "Tiếng Việt — Vietnamese",
    "th" to "ไทย — Thai", "fa" to "فارسی — Persian", "sw" to "Kiswahili — Swahili",
    "nl" to "Nederlands — Dutch", "pl" to "Polski — Polish", "uk" to "Українська — Ukrainian",
    "el" to "Ελληνικά — Greek"
)
private val nsThemes = listOf(
    "dark" to "01 · Midnight Dark", "light" to "02 · Clean Light", "ocean" to "03 · Ocean Blue",
    "forest" to "04 · Forest Green", "sunset" to "05 · Sunset Orange", "purple" to "06 · Royal Purple",
    "rose" to "07 · Rose Pink", "crimson" to "08 · Crimson Red", "gold" to "09 · Golden Hour",
    "teal" to "10 · Tropical Teal", "cyan" to "11 · Arctic Cyan", "indigo" to "12 · Deep Indigo",
    "lavender" to "13 · Lavender", "mint" to "14 · Fresh Mint", "emerald" to "15 · Emerald",
    "ruby" to "16 · Ruby", "amber" to "17 · Amber", "coral" to "18 · Coral",
    "slate" to "19 · Slate Grey", "coffee" to "20 · Coffee Brown", "plum" to "21 · Plum",
    "sakura" to "22 · Sakura", "neon" to "23 · Neon Night", "sand" to "24 · Desert Sand",
    "aurora" to "25 · Aurora"
)

@Composable
fun NativeSettingsScreen(session: NativeSession, onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val local = remember { context.getSharedPreferences("newstar_preferences", Context.MODE_PRIVATE) }
    var language by remember { mutableStateOf(local.getString("language", "hi") ?: "hi") }
    var theme by remember { mutableStateOf(local.getString("theme", "dark") ?: "dark") }
    var textScale by remember { mutableStateOf(local.getString("textScale", "1") ?: "1") }
    var reduceMotion by remember { mutableStateOf(local.getBoolean("reducedMotion", false)) }
    var highContrast by remember { mutableStateOf(local.getBoolean("highContrast", false)) }
    var notifyFollow by remember { mutableStateOf(local.getBoolean("notifyFollow", true)) }
    var notifyLike by remember { mutableStateOf(local.getBoolean("notifyLike", true)) }
    var notifyComment by remember { mutableStateOf(local.getBoolean("notifyComment", true)) }
    var notifyMessage by remember { mutableStateOf(local.getBoolean("notifyMessage", true)) }
    var privateAccount by remember { mutableStateOf(false) }
    var languageMenu by remember { mutableStateOf(false) }
    var themeMenu by remember { mutableStateOf(false) }
    var scaleMenu by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("स्थानीय सेटिंग तैयार हैं।") }
    var busy by remember { mutableStateOf(false) }

    fun localSave() {
        local.edit().putString("language", language).putString("theme", theme)
            .putString("textScale", textScale).putBoolean("reducedMotion", reduceMotion)
            .putBoolean("highContrast", highContrast).putBoolean("notifyFollow", notifyFollow)
            .putBoolean("notifyLike", notifyLike).putBoolean("notifyComment", notifyComment)
            .putBoolean("notifyMessage", notifyMessage).apply()
    }

    suspend fun syncPreferences() {
        val prefs = JSONObject()
            .put("language", language).put("theme", theme)
            .put("accessibility", JSONObject().put("textScale", textScale.toDouble())
                .put("reducedMotion", reduceMotion).put("highContrast", highContrast))
            .put("notifications", JSONObject().put("follows", notifyFollow).put("likes", notifyLike)
                .put("comments", notifyComment).put("messages", notifyMessage))
            .put("_updatedAt", java.time.Instant.now().toString())
        val rows = NativeSupabase.get(
            "/rest/v1/user_preferences?select=preferences&user_id=eq.${session.userId}", session
        )
        if (rows.length() > 0) {
            NativeSupabase.patch(
                "/rest/v1/user_preferences?user_id=eq.${session.userId}",
                JSONObject().put("preferences", prefs).put("updated_at", java.time.Instant.now().toString()), session
            )
        } else {
            NativeSupabase.post(
                "/rest/v1/user_preferences",
                JSONObject().put("user_id", session.userId).put("preferences", prefs), session
            )
        }
    }

    LaunchedEffect(session.userId) {
        try {
            val rows = NativeSupabase.get(
                "/rest/v1/profiles?select=is_private&user_id=eq.${session.userId}", session
            )
            if (rows.length() > 0) privateAccount = rows.optJSONObject(0)?.optBoolean("is_private") == true
        } catch (_: Exception) { }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("⚙️ Main Settings", style = MaterialTheme.typography.headlineSmall)
        Text("Account: ${session.email}", style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Text("01 · Notifications", style = MaterialTheme.typography.titleLarge)
        Text("In-app notification preferences")
        SettingToggle("New followers and follow requests", notifyFollow) { notifyFollow = it; localSave() }
        SettingToggle("Video likes", notifyLike) { notifyLike = it; localSave() }
        SettingToggle("Video comments", notifyComment) { notifyComment = it; localSave() }
        SettingToggle("Direct messages", notifyMessage) { notifyMessage = it; localSave() }
        Text("Browser push notifications अभी उपलब्ध नहीं हैं; Inbox की in-app notifications अलग हैं.",
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("02 · Account", style = MaterialTheme.typography.titleLarge)
        Text("Signed-in email: ${session.email}")
        Text("Password बदलने का native flow अभी जोड़ा नहीं गया है।")
        Button(onClick = onLogout) { Text("Log out this device") }
        HorizontalDivider()
        Text("03 · Privacy & Security", style = MaterialTheme.typography.titleLarge)
        SettingToggle("Private account", privateAccount) { enabled ->
            privateAccount = enabled
            scope.launch {
                busy = true
                try {
                    NativeSupabase.patch(
                        "/rest/v1/profiles?user_id=eq.${session.userId}",
                        JSONObject().put("is_private", enabled), session
                    )
                    status = "Private account सेटिंग सेव हो गई।"
                } catch (e: Exception) { status = e.message ?: "Privacy setting सेव नहीं हुई।" }
                busy = false
            }
        }
        Text("Blocked Accounts और सभी sessions से logout का native control अभी बाकी है.",
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("04 · Language & Appearance", style = MaterialTheme.typography.titleLarge)
        Text("App Language")
        Box {
            OutlinedButton(onClick = { languageMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(nsLanguages.firstOrNull { it.first == language }?.second ?: language)
            }
            DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                nsLanguages.forEach { item -> DropdownMenuItem(
                    text = { Text(item.second) }, onClick = {
                        language = item.first; languageMenu = false; localSave()
                    }
                ) }
            }
        }
        Text("App Theme")
        Box {
            OutlinedButton(onClick = { themeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(nsThemes.firstOrNull { it.first == theme }?.second ?: theme)
            }
            DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                nsThemes.forEach { item -> DropdownMenuItem(
                    text = { Text(item.second) }, onClick = {
                        theme = item.first; themeMenu = false; localSave()
                    }
                ) }
            }
        }
        Text("05 · Accessibility & Display", style = MaterialTheme.typography.titleLarge)
        Text("Text size")
        Box {
            OutlinedButton(onClick = { scaleMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(when (textScale) { "0.9" -> "Small"; "1.1" -> "Large"; "1.2" -> "Extra large"; "1.25" -> "Largest"; else -> "Default" })
            }
            DropdownMenu(expanded = scaleMenu, onDismissRequest = { scaleMenu = false }) {
                listOf("0.9" to "Small", "1" to "Default", "1.1" to "Large", "1.2" to "Extra large", "1.25" to "Largest").forEach {
                    DropdownMenuItem(text = { Text(it.second) }, onClick = { textScale = it.first; scaleMenu = false; localSave() })
                }
            }
        }
        SettingToggle("Reduce motion", reduceMotion) { reduceMotion = it; localSave() }
        SettingToggle("High contrast", highContrast) { highContrast = it; localSave() }
        HorizontalDivider()
        Text("06 · Creator & Monetization", style = MaterialTheme.typography.titleLarge)
        Text("Creator Monetization Hub और Wallet के native screens अलग से उपलब्ध हैं। Provider/payment setup केवल अधिकृत वयस्क account owner के माध्यम से होना चाहिए.")
        Text("07 · Ads & Policies", style = MaterialTheme.typography.titleLarge)
        Text("Website AdSense approval और Android AdMob integration अलग प्रक्रियाएँ हैं; अभी native Settings से कोई approval या earning verified नहीं है.")
        Text("08 · Help & About", style = MaterialTheme.typography.titleLarge)
        Text("New Star Creator Hub · Native Android")
        Button(onClick = {
            localSave()
            scope.launch {
                busy = true
                status = "Account preferences sync हो रही हैं…"
                try { syncPreferences(); status = "Preferences account के साथ sync हो गईं।" }
                catch (e: Exception) { status = e.message ?: "Cloud sync असफल रही। स्थानीय सेटिंग सेव हैं।" }
                busy = false
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Save & Sync Preferences")
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        Text("नोट: 25 भाषा/थीम विकल्प उपलब्ध हैं। सभी स्क्रीन का पूर्ण अनुवाद, हर स्क्रीन पर theme application, password/email update, blocked accounts और logout-all अभी पूरा port नहीं हुआ है.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
