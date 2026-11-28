package com.newstar.live

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

internal data class InboxProfile(
    val id: String,
    val name: String,
    val username: String,
    val avatar: String,
    val isPrivate: Boolean = false
)

internal data class InboxMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val body: String,
    val mediaPath: String?,
    val createdAt: String,
    val readAt: String?
)

internal data class InboxThread(val peer: InboxProfile, val latest: InboxMessage)
internal data class InboxFollowRequest(val id: String, val requesterId: String, val profile: InboxProfile)

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.stringOrNull(key: String): String? =
    optString(key).takeIf { it.isNotBlank() && it != "null" }

private class NativeInboxRepository(private val session: NativeSession) {
    private fun q(value: String) = Uri.encode(value)

    private fun profile(row: JSONObject) = InboxProfile(
        id = row.optString("user_id"),
        name = row.stringOrNull("full_name") ?: row.stringOrNull("username") ?: "New Star User",
        username = row.stringOrNull("username").orEmpty(),
        avatar = row.stringOrNull("avatar_url").orEmpty(),
        isPrivate = row.optBoolean("is_private")
    )

    private suspend fun profiles(ids: List<String>): Map<String, InboxProfile> {
        if (ids.isEmpty()) return emptyMap()
        val unique = ids.distinct()
        val filter = unique.joinToString(",") { it }
        val rows = NativeSupabase.get(
            "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url,is_private&user_id=in.($filter)",
            session
        )
        return rows.objects().map(::profile).associateBy { it.id }
    }

    private fun message(row: JSONObject) = InboxMessage(
        id = row.optString("id"),
        conversationId = row.optString("conversation_id"),
        senderId = row.optString("sender_id"),
        recipientId = row.optString("recipient_id"),
        body = row.optString("body"),
        mediaPath = row.stringOrNull("media_url"),
        createdAt = row.optString("created_at"),
        readAt = row.stringOrNull("read_at")
    )

    suspend fun loadThreads(): List<InboxThread> {
        val rows = NativeSupabase.get(
            "/rest/v1/messages?select=id,conversation_id,sender_id,recipient_id,body,media_url,created_at,read_at" +
                "&or=(sender_id.eq.${q(session.userId)},recipient_id.eq.${q(session.userId)})" +
                "&order=created_at.desc&limit=250",
            session
        ).objects().map(::message)
        val latestByPeer = LinkedHashMap<String, InboxMessage>()
        rows.forEach { row ->
            val peerId = if (row.senderId == session.userId) row.recipientId else row.senderId
            if (peerId != session.userId && !latestByPeer.containsKey(peerId)) latestByPeer[peerId] = row
        }
        val profileMap = profiles(latestByPeer.keys.toList())
        return latestByPeer.mapNotNull { (peerId, latest) ->
            profileMap[peerId]?.let { InboxThread(it, latest) }
        }
    }

    suspend fun loadMessages(conversationId: String): List<InboxMessage> {
        val rows = NativeSupabase.get(
            "/rest/v1/messages?select=id,conversation_id,sender_id,recipient_id,body,media_url,created_at,read_at" +
                "&conversation_id=eq.${q(conversationId)}&order=created_at.asc&limit=300",
            session
        )
        return rows.objects().map(::message)
    }

    suspend fun findOrCreateConversation(peerId: String): String {
        require(peerId.isNotBlank() && peerId != session.userId) { "आप खुद को Message नहीं भेज सकते।" }
        val filter = "and(sender_id.eq.${session.userId},recipient_id.eq.$peerId),and(sender_id.eq.$peerId,recipient_id.eq.${session.userId})"
        val rows = NativeSupabase.get(
            "/rest/v1/messages?select=conversation_id,created_at&or=(${q(filter)})&order=created_at.desc&limit=1",
            session
        )
        rows.optJSONObject(0)?.stringOrNull("conversation_id")?.let { return it }
        val pair = listOf(session.userId, peerId).sorted().joinToString(":")
        val digest = MessageDigest.getInstance("SHA-256").digest("new-star-dm:$pair".toByteArray(Charsets.UTF_8))
        val hex = digest.take(16).joinToString("") { "%02x".format(it) }
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-5" +
            hex.substring(13, 16) + "-a" + hex.substring(17, 20) + "-" + hex.substring(20, 32)
    }

