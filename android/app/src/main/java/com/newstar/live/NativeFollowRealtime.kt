package com.newstar.live

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Supabase Realtime subscription for a creator's follower graph and the
 * signed-in viewer's pending follow requests. Changes are signals only:
 * the screen reloads authoritative rows through PostgREST.
 */
internal class NativeFollowRealtime private constructor(
    private val session: NativeSession,
    private val creatorId: String,
    private val onChange: () -> Unit,
    private val onStatus: (String) -> Unit
) : Closeable {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val closed = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)
    private val ref = AtomicInteger(1)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var retrySeconds = 2L

    fun start() {
        connect()
    }

    private fun subscriptions(): JSONArray {
        val entries = JSONArray()
        fun add(table: String, filter: String) {
            entries.put(JSONObject()
                .put("event", "*")
                .put("schema", "public")
                .put("table", table)
                .put("filter", filter))
        }
        val target = Uri.encode(creatorId)
        val viewer = Uri.encode(session.userId)
        add("followers", "following_id=eq.$target")
        add("followers", "follower_id=eq.$target")
        add("follow_requests", "target_user_id=eq.$target")
        add("follow_requests", "requester_id=eq.$viewer")
        return entries
    }

    private fun connect() {
        if (closed.get()) return
        onStatus("Connecting")
        val url = "$SUPABASE_URL/realtime/v1/websocket?apikey=${Uri.encode(SUPABASE_KEY)}&vsn=1.0.0"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${session.accessToken}")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retrySeconds = 2L
                reconnecting.set(false)
                val joinRef = ref.getAndIncrement().toString()
                val payload = JSONObject()
                    .put("config", JSONObject()
                        .put("broadcast", JSONObject().put("ack", false).put("self", false))
                        .put("presence", JSONObject().put("key", ""))
                        .put("postgres_changes", subscriptions()))
                    .put("access_token", session.accessToken)
                val joined = JSONObject()
                    .put("topic", "realtime:creator-follow-$creatorId")
                    .put("event", "phx_join")
                    .put("payload", payload)
                    .put("ref", joinRef)
                    .put("join_ref", joinRef)
                webSocket.send(joined.toString())
                onStatus("Connected")
                scheduler.scheduleAtFixedRate({
                    if (!closed.get()) {
                        val heartbeatRef = ref.getAndIncrement().toString()
                        webSocket.send(JSONObject()
                            .put("topic", "phoenix")
                            .put("event", "heartbeat")
                            .put("payload", JSONObject())
                            .put("ref", heartbeatRef)
                            .toString())
                    }
                }, 20, 25, TimeUnit.SECONDS)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    when (message.optString("event")) {
                        "phx_reply" -> {
                            val status = message.optJSONObject("payload")?.optString("status")
                            if (status == "error") {
                                onStatus("Subscription error")
                                scheduleReconnect()
                            }
                        }
                        "postgres_changes" -> onChange()
                        "system" -> {
                            val messageText = message.optJSONObject("payload")?.optString("message").orEmpty()
                            if (messageText.contains("error", true)) onStatus("Realtime warning")
                        }
                    }
                } catch (_: Exception) {
                    onStatus("Realtime message error")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                onStatus("Disconnected")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                if (!closed.get()) {
                    onStatus("Disconnected")
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (closed.get() || !reconnecting.compareAndSet(false, true)) return
        val wait = retrySeconds.coerceAtMost(30)
        retrySeconds = (retrySeconds * 2).coerceAtMost(30)
        scheduler.schedule({
            reconnecting.set(false)
            connect()
        }, wait, TimeUnit.SECONDS)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.close(1000, "Creator profile closed")
        socket = null
        scheduler.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        onStatus("Stopped")
    }

    companion object {
        fun observe(
            session: NativeSession,
            creatorId: String,
            onChange: () -> Unit,
            onStatus: (String) -> Unit = {}
        ): NativeFollowRealtime = NativeFollowRealtime(session, creatorId, onChange, onStatus)
    }
}
