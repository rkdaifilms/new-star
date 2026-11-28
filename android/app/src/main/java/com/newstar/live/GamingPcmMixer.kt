package com.newstar.live

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Single-producer/single-consumer PCM mixer. The playback-capture thread writes game
 * samples while WebRTC's audio-record callback mixes them into the outgoing mic buffer.
 */
internal class GamingPcmMixer(
    capacitySamples: Int = 48_000
) {
    private val samples = ShortArray(capacitySamples.also { require(it > 0) })
    private var readIndex = 0
    private var writeIndex = 0
    private var size = 0

    @Volatile var microphoneEnabled: Boolean = true
    @Volatile var gameAudioEnabled: Boolean = true
    @Volatile var microphoneGain: Float = 1f
        set(value) { field = value.coerceIn(0f, 2f) }
    @Volatile var gameAudioGain: Float = 1f
        set(value) { field = value.coerceIn(0f, 2f) }

    @Synchronized
    fun offer(input: ShortArray, count: Int) {
        val safeCount = count.coerceIn(0, input.size)
        for (index in 0 until safeCount) {
            if (size == samples.size) {
                readIndex = (readIndex + 1) % samples.size
                size--
            }
            samples[writeIndex] = input[index]
            writeIndex = (writeIndex + 1) % samples.size
            size++
        }
    }

    @Synchronized
    fun mix(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int
    ) {
        if (audioFormat != AudioFormat.ENCODING_PCM_16BIT ||
            channelCount != 1 ||
            sampleRate != SAMPLE_RATE
        ) return

        val originalOrder = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val start = buffer.position()
        val sampleCount = buffer.remaining() / BYTES_PER_SAMPLE
        for (index in 0 until sampleCount) {
            val byteIndex = start + index * BYTES_PER_SAMPLE
            val microphone = if (microphoneEnabled) {
                buffer.getShort(byteIndex).toFloat() * microphoneGain
            } else 0f
            val game = if (gameAudioEnabled && size > 0) {
                val value = samples[readIndex].toFloat()
                readIndex = (readIndex + 1) % samples.size
                size--
                value * gameAudioGain
            } else 0f
            val mixed = (microphone + game).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer.putShort(byteIndex, mixed.toShort())
        }
        buffer.order(originalOrder)
    }

    @Synchronized
    fun clear() {
        readIndex = 0
        writeIndex = 0
        size = 0
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val BYTES_PER_SAMPLE = 2
    }
}
