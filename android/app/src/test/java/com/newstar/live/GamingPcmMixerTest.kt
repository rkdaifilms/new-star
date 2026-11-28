package com.newstar.live

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class GamingPcmMixerTest {
    @Test
    fun mixesMicrophoneAndGameSamplesIntoWebRtcBuffer() {
        val mixer = GamingPcmMixer()
        mixer.offer(shortArrayOf(1_000, -1_000), 2)
        val buffer = pcmBuffer(1_000, -1_000)

        mixer.mix(buffer, AudioFormat.ENCODING_PCM_16BIT, 1, GamingPcmMixer.SAMPLE_RATE)

        assertEquals(2_000, buffer.getShort(0).toInt())
        assertEquals(-2_000, buffer.getShort(2).toInt())
    }

    @Test
    fun microphoneMuteKeepsGameAudioInOutgoingBuffer() {
        val mixer = GamingPcmMixer().apply { microphoneEnabled = false }
        mixer.offer(shortArrayOf(3_000, 4_000), 2)
        val buffer = pcmBuffer(20_000, -20_000)

        mixer.mix(buffer, AudioFormat.ENCODING_PCM_16BIT, 1, GamingPcmMixer.SAMPLE_RATE)

        assertEquals(3_000, buffer.getShort(0).toInt())
        assertEquals(4_000, buffer.getShort(2).toInt())
    }

    @Test
    fun gameAudioMuteKeepsMicrophoneInOutgoingBuffer() {
        val mixer = GamingPcmMixer().apply { gameAudioEnabled = false }
        mixer.offer(shortArrayOf(3_000, 4_000), 2)
        val buffer = pcmBuffer(700, -900)

        mixer.mix(buffer, AudioFormat.ENCODING_PCM_16BIT, 1, GamingPcmMixer.SAMPLE_RATE)

        assertEquals(700, buffer.getShort(0).toInt())
        assertEquals(-900, buffer.getShort(2).toInt())
    }

    @Test
    fun clipsMixedPcmToSignedSixteenBitRange() {
        val mixer = GamingPcmMixer()
        mixer.offer(shortArrayOf(10_000, -10_000), 2)
        val buffer = pcmBuffer(30_000, -30_000)

        mixer.mix(buffer, AudioFormat.ENCODING_PCM_16BIT, 1, GamingPcmMixer.SAMPLE_RATE)

        assertEquals(Short.MAX_VALUE.toInt(), buffer.getShort(0).toInt())
        assertEquals(Short.MIN_VALUE.toInt(), buffer.getShort(2).toInt())
    }

    @Test
    fun dropsOldestSamplesWhenRingBufferIsFull() {
        val mixer = GamingPcmMixer(capacitySamples = 3).apply { microphoneEnabled = false }
        mixer.offer(shortArrayOf(1, 2, 3, 4, 5), 5)
        val buffer = pcmBuffer(0, 0, 0)

        mixer.mix(buffer, AudioFormat.ENCODING_PCM_16BIT, 1, GamingPcmMixer.SAMPLE_RATE)

        assertEquals(3, buffer.getShort(0).toInt())
        assertEquals(4, buffer.getShort(2).toInt())
        assertEquals(5, buffer.getShort(4).toInt())
    }

    @Test
    fun ignoresUnexpectedAudioFormatWithoutMutatingBuffer() {
        val mixer = GamingPcmMixer()
        mixer.offer(shortArrayOf(1_000), 1)
        val buffer = pcmBuffer(2_000)

        mixer.mix(buffer, AudioFormat.ENCODING_PCM_FLOAT, 1, GamingPcmMixer.SAMPLE_RATE)

        assertEquals(2_000, buffer.getShort(0).toInt())
    }

    private fun pcmBuffer(vararg values: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }
}
