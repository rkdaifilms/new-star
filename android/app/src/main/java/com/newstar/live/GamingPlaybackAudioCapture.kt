package com.newstar.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures only playback streams that Android allows a MediaProjection app to capture.
 * Apps that opt out, protected audio, and unsupported Android versions remain unavailable.
 */
internal class GamingPlaybackAudioCapture(
    private val projection: MediaProjection,
    private val mixer: GamingPcmMixer,
    private val onError: (String) -> Unit
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var worker: Thread? = null

    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        check(!running.get()) { "Game audio capture पहले से चालू है।" }

        val configuration = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(GamingPcmMixer.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minimumBuffer = AudioRecord.getMinBufferSize(
            GamingPcmMixer.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minimumBuffer > 0) { "Android game audio capture का buffer उपलब्ध नहीं है।" }

        val audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(configuration)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimumBuffer * 2, GamingPcmMixer.SAMPLE_RATE / 5 * 2))
            .build()
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            "Android game audio capture initialize नहीं हो सका।"
        }

        try {
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Android game audio capture शुरू नहीं हुआ।"
            }
            recorder = audioRecord
            running.set(true)
            worker = Thread({ captureLoop(audioRecord) }, "NewStarGameAudioCapture").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            return true
        } catch (error: Exception) {
            running.set(false)
            runCatching { audioRecord.stop() }
            audioRecord.release()
            recorder = null
            throw error
        }
    }

    private fun captureLoop(audioRecord: AudioRecord) {
        val pcm = ShortArray(480)
        try {
            while (running.get()) {
                val count = audioRecord.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    mixer.offer(pcm, count)
                } else if (count < 0 && running.get()) {
                    throw IllegalStateException("Android game audio capture read failed ($count).")
                }
            }
        } catch (error: Exception) {
            if (running.get()) onError(error.message ?: "Game audio capture बंद हो गया।")
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            recorder?.let { runCatching { it.release() } }
            recorder = null
            return
        }
        val activeRecorder = recorder
        recorder = null
        runCatching { activeRecorder?.stop() }
        runCatching { worker?.join(750) }
        worker = null
        runCatching { activeRecorder?.release() }
        mixer.clear()
    }
}