    suspend fun sendText(peerId: String, conversationId: String, body: String) {
        val clean = body.trim()
        require(clean.isNotEmpty() && clean.length <= 2000) { "Message 1 से 2000 अक्षर का होना चाहिए।" }
        NativeSupabase.post(
            "/rest/v1/messages",
            JSONObject().put("conversation_id", conversationId)
                .put("sender_id", session.userId).put("recipient_id", peerId).put("body", clean),
            session
        )
    }

    suspend fun sendAttachment(peerId: String, conversationId: String, displayName: String, bytes: ByteArray, mime: String) {
        require(bytes.size in 1..NativeInboxAttachmentPolicy.MAX_BYTES) {
            "Attachment 20 MB से छोटी होनी चाहिए।"
        }
        val normalizedMime = NativeInboxAttachmentPolicy.normalizeMimeType(mime)
        val safeName = NativeInboxAttachmentPolicy.safeDisplayName(displayName, normalizedMime)
        val extension = NativeInboxAttachmentPolicy.extensionForMimeType(normalizedMime)
        val path = "${session.userId}/${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.$extension"
        val storedPath = NativeSupabase.uploadPrivateObject("message-media", path, bytes, normalizedMime, session)
        NativeSupabase.post(
            "/rest/v1/messages",
            JSONObject().put("conversation_id", conversationId)
                .put("sender_id", session.userId).put("recipient_id", peerId)
                .put("body", "📎 $safeName").put("media_url", storedPath),
            session
        )
    }

    suspend fun signedMediaUrl(path: String): String =
        NativeSupabase.createSignedObjectUrl("message-media", path, session)

    suspend fun markRead(conversationId: String) {
        NativeSupabase.patch(
            "/rest/v1/messages?conversation_id=eq.${q(conversationId)}" +
                "&recipient_id=eq.${q(session.userId)}&read_at=is.null",
            JSONObject().put("read_at", java.time.Instant.now().toString()),
            session
        )
    }

    suspend fun loadRelations(followers: Boolean): List<InboxProfile> {
        val field = if (followers) "following_id" else "follower_id"
        val peerField = if (followers) "follower_id" else "following_id"
        val rows = NativeSupabase.get(
            "/rest/v1/followers?select=follower_id,following_id,created_at&$field=eq.${q(session.userId)}" +
                "&order=created_at.desc&limit=200",
            session
        ).objects()
        val ids = rows.map { it.optString(peerField) }.filter { it.isNotBlank() }
        val map = profiles(ids)
        return ids.mapNotNull { map[it] }
    }

    suspend fun loadFollowRequests(): List<InboxFollowRequest> {
        val rows = NativeSupabase.get(
            "/rest/v1/follow_requests?select=id,requester_id,created_at,status" +
                "&target_user_id=eq.${q(session.userId)}&status=eq.pending&order=created_at.desc&limit=100",
            session
        ).objects()
        val ids = rows.map { it.optString("requester_id") }
        val map = profiles(ids)
        return rows.mapNotNull { row ->
            val id = row.optString("id")
            val requesterId = row.optString("requester_id")
            map[requesterId]?.let { InboxFollowRequest(id, requesterId, it) }
        }
    }

    suspend fun respondToFollowRequest(requestId: String, requesterId: String, accept: Boolean) {
        val status = if (accept) "accepted" else "rejected"
        NativeSupabase.patch(
            "/rest/v1/follow_requests?id=eq.${q(requestId)}" +
                "&target_user_id=eq.${q(session.userId)}&status=eq.pending",
            JSONObject().put("status", status),
            session
        )
        if (accept) {
            NativeSupabase.upsert(
                "/rest/v1/followers",
                JSONObject().put("follower_id", requesterId).put("following_id", session.userId),
                session,
                ignoreDuplicates = true
            )
        }
    }

    suspend fun unfollow(peerId: String) {
        NativeSupabase.delete(
            "/rest/v1/followers?follower_id=eq.${q(session.userId)}&following_id=eq.${q(peerId)}",
            session
        )
    }
}

