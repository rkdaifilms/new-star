package com.newstar.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CameraLiveService : Service() {
    private val stopped = AtomicBoolean(false)
    private var capturer: CameraVideoCapturer? = null
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: org.webrtc.AudioTrack? = null
    private var texture: SurfaceTextureHelper? = null
    private var adm: JavaAudioDeviceModule? = null
    private var whipResource: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopStream(); return START_NOT_STICKY }
        if (intent?.action != START) return START_NOT_STICKY
        ServiceCompat.startForeground(this, 7042, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        Thread {
            try { startStream(intent) }
            catch (e: Exception) { emit("error", e.message ?: "Camera LIVE failed"); stopStream() }
        }.start()
        return START_NOT_STICKY
    }

    private fun startStream(intent: Intent) {
        val whip = intent.getStringExtra(WHIP_URL) ?: error("Missing Cloudflare WHIP URL.")
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions())
        egl = EglBase.create()
        adm = JavaAudioDeviceModule.builder(applicationContext).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm!!)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl!!.eglBaseContext))
            .createPeerConnectionFactory()
        val enumerator = Camera2Enumerator(this)
        val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: error("No camera was found on this device.")
        capturer = enumerator.createCapturer(cameraName, null) ?: error("Could not open the camera.")
        texture = SurfaceTextureHelper.create("NewStarCameraCapture", egl!!.eglBaseContext)
        videoSource = factory!!.createVideoSource(false)
        capturer!!.initialize(texture, this, videoSource!!.capturerObserver)
        capturer!!.startCapture(1280, 720, 30)
        videoTrack = factory!!.createVideoTrack("newstar-camera", videoSource!!)
        audioSource = factory!!.createAudioSource(MediaConstraints())
        audioTrack = factory!!.createAudioTrack("newstar-microphone", audioSource!!)
        val connected = CountDownLatch(1)
        var iceFailure: String? = null
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) connected.countDown()
                if (state == PeerConnection.IceConnectionState.FAILED) { iceFailure = "WebRTC connection failed. Try a different network."; connected.countDown() }
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
        peer = factory!!.createPeerConnection(
            PeerConnection.RTCConfiguration(listOf(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())),
            observer
        ) ?: error("Could not create WebRTC peer.")
        peer!!.addTrack(videoTrack, listOf("newstar-live"))
        peer!!.addTrack(audioTrack, listOf("newstar-live"))
        val offer = createOffer(peer!!)
        setDescription(peer!!, offer, true)
        awaitIce(peer!!)
        val sdp = peer!!.localDescription?.description ?: error("Camera LIVE offer is empty.")
        val conn = URL(whip).openConnection() as HttpURLConnection
        val answer: String
        try {
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.connectTimeout = 15000; conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/sdp")
            conn.setRequestProperty("Accept", "application/sdp")
            conn.outputStream.use { it.write(sdp.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status !in 200..299) error("Cloudflare rejected camera LIVE ($status).")
            answer = conn.inputStream.bufferedReader().use { it.readText() }
            whipResource = conn.getHeaderField("Location")?.let { URL(URL(whip), it).toString() }
        } finally { conn.disconnect() }
        require(answer.isNotBlank()) { "Cloudflare returned an empty LIVE answer." }
        setDescription(peer!!, SessionDescription(SessionDescription.Type.ANSWER, answer), false)
        emit("connecting", "Cloudflare accepted camera and microphone. Connecting media…")
        if (!connected.await(20, TimeUnit.SECONDS)) error("Camera LIVE did not connect within 20 seconds.")
        check(iceFailure == null) { iceFailure ?: "WebRTC connection failed." }
        emit("connected", "Camera and microphone are LIVE on Cloudflare.", whipResource ?: "")
    }

    private fun createOffer(pc: PeerConnection): SessionDescription {
        val latch = CountDownLatch(1); var result: SessionDescription? = null; var failure: String? = null
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) { result = sdp; latch.countDown() }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { failure = error; latch.countDown() }
            override fun onSetFailure(error: String?) { failure = error; latch.countDown() }
        }, MediaConstraints())
        check(latch.await(15, TimeUnit.SECONDS)) { "Timed out creating LIVE offer." }
        return result ?: error(failure ?: "Could not create LIVE offer.")
    }

    private fun setDescription(pc: PeerConnection, sdp: SessionDescription, local: Boolean) {
        val latch = CountDownLatch(1); var failure: String? = null
        val cb = object : SdpObserver {
            override fun onSetSuccess() { latch.countDown() }
            override fun onSetFailure(error: String?) { failure = error; latch.countDown() }
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }
        if (local) pc.setLocalDescription(cb, sdp) else pc.setRemoteDescription(cb, sdp)
        check(latch.await(15, TimeUnit.SECONDS)) { "Timed out applying LIVE session description." }
        check(failure == null) { failure ?: "Could not apply LIVE session description." }
    }

    private fun awaitIce(pc: PeerConnection) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (pc.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE && System.nanoTime() < deadline) Thread.sleep(100)
        check(pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) { "LIVE ICE gathering timed out." }
    }

    private fun stopStream() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { whipResource?.let { val c = URL(it).openConnection() as HttpURLConnection; c.requestMethod = "DELETE"; c.connectTimeout = 5000; c.readTimeout = 5000; c.responseCode; c.disconnect() } }
        runCatching { capturer?.stopCapture() }; runCatching { peer?.close() }
        runCatching { videoTrack?.dispose() }; runCatching { audioTrack?.dispose() }
        runCatching { audioSource?.dispose() }; runCatching { capturer?.dispose() }
        runCatching { videoSource?.dispose() }; runCatching { texture?.dispose() }
        runCatching { factory?.dispose() }; runCatching { adm?.release() }; runCatching { egl?.release() }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun notification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("newstar_camera_live", "New Star LIVE", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, "newstar_camera_live").setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("New Star LIVE").setContentText("Camera and microphone streaming is active").setOngoing(true).build()
    }

    private fun emit(status: String, message: String, resource: String = "") {
        sendBroadcast(Intent(STATUS).setPackage(packageName).putExtra("status", status).putExtra("message", message).putExtra("resource", resource))
    }
    override fun onDestroy() { stopStream(); super.onDestroy() }

    companion object {
        const val START = "com.newstar.live.CAMERA_START"
        const val STOP = "com.newstar.live.CAMERA_STOP"
        const val STATUS = "com.newstar.live.CAMERA_STATUS"
        const val WHIP_URL = "whip_url"
    }
}
