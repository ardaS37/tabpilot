package com.ardas.tabletcontroller

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/** Low-latency two-operator FM synthesizer. */
class ThereminEngine {
    @Volatile private var frequency = 220.0
    @Volatile private var amplitude = 0.0
    @Volatile private var modulationIndex = 1.0
    @Volatile private var modulationRatio = 2.0
    @Volatile private var running = true
    private val sampleRate = 44_100
    private val track: AudioTrack
    private val worker: Thread

    init {
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(minimum * 3)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        worker = thread(name = "theremin-audio", isDaemon = true) {
            val samples = ShortArray(512)
            var carrierPhase = 0.0
            var modulatorPhase = 0.0
            var smoothHz = frequency
            var smoothGain = amplitude
            var smoothIndex = modulationIndex
            var smoothRatio = modulationRatio
            while (running) {
                // Gentle smoothing makes every coordinate and pressure change musical,
                // instead of producing zipper noise or clicks.
                smoothHz += (frequency.coerceIn(55.0, 4_000.0) - smoothHz) * .08
                smoothGain += (amplitude.coerceIn(0.0, .85) - smoothGain) * .12
                smoothIndex += (modulationIndex.coerceIn(0.0, 14.0) - smoothIndex) * .08
                smoothRatio += (modulationRatio.coerceIn(.125, 8.0) - smoothRatio) * .08
                val carrierStep = 2.0 * PI * smoothHz / sampleRate
                val modulatorStep = carrierStep * smoothRatio
                for (i in samples.indices) {
                    val fm = sin(carrierPhase + smoothIndex * sin(modulatorPhase))
                    // A tiny soft saturation keeps strong modulation pleasant on tablet speakers.
                    val shaped = fm / (1.0 + .18 * kotlin.math.abs(fm))
                    samples[i] = (shaped * smoothGain * Short.MAX_VALUE).toInt().toShort()
                    carrierPhase = (carrierPhase + carrierStep) % (2.0 * PI)
                    modulatorPhase = (modulatorPhase + modulatorStep) % (2.0 * PI)
                }
                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            }
        }
    }

    fun setTone(hz: Double, volume: Double, index: Double, ratio: Double) {
        frequency = hz; amplitude = volume; modulationIndex = index; modulationRatio = ratio
    }
    fun silence() { amplitude = 0.0 }
    fun close() { running = false; worker.interrupt(); track.pause(); track.flush(); track.release() }
}
