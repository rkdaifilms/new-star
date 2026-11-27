package com.newstar.live

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun NativeFanSupportScreen(
    session: NativeSession,
    onOpenCreator: (String) -> Unit
) {
    var fans by remember(session.userId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var following by remember(session.userId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedTab by remember(session.userId) { mutableIntStateOf(0) }
    var loading by remember(session.userId) { mutableStateOf(true) }
    var error by remember(session.userId) { mutableStateOf("") }
    var refreshKey by remember(session.userId) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session.userId, refreshKey) {
        loading = true
        error = ""
        try {
            val followerRows = NativeSupabase.get(
                "/rest/v1/followers?select=follower_id,created_at&following_id=eq.${Uri.encode(session.userId)}&order=created_at.desc&limit=200",
                session
            )
            val followingRows = NativeSupabase.get(
                "/rest/v1/followers?select=following_id,created_at&follower_id=eq.${Uri.encode(session.userId)}&order=created_at.desc&limit=200",
                session
            )
            val fanIds = (0 until followerRows.length()).mapNotNull {
                followerRows.optJSONObject(it)?.optString("follower_id")?.takeIf(String::isNotBlank)
            }
            val followingIds = (0 until followingRows.length()).mapNotNull {
                followingRows.optJSONObject(it)?.optString("following_id")?.takeIf(String::isNotBlank)
            }
            val allIds = (fanIds + followingIds).distinct()
            val profiles = if (allIds.isEmpty()) emptyList() else {
                val inFilter = Uri.encode("(${allIds.joinToString(",")})")
                val rows = NativeSupabase.get(
                    "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url,is_private&user_id=in.$inFilter",
                    session
                )
                (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            }
            val byId = profiles.associateBy { it.optString("user_id") }
            fun profileList(ids: List<String>) = ids.map { id ->
                byId[id] ?: JSONObject()
                    .put("user_id", id)
                    .put("username", "Private / unavailable creator")
            }
            fans = profileList(fanIds)
            following = profileList(followingIds)
        } catch (e: Exception) {
            error = e.message ?: "Community list लोड नहीं हुई।"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("⭐ Creator Fan Support", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "यह मुफ्त community support है। यहाँ आपके वास्तविक followers और आपके द्वारा follow किए गए creators दिखते हैं। कोई paid membership, coin transfer या earning सुविधा अभी उपलब्ध नहीं है।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CommunityCountCard("Your Fans", fans.size, Modifier.weight(1f))
                    CommunityCountCard("You Support", following.size, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { refreshKey++ }, enabled = !loading) { Text("Refresh") }
                }
            }
        }
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Your Fans") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("You Support") })
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        val rows = if (selectedTab == 0) fans else following
        if (!loading && error.isBlank() && rows.isEmpty()) {
            Text(
                if (selectedTab == 0) "अभी आपका कोई follower नहीं है।" else "आपने अभी किसी creator को follow नहीं किया है।",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.optString("user_id") }) { profile ->
                val id = profile.optString("user_id")
                Card(Modifier.fillMaxWidth().clickable(enabled = id.isNotBlank() && profile.optString("username") != "Private / unavailable creator") {
                    onOpenCreator(id)
                }) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AsyncImage(
                            model = profile.optString("avatar_url").takeIf { it.startsWith("https://") },
                            contentDescription = "Creator avatar",
                            modifier = Modifier.size(46.dp).clip(CircleShape)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                profile.optString("full_name").ifBlank {
                                    profile.optString("username").ifBlank { "New Star Creator" }
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                            val username = profile.optString("username")
                            if (username.isNotBlank() && username != "Private / unavailable creator") {
                                Text("@$username", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text("Private / unavailable creator", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (id.isNotBlank() && profile.optString("username") != "Private / unavailable creator") {
                            Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityCountCard(label: String, count: Int, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
