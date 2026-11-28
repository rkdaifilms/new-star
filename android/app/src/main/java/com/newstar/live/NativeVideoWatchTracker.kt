package com.newstar.live

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/**
 * Mirrors the web feed's authenticated watch-session lifecycle.
 *
 * A watch session is opened only while ExoPlayer is actually playing. Heartbeats
 * report the current playback position every eight seconds, and pause/end/error
 * closes the session so the database can apply its own qualified-view rules.
 */
internal class NativeVideoWatchTracker(
    private val session: NativeSession?,
    private val videoId: String,
    private val player: ExoPlayer
) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var activeSessionId: String? = null
    private var heartbeatJob: Job? = null
    @Volatile private var closed = false

    init {
        player.addListener(this)
        if (player.isPlaying) startSession()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) startSession() else stopSession()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
            stopSession()
        }
    }

    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        stopSession()
    }

    private fun startSession() {
        val current = session ?: return
        if (closed) return
        scope.launch {
            mutex.withLock {
                if (closed || !player.isPlaying || activeSessionId != null) return@withLock
                try {
                    val response = NativeSupabase.post(
                        "/rest/v1/rpc/start_video_watch_session",
                        JSONObject()
                            .put("p_video_id", videoId)
                            .put("p_client_session_id", UUID.randomUUID().toString()),
                        current
                    )
                    val result = JSONObject(response)
                    val createdId = result.optString("session_id").takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("Watch session ID missing from Supabase response.")
                    if (closed || !player.isPlaying) {
                        finishSession(current, createdId)
                        return@withLock
                    }
                    activeSessionId = createdId
                    heartbeatJob = scope.launch {
                        while (!closed) {
                            delay(8_000)
                            if (!player.isPlaying) continue
                            val id = activeSessionId ?: break
                            try {
                                NativeSupabase.post(
                                    "/rest/v1/rpc/heartbeat_video_watch_session",
                                    JSONObject()
                                        .put("p_session_id", id)
                                        .put("p_position_seconds", player.currentPosition.coerceAtLeast(0L) / 1000.0)
                                        .put("p_is_playing", true),
                                    current
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                // A transient heartbeat failure is retried on the next interval.
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Playback remains available if analytics/watch tracking is unavailable.
                }
            }
        }
    }

    private fun stopSession() {
        if (closed) return
        val current = session ?: return
        scope.launch {
            mutex.withLock {
                stopSessionLocked(current)
            }
        }
    }

    private suspend fun stopSessionLocked(current: NativeSession) {
        heartbeatJob?.cancelAndJoin()
        heartbeatJob = null
        val id = activeSessionId ?: return
        activeSessionId = null
        try {
            finishSession(current, id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The database expires abandoned active sessions after its timeout.
        }
    }

    private suspend fun finishSession(current: NativeSession, id: String) {
        NativeSupabase.post(
            "/rest/v1/rpc/end_video_watch_session",
            JSONObject()
                .put("p_session_id", id)
                .put("p_position_seconds", player.currentPosition.coerceAtLeast(0L) / 1000.0),
            current
        )
    }

    fun close() {
        if (closed) return
        closed = true
        player.removeListener(this)
        val current = session
        scope.launch {
            if (current != null) {
                mutex.withLock {
                    stopSessionLocked(current)
                }
            }
            scope.cancel()
        }
    }
}