internal class NativeInboxViewModel(private val session: NativeSession) : ViewModel() {
    private val repository = NativeInboxRepository(session)
    var threads by mutableStateOf<List<InboxThread>>(emptyList())
        private set
    var followers by mutableStateOf<List<InboxProfile>>(emptyList())
        private set
    var following by mutableStateOf<List<InboxProfile>>(emptyList())
        private set
    var requests by mutableStateOf<List<InboxFollowRequest>>(emptyList())
        private set
    var messages by mutableStateOf<List<InboxMessage>>(emptyList())
        private set
    var activePeer by mutableStateOf<InboxProfile?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var sending by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set
    var status by mutableStateOf("Connecting to Inbox…")
        private set
    private val realtime = NativeRealtimeMessages(
        session.accessToken, session.userId,
        onChange = { viewModelScope.launch { refreshCurrent() } },
        onStatus = { value -> status = value }
    )

    init {
        realtime.connect()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            error = ""
            try {
                threads = repository.loadThreads()
                followers = repository.loadRelations(true)
                following = repository.loadRelations(false)
                requests = repository.loadFollowRequests()
                status = "Inbox updated"
            } catch (e: Exception) {
                error = e.message ?: "Inbox लोड नहीं हुआ।"
            } finally {
                loading = false
            }
        }
    }

    private suspend fun refreshCurrent() {
        try {
            threads = repository.loadThreads()
            followers = repository.loadRelations(true)
            following = repository.loadRelations(false)
            requests = repository.loadFollowRequests()
            val peer = activePeer
            if (peer != null) {
                val conversation = repository.findOrCreateConversation(peer.id)
                messages = repository.loadMessages(conversation)
                repository.markRead(conversation)
            }
        } catch (e: Exception) {
            error = e.message ?: "Inbox refresh नहीं हुआ।"
        }
    }

    fun openConversation(peer: InboxProfile) {
        if (peer.id == "") return
        viewModelScope.launch {
            loading = true
            error = ""
            try {
                require(!peer.isPrivate) {
                    "यह Creator Account Private है। Private Account पर Direct Message नहीं भेज सकते।"
                }
                activePeer = peer
                val conversation = repository.findOrCreateConversation(peer.id)
                messages = repository.loadMessages(conversation)
                repository.markRead(conversation)
                threads = repository.loadThreads()
            } catch (e: Exception) {
                error = e.message ?: "Conversation नहीं खुली।"
            } finally {
                loading = false
            }
        }
    }

    fun openTarget(targetId: String) {
        if (targetId.isBlank() || targetId == sessionUserId()) return
        viewModelScope.launch {
            try {
                val rows = NativeSupabase.get(
                    "/rest/v1/creator_public_profiles?select=user_id,username,full_name,avatar_url,is_private&user_id=eq.${Uri.encode(targetId)}",
                    session
                )
                val row = rows.optJSONObject(0) ?: throw IllegalStateException("यह Creator उपलब्ध नहीं है।")
                val peer = InboxProfile(
                    row.optString("user_id"),
                    row.stringOrNull("full_name") ?: row.stringOrNull("username") ?: "New Star User",
                    row.stringOrNull("username").orEmpty(),
                    row.stringOrNull("avatar_url").orEmpty(),
                    row.optBoolean("is_private")
                )
                openConversation(peer)
            } catch (e: Exception) {
                error = e.message ?: "Creator नहीं मिला।"
            }
        }
    }

    private fun sessionUserId() = session.userId

    fun closeConversation() {
        activePeer = null
        messages = emptyList()
    }

    fun sendText(body: String) {
        val peer = activePeer ?: return
        viewModelScope.launch {
            sending = true
            error = ""
            try {
                val conversation = repository.findOrCreateConversation(peer.id)
                repository.sendText(peer.id, conversation, body)
                messages = repository.loadMessages(conversation)
                threads = repository.loadThreads()
            } catch (e: Exception) {
                error = e.message ?: "Message नहीं भेजा गया।"
            } finally {
                sending = false
            }
        }
    }

    fun sendAttachment(name: String, bytes: ByteArray, mime: String) {
        val peer = activePeer ?: return
        viewModelScope.launch {
            sending = true
            error = ""
            try {
                val conversation = repository.findOrCreateConversation(peer.id)
                repository.sendAttachment(peer.id, conversation, name, bytes, mime)
                messages = repository.loadMessages(conversation)
                threads = repository.loadThreads()
            } catch (e: Exception) {
                error = e.message ?: "Attachment नहीं भेजा गया।"
            } finally {
                sending = false
            }
        }
    }

    suspend fun signedMediaUrl(path: String): String = repository.signedMediaUrl(path)

    fun respond(request: InboxFollowRequest, accept: Boolean) {
        viewModelScope.launch {
            error = ""
            try {
                repository.respondToFollowRequest(request.id, request.requesterId, accept)
                requests = repository.loadFollowRequests()
                followers = repository.loadRelations(true)
            } catch (e: Exception) {
                error = e.message ?: "Follow request update नहीं हुआ।"
            }
        }
    }

    fun unfollow(profile: InboxProfile) {
        viewModelScope.launch {
            try {
                repository.unfollow(profile.id)
                following = repository.loadRelations(false)
            } catch (e: Exception) {
                error = e.message ?: "Unfollow नहीं हुआ।"
            }
        }
    }

    fun close() {
        realtime.close()
        viewModelScope.cancel()
    }

    override fun onCleared() {
        realtime.close()
        super.onCleared()
    }
}

