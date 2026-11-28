package com.newstar.live

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GamingStreamService : Service() {
    private var capturer: ScreenCapturerAndroid? = null
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: org.webrtc.AudioTrack? = null
    private var includeMic = false
    private var texture: SurfaceTextureHelper? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var whipResource: String? = null
    private val stopped = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopStreaming(); return START_NOT_STICKY }
        if (intent?.action == SET_MIC) {
            val requested = intent.getBooleanExtra(MIC_ENABLED, false)
            val track = audioTrack
            val permissionGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (track == null) {
                emit("error", "इस Gaming LIVE session में microphone track उपलब्ध नहीं है।")
            } else if (requested && !permissionGranted) {
                track.setEnabled(false)
                includeMic = false
                emit("error", "Microphone permission उपलब्ध नहीं है। Android Settings में अनुमति देने के बाद नया Gaming LIVE शुरू करें।")
            } else {
                track.setEnabled(requested)
                includeMic = requested
                emit(
                    "connected",
                    if (requested) "Gaming screen और microphone चालू हैं।"
                    else "Gaming screen चालू है; microphone mute है।"
                )
            }
            return START_NOT_STICKY
        }
        if (intent?.action == START) {
            includeMic = intent.getBooleanExtra(INCLUDE_MIC, false) &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                if (includeMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), foregroundTypes)
            Thread {
                try { startStreaming(intent) }
                catch (e: Exception) { emit("error", e.message ?: "Native stream failed"); stopStreaming() }
            }.start()
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(intent: Intent) {
        val resultCode = intent.getIntExtra(RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val permissionData = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(RESULT_DATA, Intent::class.java) else intent.getParcelableExtra(RESULT_DATA)
        val whipUrl = intent.getStringExtra(WHIP_URL) ?: error("Missing WHIP endpoint.")
        require(resultCode == android.app.Activity.RESULT_OK && permissionData != null) { "Screen permission was not granted." }

        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions())
        egl = EglBase.create()
        val factoryBuilder = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl!!.eglBaseContext))
        if (includeMic) {
            audioDeviceModule = JavaAudioDeviceModule.builder(applicationContext).createAudioDeviceModule()
            factoryBuilder.setAudioDeviceModule(audioDeviceModule!!)
        }
        factory = factoryBuilder.createPeerConnectionFactory()

        texture = SurfaceTextureHelper.create("NewStarScreenCapture", egl!!.eglBaseContext)
        source = factory!!.createVideoSource(false)
        capturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
            override fun onStop() { emit("error", "Android screen sharing was stopped."); stopStreaming() }
        })
        capturer!!.initialize(texture!!, applicationContext, source!!.capturerObserver)
        capturer!!.startCapture(1920, 1080, 30)
        track = factory!!.createVideoTrack("newstar-screen", source)
        if (includeMic) {
            audioSource = factory!!.createAudioSource(MediaConstraints())
            audioTrack = factory!!.createAudioTrack("newstar-microphone", audioSource!!)
            audioTrack!!.setEnabled(true)
        }

        val connected = CountDownLatch(1)
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) connected.countDown()
                if (state == PeerConnection.IceConnectionState.FAILED) emit("error", "WebRTC network connection failed.")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        )
        peer = factory!!.createPeerConnection(PeerConnection.RTCConfiguration(iceServers), observer)
            ?: error("Could not create WebRTC connection.")
        peer!!.addTrack(track, listOf("newstar-screen"))
        audioTrack?.let { peer!!.addTrack(it, listOf("newstar-screen")) }

        val offer = createOffer(peer!!)
        setDescription(peer!!, offer, true)
        awaitIceGathering(peer!!)
        val localSdp = peer!!.localDescription?.description ?: error("WebRTC offer is empty.")
        val connection = URL(whipUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("Content-Type", "application/sdp")
        connection.setRequestProperty("Accept", "application/sdp")
        connection.outputStream.use { it.write(localSdp.toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        if (status !in 200..299) {
            val detail = runCatching { connection.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
            error("Cloudflare WHIP rejected the stream (" + status + "): " + detail.take(160))
        }
        val answer = connection.inputStream.bufferedReader().use { it.readText() }
        whipResource = connection.getHeaderField("Location")?.let { URL(URL(whipUrl), it).toString() }
        connection.disconnect()
        require(answer.isNotBlank()) { "Cloudflare returned an empty SDP answer." }
        setDescription(peer!!, SessionDescription(SessionDescription.Type.ANSWER, answer), false)
        emit("connecting", "Cloudflare accepted the screen stream; checking media connection.")
        if (!connected.await(20, TimeUnit.SECONDS)) error("Cloudflare accepted WHIP, but the WebRTC media connection did not become active.")
        emit(
            "connected",
            if (includeMic) "Gaming screen and microphone are connected to Cloudflare."
            else "Gaming screen connected to Cloudflare. Microphone is off.",
            whipResource ?: ""
        )
    }

    private fun createOffer(pc: PeerConnection): SessionDescription {
        val latch = CountDownLatch(1)
        var result: SessionDescription? = null
        var failure: String? = null
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) { result = sdp; latch.countDown() }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { failure = error; latch.countDown() }
            override fun onSetFailure(error: String?) { failure = error; latch.countDown() }
        }, MediaConstraints())
        check(latch.await(15, TimeUnit.SECONDS)) { "Timed out creating WebRTC offer." }
        return result ?: error(failure ?: "Could not create WebRTC offer.")
    }

    private fun setDescription(pc: PeerConnection, sdp: SessionDescription, local: Boolean) {
        val latch = CountDownLatch(1)
        var failure: String? = null
        val callback = object : SdpObserver {
            override fun onSetSuccess() { latch.countDown() }
            override fun onSetFailure(error: String?) { failure = error; latch.countDown() }
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }
        if (local) pc.setLocalDescription(callback, sdp) else pc.setRemoteDescription(callback, sdp)
        check(latch.await(15, TimeUnit.SECONDS)) { "Timed out applying WebRTC session description." }
        check(failure == null) { failure ?: "Could not apply WebRTC session description." }
    }

    private fun awaitIceGathering(pc: PeerConnection) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (pc.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE && System.nanoTime() < deadline) Thread.sleep(100)
        check(pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) { "ICE gathering timed out." }
    }

    private fun stopStreaming() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching {
            whipResource?.let {
                val c = URL(it).openConnection() as HttpURLConnection
                c.requestMethod = "DELETE"; c.connectTimeout = 5000; c.readTimeout = 5000
                c.responseCode; c.disconnect()
            }
        }
        runCatching { capturer?.stopCapture() }
        runCatching { peer?.close() }
        runCatching { track?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { capturer?.dispose() }
        runCatching { source?.dispose() }
        runCatching { texture?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { audioDeviceModule?.release() }
        runCatching { egl?.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Gaming LIVE", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("New Star Gaming LIVE")
            .setContentText("Screen sharing is active")
            .setOngoing(true)
            .build()
    }

    private fun emit(status: String, message: String, resource: String = "") {
        sendBroadcast(Intent(STATUS).setPackage(packageName).putExtra("status", status).putExtra("message", message).putExtra("resource", resource).putExtra("mic_available", audioTrack != null).putExtra("mic_enabled", audioTrack?.enabled() == true))
    }

    override fun onDestroy() { stopStreaming(); super.onDestroy() }

    companion object {
        const val START = "com.newstar.live.START"
        const val STOP = "com.newstar.live.STOP"
        const val SET_MIC = "com.newstar.live.SET_MIC"
        const val MIC_ENABLED = "mic_enabled"
        const val STATUS = "com.newstar.live.STATUS"
        const val RESULT_CODE = "result_code"
        const val RESULT_DATA = "result_data"
        const val WHIP_URL = "whip_url"
        const val INCLUDE_MIC = "include_mic"
        private const val CHANNEL = "newstar_gaming_live"
        private const val NOTIFICATION_ID = 7041
    }
}
