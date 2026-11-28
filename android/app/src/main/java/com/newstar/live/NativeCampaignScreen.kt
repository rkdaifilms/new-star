package com.newstar.live

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

@Composable
fun NativeCampaignScreen(session: NativeSession) {
    var campaigns by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var drafts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var applications by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var access by remember { mutableStateOf<JSONObject?>(null) }
    var title by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("brand_sponsorship") }
    var budget by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("NPR") }
    var countries by remember { mutableStateOf("NP") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var accessError by remember { mutableStateOf("") }
    var campaignsError by remember { mutableStateOf("") }
    var draftsError by remember { mutableStateOf("") }
    var applicationsError by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var proposalById by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var applyingId by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        accessError = ""
        campaignsError = ""
        draftsError = ""
        applicationsError = ""
        try {
            val rawAccess = NativeSupabase.post("/rest/v1/rpc/get_creator_feature_access", JSONObject(), session)
            access = JSONObject(rawAccess)
        } catch (e: Exception) {
            access = null
            accessError = e.message ?: "Creator eligibility उपलब्ध नहीं है।"
        }
        try {
            val activeRows = NativeSupabase.get(
                "/rest/v1/star_campaigns?select=id,title,description,brand_name,destination_url,currency,target_countries,starts_at,ends_at&status=eq.active&payment_status=eq.paid&order=created_at.desc&limit=50",
                session
            )
            campaigns = (0 until activeRows.length()).mapNotNull { activeRows.optJSONObject(it) }
        } catch (e: Exception) {
            campaigns = emptyList()
            campaignsError = e.message ?: "Active campaigns लोड नहीं हुए।"
        }
        try {
            val ownRows = NativeSupabase.get(
                "/rest/v1/star_campaigns?select=id,title,description,brand_name,destination_url,status,payment_status,created_at&owner_id=eq.${Uri.encode(session.userId)}&order=created_at.desc&limit=50",
                session
            )
            drafts = (0 until ownRows.length()).mapNotNull { ownRows.optJSONObject(it) }
        } catch (e: Exception) {
            drafts = emptyList()
            draftsError = e.message ?: "आपके drafts लोड नहीं हुए।"
        }
        try {
            val appRows = NativeSupabase.get(
                "/rest/v1/star_campaign_applications?select=id,campaign_id,proposal,status,created_at,star_campaigns(title,brand_name)&creator_id=eq.${Uri.encode(session.userId)}&order=created_at.desc&limit=50",
                session
            )
            applications = (0 until appRows.length()).mapNotNull { appRows.optJSONObject(it) }
        } catch (e: Exception) {
            applications = emptyList()
            applicationsError = e.message ?: "आपकी applications लोड नहीं हुईं।"
        }
        loading = false
    }

    LaunchedEffect(session.userId) { reload() }
    val createAllowed = access?.optBoolean("campaign_create_access") == true
    val joinAllowed = access?.optBoolean("campaign_join_access") == true
    val followerCount = access?.optInt("follower_count") ?: 0
    val appliedCampaignIds = remember(applications) {
        applications.mapNotNull { row -> row.optString("campaign_id").takeIf { it.isNotBlank() } }.toSet()
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Campaigns & Brand Deals", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = { scope.launch { reload() } }, enabled = !loading) { Text("Refresh") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (accessError.isNotBlank()) Text("Eligibility unavailable: $accessError", color = MaterialTheme.colorScheme.error)
        Text(
            if (createAllowed) "Unlocked · $followerCount verified followers" else "Locked · Campaign create/apply के लिए 1,000 genuine followers चाहिए। अभी: $followerCount/1,000",
            color = if (createAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Create campaign draft", style = MaterialTheme.typography.titleLarge)
                        Text("Draft निजी और unpaid रहेगा। यह publish नहीं होगा और कोई भुगतान नहीं करेगा।", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(kind, { kind = it }, label = { Text("Campaign type") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = createAllowed && !saving)
                        OutlinedTextField(title, { title = it.take(120) }, label = { Text("Campaign title") }, modifier = Modifier.fillMaxWidth(), enabled = createAllowed && !saving)
                        OutlinedTextField(brand, { brand = it.take(100) }, label = { Text("Brand / artist") }, modifier = Modifier.fillMaxWidth(), enabled = createAllowed && !saving)
                        OutlinedTextField(description, { description = it.take(4000) }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = createAllowed && !saving)
                        OutlinedTextField(destination, { destination = it.take(1000) }, label = { Text("Destination HTTPS link (optional)") }, modifier = Modifier.fillMaxWidth(), enabled = createAllowed && !saving)
                        OutlinedTextField(budget, { budget = it.filter { c -> c.isDigit() || c == '.' }.take(12) }, label = { Text("Budget (display only; draft remains unpaid)") }, modifier = Modifier.fillMaxWidth(), enabled = createAllowed && !saving)
                        OutlinedTextField(currency, { currency = it.filter(Char::isLetter).uppercase(Locale.ROOT).take(3) }, label = { Text("Currency ISO code") }, modifier = Modifier.fillMaxWidth(), enabled = createAllowed && !saving)
                        OutlinedTextField(countries, { countries = it.uppercase(Locale.ROOT).take(100) }, label = { Text("Target countries (ISO-2, comma-separated)") }, modifier = Modifier.fillMaxWidth(), enabled = createAllowed && !saving)
                        Button(
                            onClick = {
                                scope.launch {
                                    saving = true
                                    notice = ""
                                    try {
                                        require(createAllowed) { "1,000 genuine followers के बिना campaign draft नहीं बन सकता।" }
                                        require(title.isNotBlank() && brand.isNotBlank()) { "Title और Brand जरूरी हैं।" }
                                        require(kind in setOf("brand_sponsorship", "artist_promotion", "video_promotion")) { "Campaign type चुनें।" }
                                        val budgetValue = budget.toBigDecimalOrNull()
                                        require(budgetValue != null && budgetValue > java.math.BigDecimal.ZERO && budgetValue <= java.math.BigDecimal("1000000")) { "Budget 0 से अधिक और 1,000,000 तक होना चाहिए।" }
                                        require(currency.matches(Regex("[A-Z]{3}"))) { "Currency का 3-letter ISO code डालें।" }
                                        val countryCodes = countries.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                                        require(countryCodes.isNotEmpty() && countryCodes.all { it.matches(Regex("[A-Z]{2}")) }) { "Country codes ISO-2 format में डालें।" }
                                        val safeDestination = destination.trim().takeIf { it.isNotEmpty() }?.also {
                                            val parsed = java.net.URI(it)
                                            require(parsed.scheme == "https" && !parsed.host.isNullOrBlank()) { "केवल HTTPS link स्वीकार है।" }
                                        }
                                        val zeroDecimalCurrencies = setOf("BIF", "CLP", "DJF", "GNF", "ISK", "JPY", "KMF", "KRW", "PYG", "RWF", "UGX", "UYI", "VND", "VUV", "XAF", "XOF", "XPF")
                                        val threeDecimalCurrencies = setOf("BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND")
                                        val currencyDecimals = when (currency) { in zeroDecimalCurrencies -> 0; in threeDecimalCurrencies -> 3; else -> 2 }
                                        val minor = budgetValue.multiply(java.math.BigDecimal.TEN.pow(currencyDecimals)).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                                        val payload = JSONObject().put("owner_id", session.userId).put("campaign_kind", kind)
                                            .put("title", title.trim()).put("description", description.trim())
                                            .put("brand_name", brand.trim()).put("destination_url", safeDestination)
                                            .put("budget_minor", minor).put("currency", currency)
                                            .put("target_countries", JSONArray(countryCodes))
                                            .put("status", "draft").put("payment_status", "unpaid").put("platform_fee_minor", 0)
                                        NativeSupabase.post("/rest/v1/star_campaigns", payload, session)
                                        title = ""; brand = ""; description = ""; destination = ""; budget = ""
                                        notice = "Draft सेव हुआ। यह सार्वजनिक नहीं है और कोई भुगतान नहीं हुआ।"
                                        reload()
                                    } catch (e: Exception) { notice = e.message ?: "Draft save नहीं हुआ।" }
                                    finally { saving = false }
                                }
                            },
                            enabled = createAllowed && !saving,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (saving) "Saving…" else "Save unpaid draft") }
                        if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            item { Text("Verified active & paid campaigns", style = MaterialTheme.typography.titleLarge) }
            if (campaignsError.isNotBlank()) item { Text(campaignsError, color = MaterialTheme.colorScheme.error) }
            if (campaigns.isEmpty() && !loading) item { Text("अभी कोई सक्रिय payment-verified campaign उपलब्ध नहीं है।") }
            items(campaigns, key = { it.optString("id") }) { campaign ->
                val id = campaign.optString("id")
                val alreadyApplied = id in appliedCampaignIds
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(campaign.optString("title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(campaign.optString("brand_name"), style = MaterialTheme.typography.bodySmall)
                        Text(campaign.optString("description"), style = MaterialTheme.typography.bodyMedium)
                        Text("Verified active · Paid", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(
                            value = proposalById[id].orEmpty(),
                            onValueChange = { value -> proposalById = proposalById + (id to value.take(2000)) },
                            label = { Text("Your proposal (no payment details)") },
                            modifier = Modifier.fillMaxWidth(), minLines = 2, enabled = joinAllowed && applyingId != id && !alreadyApplied
                        )
                        Button(
                            enabled = joinAllowed && !alreadyApplied && applyingId != id && proposalById[id].orEmpty().isNotBlank(),
                            onClick = {
                                scope.launch {
                                    applyingId = id
                                    try {
                                        NativeSupabase.post("/rest/v1/star_campaign_applications",
                                            JSONObject().put("campaign_id", id).put("creator_id", session.userId)
                                                .put("proposal", proposalById[id]?.trim()).put("status", "pending"), session)
                                        proposalById = proposalById - id
                                        notice = "Application review के लिए भेजी गई। कोई भुगतान या कमाई नहीं बनी।"
                                        reload()
                                    } catch (e: Exception) {
                                        val message = e.message.orEmpty()
                                        if (message.contains("duplicate", ignoreCase = true) ||
                                            message.contains("unique constraint", ignoreCase = true) ||
                                            message.contains("23505")) {
                                            notice = "इस campaign में आपका आवेदन पहले से मौजूद है। सूची refresh की जा रही है।"
                                            reload()
                                        } else {
                                            notice = message.ifBlank { "Application submit नहीं हुई।" }
                                        }
                                    } finally { applyingId = "" }
                                }
                            }, modifier = Modifier.fillMaxWidth()
                        ) { Text(when { applyingId == id -> "Sending…"; alreadyApplied -> "Application already submitted"; else -> "Send application" }) }
                        if (alreadyApplied) Text("आप इस campaign में पहले ही आवेदन भेज चुके हैं। मौजूदा आवेदन की स्थिति नीचे देखें।", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        if (!joinAllowed) Text("Apply करने के लिए 1,000 genuine followers जरूरी हैं।", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        if (campaign.optString("destination_url").startsWith("https://")) Text(campaign.optString("destination_url"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text("Your applications", style = MaterialTheme.typography.titleLarge) }
            if (applicationsError.isNotBlank()) item { Text(applicationsError, color = MaterialTheme.colorScheme.error) }
            if (applications.isEmpty() && !loading) item { Text("अभी कोई application नहीं है।") }
            items(applications, key = { it.optString("id") }) { row ->
                val campaign = row.optJSONObject("star_campaigns")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(campaign?.optString("title").orEmpty().ifBlank { "Campaign" }, fontWeight = FontWeight.SemiBold)
                        Text("Status: ${row.optString("status")}")
                        Text(row.optString("proposal"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text("Your drafts", style = MaterialTheme.typography.titleLarge) }
            if (draftsError.isNotBlank()) item { Text(draftsError, color = MaterialTheme.colorScheme.error) }
            if (drafts.isEmpty() && !loading) item { Text("आपने अभी कोई campaign draft नहीं बनाया है।") }
            items(drafts, key = { it.optString("id") }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(row.optString("title"), fontWeight = FontWeight.SemiBold)
                        Text(row.optString("brand_name"))
                        Text("Draft · ${row.optString("payment_status")} · ${row.optString("status")}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
