package com.newstar.live

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class NativeSfuSubscriber private constructor(
    private val roomId: String,
    private val session: NativeSession,
    private val sfuSessionId: String,
    private val factory: PeerConnectionFactory,
    private val egl: EglBase,
    private val peer: PeerConnection,
    private val trackMids: JSONArray
) {
    private val stopped = AtomicBoolean(false)
    private val trackLock = Any()
    @Volatile private var remoteVideo: VideoTrack? = null
    @Volatile private var renderer: SurfaceViewRenderer? = null

    fun attachRenderer(target: SurfaceViewRenderer) {
        check(!stopped.get()) { "SFU viewer बंद हो चुका है।" }
        if (renderer === target) return
        detachRenderer()
        target.init(egl.eglBaseContext, null)
        target.setEnableHardwareScaler(true)
        target.setMirror(false)
        synchronized(trackLock) {
            remoteVideo?.addSink(target)
            renderer = target
        }
    }

    fun detachRenderer() {
        synchronized(trackLock) {
            val old = renderer
            val track = remoteVideo
            if (old != null && track != null) runCatching { track.removeSink(old) }
            renderer = null
        }
    }

    private fun onRemoteTrack(track: VideoTrack) {
        synchronized(trackLock) {
            remoteVideo?.let { previous -> renderer?.let { runCatching { previous.removeSink(it) } } }
            remoteVideo = track
            renderer?.let { track.addSink(it) }
        }
    }

    suspend fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        detachRenderer()
        runCatching {
            if (trackMids.length() > 0) {
                NativeLiveRepository.closeSfuSession(roomId, sfuSessionId, trackMids, session)
            } else {
                NativeLiveRepository.abandonSfuSession(roomId, sfuSessionId, session)
            }
        }
        runCatching { peer.close(); peer.dispose() }
        remoteVideo = null
        runCatching { factory.dispose() }
        runCatching { egl.release() }
    }

    companion object {
        @Volatile private var initialized = false

        private fun initialize(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (!initialized) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                            .createInitializationOptions()
                    )
                    initialized = true
                }
            }
        }

        private suspend fun setDescription(peer: PeerConnection, value: SessionDescription, local: Boolean) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val observer = object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) {}
                    override fun onSetSuccess() {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onCreateFailure(error: String) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                    }
                    override fun onSetFailure(error: String) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                    }
                }
                if (local) peer.setLocalDescription(observer, value) else peer.setRemoteDescription(observer, value)
            }
        }

        private suspend fun createAnswer(peer: PeerConnection): SessionDescription =
            suspendCancellableCoroutine { continuation ->
                peer.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) {
                        if (continuation.isActive) continuation.resume(description)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                    }
                    override fun onSetFailure(error: String) {}
                }, MediaConstraints())
            }

        suspend fun start(
            context: Context,
            roomId: String,
            session: NativeSession
        ): NativeSfuSubscriber = withContext(Dispatchers.IO) {
            require(roomId.isNotBlank()) { "LIVE room ID आवश्यक है।" }
            initialize(context)
            val publications = NativeLiveRepository.listSfuPublications(roomId, session)
            val unique = LinkedHashMap<String, JSONObject>()
            for (index in 0 until publications.length()) {
                val item = publications.optJSONObject(index) ?: continue
                val publisherId = item.optString("session_id")
                val trackName = item.optString("track_name")
                if (publisherId.isNotBlank() && trackName in setOf("camera", "microphone")) {
                    unique["$publisherId:$trackName"] = item
                }
            }
            val requested = JSONArray()
            unique.values.forEach { item ->
                requested.put(JSONObject()
                    .put("location", "remote")
                    .put("sessionId", item.getString("session_id"))
                    .put("trackName", item.getString("track_name")))
            }
            if (requested.length() == 0) {
                throw IllegalStateException("इस LIVE room में अभी कोई SFU camera या microphone publication उपलब्ध नहीं है।")
            }

            val sfuSessionId = NativeLiveRepository.createSfuSession(roomId, "subscriber", session)
            var factory: PeerConnectionFactory? = null
            var egl: EglBase? = null
            var peer: PeerConnection? = null
            try {
                egl = EglBase.create()
                factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(org.webrtc.DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                    .setVideoDecoderFactory(org.webrtc.DefaultVideoDecoderFactory(egl.eglBaseContext))
                    .createPeerConnectionFactory()
                val gathered = CompletableDeferred<Unit>()
                val connected = CompletableDeferred<Unit>()
                val remoteVideo = java.util.concurrent.atomic.AtomicReference<VideoTrack?>(null)
                val remoteTrackConsumer = java.util.concurrent.atomic.AtomicReference<((VideoTrack) -> Unit)?>(null)
                val createdPeer = factory.createPeerConnection(
                    PeerConnection.RTCConfiguration(
                        listOf(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
                    ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN },
                    object : PeerConnection.Observer {
                        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                            when (state) {
                                PeerConnection.IceConnectionState.CONNECTED,
                                PeerConnection.IceConnectionState.COMPLETED -> if (!connected.isCompleted) connected.complete(Unit)
                                PeerConnection.IceConnectionState.FAILED -> if (!connected.isCompleted) {
                                    connected.completeExceptionally(IllegalStateException("SFU viewer ICE connection failed."))
                                }
                                else -> Unit
                            }
                        }
                        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                            if (state == PeerConnection.IceGatheringState.COMPLETE && !gathered.isCompleted) gathered.complete(Unit)
                        }
                        override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {}
                        override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) {}
                        override fun onAddStream(stream: org.webrtc.MediaStream) {}
                        override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
                        override fun onDataChannel(channel: org.webrtc.DataChannel) {}
                        override fun onRenegotiationNeeded() {}
                        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {
                            (receiver.track() as? VideoTrack)?.let { track -> remoteVideo.set(track); remoteTrackConsumer.get()?.invoke(track) }
                        }
                        override fun onTrack(transceiver: RtpTransceiver) {
                            (transceiver.receiver.track() as? VideoTrack)?.let { track -> remoteVideo.set(track); remoteTrackConsumer.get()?.invoke(track) }
                        }
                        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {}
                        override fun onSelectedCandidatePairChanged(event: PeerConnection.CandidatePairChangeEvent) {}
                    }
                ) ?: throw IllegalStateException("SFU subscriber PeerConnection create नहीं हुआ।")
                peer = createdPeer

                val response = NativeLiveRepository.subscribeSfuTracks(roomId, sfuSessionId, requested, session)
                val offer = response.optJSONObject("sessionDescription")
                    ?: throw IllegalStateException("SFU ने subscriber offer नहीं लौटाया।")
                val offerSdp = offer.optString("sdp")
                if (offer.optString("type") != "offer" || offerSdp.isBlank()) {
                    throw IllegalStateException("SFU subscriber offer अमान्य है।")
                }
                setDescription(createdPeer, SessionDescription(SessionDescription.Type.OFFER, offerSdp), false)
                val answer = createAnswer(createdPeer)
                setDescription(createdPeer, answer, true)
                withTimeout(25000) { gathered.await() }
                val localAnswer = createdPeer.localDescription
                    ?: throw IllegalStateException("SFU subscriber SDP answer उपलब्ध नहीं है।")
                NativeLiveRepository.renegotiateSfuSession(roomId, sfuSessionId, localAnswer.description, session)
                withTimeout(30000) { connected.await() }
                val mids = JSONArray()
                val returnedTracks = response.optJSONArray("tracks") ?: JSONArray()
                for (index in 0 until returnedTracks.length()) {
                    val mid = returnedTracks.optJSONObject(index)?.optString("mid").orEmpty()
                    if (mid.matches(Regex("\\d+"))) mids.put(JSONObject().put("mid", mid))
                }
                return@withContext NativeSfuSubscriber(
                    roomId, session, sfuSessionId, factory, egl, createdPeer, mids
                ).also { subscriber ->
                    remoteTrackConsumer.set(subscriber::onRemoteTrack)
                    remoteVideo.get()?.let { subscriber.onRemoteTrack(it) }
                }
            } catch (error: Exception) {
                runCatching { peer?.close(); peer?.dispose() }
                runCatching { factory?.dispose() }
                runCatching { egl?.release() }
                runCatching { NativeLiveRepository.abandonSfuSession(roomId, sfuSessionId, session) }
                Log.e("NewStarSFU", "Subscriber setup failed", error)
                throw error
            }
        }
    }
}