@Composable
fun NativeInboxScreen(
    session: NativeSession,
    targetCreatorId: String?,
    targetRequestId: Int = 0,
    onOpenCreator: (String) -> Unit = {}
) {
    val vm = remember(session.userId) { NativeInboxViewModel(session) }
    var tab by remember { mutableStateOf("Messages") }
    var draft by remember { mutableStateOf("") }
    var mediaLoading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            mediaLoading = true
            try {
                val mime = NativeInboxAttachmentPolicy.normalizeMimeType(context.contentResolver.getType(uri))
                val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else "attachment"
                } ?: "attachment"
                val safeName = NativeInboxAttachmentPolicy.safeDisplayName(name, mime)
                val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        NativeInboxAttachmentPolicy.readBounded(input)
                    } ?: throw IllegalStateException("Attachment पढ़ी नहीं जा सकी।")
                }
                vm.sendAttachment(safeName, bytes, mime)
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, e.message ?: "Attachment upload असफल।", android.widget.Toast.LENGTH_LONG).show()
            } finally {
                mediaLoading = false
            }
        }
    }

    LaunchedEffect(targetCreatorId, targetRequestId) {
        if (!targetCreatorId.isNullOrBlank()) {
            tab = "Messages"
            vm.openTarget(targetCreatorId)
        }
    }
    DisposableEffect(vm) { onDispose { vm.close() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Inbox", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = { vm.refresh() }, enabled = !vm.loading) { Text("Refresh") }
        }
        Text(vm.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (vm.error.isNotBlank()) {
            Text(vm.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (vm.activePeer == null) {
            ScrollableTabRow(selectedTabIndex = listOf("Messages", "Followers", "Following", "Requests").indexOf(tab)) {
                listOf("Messages", "Followers", "Following", "Requests").forEach { name ->
                    Tab(selected = tab == name, onClick = { tab = name }, text = {
                        val count = when (name) {
                            "Followers" -> vm.followers.size
                            "Following" -> vm.following.size
                            "Requests" -> vm.requests.size
                            else -> 0
                        }
                        Text(if (count > 0) "$name ($count)" else name)
                    })
                }
            }
            if (vm.loading && vm.threads.isEmpty() && vm.followers.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else when (tab) {
                "Messages" -> {
                    if (vm.threads.isEmpty()) EmptyInbox("अभी कोई बातचीत नहीं है। किसी Creator की Profile से Message शुरू करें।")
                    else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(vm.threads, key = { it.peer.id }) { thread ->
                            InboxProfileRow(thread.peer, thread.latest.body.ifBlank { "Media message" },
                                onClick = { vm.openConversation(thread.peer); tab = "Messages" })
                        }
                    }
                }
                "Followers" -> RelationList(vm.followers, false, {}, onOpenCreator)
                "Following" -> RelationList(vm.following, true, vm::unfollow, onOpenCreator)
                else -> {
                    if (vm.requests.isEmpty()) EmptyInbox("अभी कोई Follow Request नहीं है।")
                    else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(vm.requests, key = { it.id }) { request ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                InboxAvatar(request.profile)
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(request.profile.name, fontWeight = FontWeight.SemiBold)
                                    Text("आपको Follow करना चाहते हैं", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { vm.respond(request, true) }) { Text("Accept") }
                                TextButton(onClick = { vm.respond(request, false) }) { Text("Decline") }
                            }
                        }
                    }
                }
            }
        } else {
            val peer = vm.activePeer!!
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.closeConversation(); draft = "" }) { Text("← Inbox") }
                InboxAvatar(peer)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(peer.name, fontWeight = FontWeight.SemiBold)
                    if (peer.username.isNotBlank()) Text("@${peer.username}", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { onOpenCreator(peer.id) }) { Text("Profile") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = false,
                contentPadding = PaddingValues(vertical = 10.dp)) {
                items(vm.messages, key = { it.id }) { message ->
                    val mine = message.senderId == session.userId
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        Surface(
                            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.widthIn(max = 300.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(message.body)
                                if (!message.mediaPath.isNullOrBlank()) {
                                    var signed by remember(message.mediaPath) { mutableStateOf("") }
                                    LaunchedEffect(message.mediaPath) {
                                        try { signed = vm.signedMediaUrl(message.mediaPath) } catch (_: Exception) { signed = "" }
                                    }
                                    if (signed.isNotBlank()) TextButton(onClick = {
                                        try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(signed))) }
                                        catch (_: Exception) { }
                                    }) { Text("Open secure attachment") }
                                    else Text("Secure attachment", style = MaterialTheme.typography.labelSmall)
                                }
                                Text(
                                    try { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date.from(java.time.Instant.parse(message.createdAt))) }
                                    catch (_: Exception) { "" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (mine && message.readAt != null) Text("Seen", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { picker.launch(arrayOf("image/*", "video/*", "audio/*", "application/pdf")) },
                    enabled = !vm.sending && !mediaLoading) { Text("＋") }
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it.take(2000) },
                    modifier = Modifier.weight(1f), placeholder = { Text("Message…") },
                    maxLines = 4, enabled = !vm.sending
                )
                IconButton(onClick = {
                    val text = draft
                    if (text.isNotBlank()) { vm.sendText(text); draft = "" }
                }, enabled = draft.isNotBlank() && !vm.sending) { Text("➤") }
            }
            if (mediaLoading || vm.sending) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun EmptyInbox(message: String) {
    Box(Modifier.fillMaxWidth().heightIn(min = 180.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InboxAvatar(profile: InboxProfile) {
    if (profile.avatar.isNotBlank()) {
        AsyncImage(profile.avatar, contentDescription = profile.name,
            modifier = Modifier.size(44.dp).clip(CircleShape))
    } else {
        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center) {
            Text(profile.name.take(1).uppercase(), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InboxProfileRow(profile: InboxProfile, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        InboxAvatar(profile)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(profile.name, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (profile.username.isNotBlank()) Text("@${profile.username}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RelationList(
    profiles: List<InboxProfile>,
    canUnfollow: Boolean,
    onUnfollow: (InboxProfile) -> Unit,
    onOpenCreator: (String) -> Unit
) {
    if (profiles.isEmpty()) {
        EmptyInbox(if (canUnfollow) "आप अभी किसी Creator को Follow नहीं करते।" else "अभी कोई Follower नहीं है।")
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(profiles, key = { it.id }) { profile ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable { onOpenCreator(profile.id) }, verticalAlignment = Alignment.CenterVertically) {
                    InboxAvatar(profile)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(profile.name, fontWeight = FontWeight.SemiBold)
                        Text(if (profile.username.isNotBlank()) "@${profile.username}" else "Creator",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (canUnfollow) TextButton(onClick = { onUnfollow(profile) }) { Text("Following") }
            }
        }
    }
}
