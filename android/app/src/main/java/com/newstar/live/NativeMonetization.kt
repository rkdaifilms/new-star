package com.newstar.live

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class MonetizationProgress(
    val followers: Long = 0,
    val watchHours: Double = 0.0,
    val shortViews: Long = 0,
    val eligible: Boolean = false,
    val ageVerified: Boolean = false,
    val contactVerified: Boolean = false,
    val applicationStatus: String = "not_submitted"
)

@Composable
fun NativeMonetizationScreen(
    session: NativeSession,
    onOpenWallet: () -> Unit,
    onOpenFanSupport: () -> Unit
) {
    val context = LocalContext.current
    var showReferralDialog by remember { mutableStateOf(false) }
    var referralFeedback by remember { mutableStateOf("") }
    var referralCount by remember { mutableLongStateOf(0L) }
    var referralStatsError by remember { mutableStateOf("") }
    val referralLink = remember(session.userId) {
        Uri.Builder()
            .scheme("https")
            .authority("new-star-delta.vercel.app")
            .appendPath("auth.html")
            .appendQueryParameter("ref", session.userId)
            .build()
            .toString()
    }
    var progress by remember { mutableStateOf<MonetizationProgress?>(null) }
    var featureAccess by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(session.userId, refresh) {
        loading = true
        error = ""
        try {
            val pRaw = NativeSupabase.post("/rest/v1/rpc/get_creator_monetization_progress", JSONObject(), session)
            val fRaw = NativeSupabase.post("/rest/v1/rpc/get_creator_feature_access", JSONObject(), session)
            val p = JSONObject(pRaw)
            featureAccess = JSONObject(fRaw)
            progress = MonetizationProgress(
                followers = p.optLong("follower_count"),
                watchHours = p.optDouble("watch_hours_60d"),
                shortViews = p.optLong("short_views_60d", p.optLong("short_views_30d")),
                eligible = p.optBoolean("eligible_for_review"),
                ageVerified = p.optBoolean("age_18_plus_verified"),
                contactVerified = p.optBoolean("contact_verified"),
                applicationStatus = p.optString("application_status", "not_submitted")
            )
        } catch (e: Exception) {
            error = e.message ?: "Creator progress सत्यापित नहीं हो पाया।"
        } finally { loading = false }
    }

    LaunchedEffect(session.userId, refresh) {
        referralStatsError = ""
        try {
            val result = NativeSupabase.post("/rest/v1/rpc/get_my_referral_stats", JSONObject(), session)
            referralCount = JSONObject(result).optLong("referral_count")
        } catch (e: Exception) {
            referralStatsError = e.message ?: "Referral count लोड नहीं हुआ।"
        }
    }

    val p = progress
    val f = featureAccess
    val liveAccess = f?.optBoolean("live_access") == true
    val campaignAccess = f?.optBoolean("campaign_create_access") == true
    val sponsorAccess = f?.optBoolean("sponsorship_access") == true
    val liveGiftAccess = f?.optBoolean("live_gifts_access") == true
    val videoGiftAccess = f?.optBoolean("video_gifts_access") == true

    @Composable
    fun ProgressLine(label: String, value: Double, target: Double, suffix: String) {
        val fraction = (value / target).toFloat().coerceIn(0f, 1f)
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(value.toLong().toString() + " / " + target.toLong() + " " + suffix,
                    style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }

    @Composable
    fun HubRow(title: String, detail: String, state: String, onClick: (() -> Unit)? = null) {
        Card(Modifier.fillMaxWidth().clickable(enabled = onClick != null) { onClick?.invoke() }) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(state, style = MaterialTheme.typography.labelMedium)
                }
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Creator Monetization Hub", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Refresh") }
            }
            Text("सिर्फ verified server progress और feature-access status दिखाए जाते हैं।")
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Creator Growth Program", style = MaterialTheme.typography.titleLarge)
                    if (p == null) {
                        Text("Progress अभी उपलब्ध नहीं है। Refresh करके फिर जाँचें।")
                    } else {
                        ProgressLine("Genuine followers", p.followers.toDouble(), 5000.0, "followers")
                        ProgressLine("Public long-video watch time · last 60 days", p.watchHours, 1000.0, "hours")
                        ProgressLine("Qualified Shorts views · last 60 days", p.shortViews.toDouble(), 300000.0, "views")
                        Text(
                            when {
                                p.applicationStatus == "pending" -> "Application: review pending"
                                !p.ageVerified -> "18+ age/identity verification required before monetization application"
                                !p.contactVerified -> "Verified email or phone required"
                                p.eligible -> "Performance thresholds reached · final quality and policy review still required"
                                else -> "Eligibility thresholds not reached yet"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        item {
            Text("Eligibility Rules", style = MaterialTheme.typography.titleLarge)
            Text("1,000 genuine followers: LIVE, LIVE Gifts, LIVE Gaming, Sponsorship and Campaign access, subject to server feature checks.")
            Text("5,000 genuine followers plus either 1,000 eligible public long-video watch hours OR 300,000 eligible public Shorts views in the last 60 days: video/ads monetization review route.")
            Text("Monetization review requires the creator to be 18+, verified age/identity, verified email or phone, original public content and policy compliance. Meeting thresholds does not guarantee approval or earnings.")
        }
        item { Text("Creator Features", style = MaterialTheme.typography.titleLarge) }
        item { HubRow("LIVE & LIVE Gaming", "Live creator access is controlled by verified account eligibility.", if (liveAccess) "Unlocked" else "Locked / not verified") }
        item { HubRow("LIVE Gifts & Treasure Box", "Old catalogue is preview-only; secure send/claim route must be active.", if (liveGiftAccess) "Access flag on" else "Setup / eligibility pending") }
        item { HubRow("Video Gifts", "Requires qualifying video route and monetization approval.", if (videoGiftAccess) "Access flag on" else "Approval pending") }
        item { HubRow("Campaigns", "Brand and artist campaigns; access is verified by server.", if (campaignAccess) "Unlocked" else "1,000 followers / eligibility pending") }
        item { HubRow("Creator Sponsorship & Brand Deals", "Create a sponsorship draft or apply to eligible campaigns.", if (sponsorAccess) "Unlocked" else "1,000 followers / eligibility pending") }
        item { HubRow("Affiliate Marketing", "Not enabled in the original system.", "Coming soon") }
        item { HubRow("Promote & Boost Video", "Checkout remains disabled until payment gateway and ad-delivery verification are connected.", "Unavailable") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("New Star Referral Hub", style = MaterialTheme.typography.titleMedium)
                    Text("आपके invite link से जुड़े verified signups: $referralCount")
                    if (referralStatsError.isNotBlank()) Text(referralStatsError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("कोई reward या payment स्वतः नहीं बनता।", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { referralFeedback = ""; showReferralDialog = true }) { Text("Share invite link") }
                }
            }
        }
        item { HubRow("Creator Fan Support", "Free community support: view your real followers and creators you follow.", "Open") { onOpenFanSupport() } }
        item {
            Text("Revenue Share — Draft Only", style = MaterialTheme.typography.titleLarge)
            Text("Video Gifts: 50% platform / 50% creator. LIVE Gifts: 50% / 50%. LIVE Gaming Gifts: 50% / 50%. Sponsorship: 20% / 80%. Ads proposal: 55% creator / 45% platform.")
            Text("ये draft percentages हैं, confirmed payouts नहीं। Secure ledger, provider approval और verified settlement के बिना इन्हें कमाई नहीं माना जाएगा.",
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            Button(onClick = onOpenWallet, modifier = Modifier.fillMaxWidth()) {
                Text("Open Wallet & Earnings")
            }
        }
        item {
            Text("Payment and payout setup is not enabled here. For any future financial account or payout configuration, an adult parent/guardian must manage the verified account. No payment or earnings are promised.",
                style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showReferralDialog) {
        AlertDialog(
            onDismissRequest = { showReferralDialog = false },
            title = { Text("New Star Referral Hub") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("अपना invite link WhatsApp, Facebook, Telegram या किसी भी compatible app में share करें। इस link से कोई payment या earning अपने-आप नहीं बनती।")
                    OutlinedTextField(
                        value = referralLink,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Your invite link") }
                    )
                    if (referralFeedback.isNotBlank()) {
                        Text(referralFeedback, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard == null) {
                        referralFeedback = "इस डिवाइस पर clipboard उपलब्ध नहीं है।"
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("New Star invite link", referralLink))
                        referralFeedback = "Invite link copied."
                        Toast.makeText(context, "Invite link copied", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Copy link") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        try {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "New Star Creator Hub")
                                putExtra(Intent.EXTRA_TEXT, "Join me on New Star Creator Hub: $referralLink")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share New Star link"))
                        } catch (e: Exception) {
                            referralFeedback = e.message ?: "Share sheet नहीं खुल सका।"
                        }
                    }) { Text("Share") }
                    TextButton(onClick = { showReferralDialog = false }) { Text("Close") }
                }
            }
        )
    }
}
