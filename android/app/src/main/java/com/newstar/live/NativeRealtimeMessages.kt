package com.newstar.live

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Supabase Realtime subscription for private Inbox messages, incoming follow requests, and user notifications.
 * The server still applies the table's RLS policies to each subscribed row.
 */
class NativeRealtimeMessages(
    private val accessToken: String,
    private val userId: String,
    private val onChange: () -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var joined = false
    private val heartbeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val closed = AtomicBoolean(false)
    private val topic = "realtime:public:messages"
    private val joinRef = "1"

    fun connect() {
        if (closed.get()) return
        heartbeatExecutor.scheduleAtFixedRate({
            heartbeat()
            if (!closed.get() && !joined && socket == null) openSocket()
        }, 5, 5, TimeUnit.SECONDS)
        openSocket()
    }

    @Synchronized
    private fun openSocket() {
        if (closed.get() || joined || socket != null) return
        val base = SUPABASE_URL.removePrefix("https://").removePrefix("http://").trimEnd('/')
        val apiKey = URLEncoder.encode(SUPABASE_KEY, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("wss://$base/realtime/v1/websocket?apikey=$apiKey&vsn=1.0.0")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val changes = JSONArray()
                    .put(
                        JSONObject()
                            .put("event", "*")
                            .put("schema", "public")
                            .put("table", "messages")
                    )
                    .put(
                        JSONObject()
                            .put("event", "*")
                            .put("schema", "public")
                            .put("table", "follow_requests")
                            .put("filter", "target_user_id=eq.$userId")
                    )
                    .put(
                        JSONObject()
                            .put("event", "*")
                            .put("schema", "public")
                            .put("table", "notifications")
                            .put("filter", "user_id=eq.$userId")
                    )
                val config = JSONObject()
                    .put("broadcast", JSONObject().put("ack", false).put("self", false))
                    .put("presence", JSONObject().put("key", ""))
                    .put("postgres_changes", changes)
                val payload = JSONObject()
                    .put("config", config)
                    .put("access_token", accessToken)
                val join = JSONObject()
                    .put("topic", topic)
                    .put("event", "phx_join")
                    .put("payload", payload)
                    .put("ref", joinRef)
                    .put("join_ref", joinRef)
                webSocket.send(join.toString())
                onStatus("Realtime Inbox कनेक्ट हो रहा है…")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val packet = JSONObject(text)
                    val event = packet.optString("event")
                    val payload = packet.optJSONObject("payload") ?: JSONObject()
                    if (event == "phx_reply") {
                        val status = payload.optString("status")
                        if (status == "ok") {
                            joined = true
                            onStatus("Realtime Inbox connected")
                        } else if (status == "error") {
                            joined = false
                            socket = null
                            onStatus("Realtime subscription असफल: ${payload.optJSONObject("response")?.toString()?.take(160).orEmpty()}")
                        }
                    } else if (event == "postgres_changes") {
                        onChange()
                    } else if (event == "system") {
                        onStatus(payload.optString("message").ifBlank { "Realtime connection सूचना मिली।" })
                    }
                } catch (_: Exception) {
                    // Ignore heartbeat replies and malformed protocol frames.
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                joined = false
                if (socket === webSocket) socket = null
                onStatus("Realtime connection बंद हो रहा है…")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                joined = false
                if (socket === webSocket) socket = null
                onStatus("Realtime disconnected")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                joined = false
                if (socket === webSocket) socket = null
                onStatus("Realtime unavailable: ${t.message ?: "network error"}")
            }
        })
    }

    fun heartbeat() {
        if (!joined) return
        socket?.send(
            JSONObject()
                .put("topic", "phoenix")
                .put("event", "heartbeat")
                .put("payload", JSONObject())
                .put("ref", System.nanoTime().toString())
                .toString()
        )
    }

    fun close() {
        closed.set(true)
        joined = false
        socket?.close(1000, "Inbox screen closed")
        socket = null
        heartbeatExecutor.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
