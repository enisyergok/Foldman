package dev.foldman.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Taps the local player's PCM so the VU needles and lamps follow the actual music. Levels are RMS in
 * 0..1 per channel with VU ballistics: the reading is integrated over about 300 ms (it reaches 99% of
 * a steady tone in that time) and falls back the same way. [read] returns silence once buffers stop
 * arriving (paused, stopped).
 */
@OptIn(UnstableApi::class)
object AudioLevels : TeeAudioProcessor.AudioBufferSink {
    @Volatile private var left = 0f
    @Volatile private var right = 0f
    @Volatile private var updatedAt = 0L
    private var channels = 2
    private var sampleRate = 44100
    private var encoding = C.ENCODING_PCM_16BIT
    private var meanSquareL = 0.0
    private var meanSquareR = 0.0

    /** Time constant of the integration: 99% in 300 ms is about 4.6 time constants. */
    private const val TAU_SECONDS = 0.065

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        channels = channelCount
        sampleRate = sampleRateHz.coerceAtLeast(8000)
        this.encoding = encoding
        meanSquareL = 0.0
        meanSquareR = 0.0
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (channels < 1) return
        val isFloat = encoding == C.ENCODING_PCM_FLOAT
        if (!isFloat && encoding != C.ENCODING_PCM_16BIT) return
        val source = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytesPerSample = if (isFloat) 4 else 2
        val frameBytes = channels * bytesPerSample
        var sumL = 0.0
        var sumR = 0.0
        var frames = 0
        var pos = source.position()
        while (source.limit() - pos >= frameBytes) {
            val l = sample(source, pos, isFloat)
            val r = if (channels > 1) sample(source, pos + bytesPerSample, isFloat) else l
            sumL += l * l
            sumR += r * r
            frames++
            pos += frameBytes
        }
        if (frames == 0) return
        // One-pole smoothing of the mean square, weighted by how much audio was just heard.
        val weight = 1.0 - exp(-frames / (sampleRate * TAU_SECONDS))
        meanSquareL += (sumL / frames - meanSquareL) * weight
        meanSquareR += (sumR / frames - meanSquareR) * weight
        left = sqrt(meanSquareL).toFloat()
        right = sqrt(meanSquareR).toFloat()
        updatedAt = SystemClock.elapsedRealtime()
    }

    private fun sample(b: ByteBuffer, index: Int, isFloat: Boolean): Double =
        if (isFloat) b.getFloat(index).toDouble() else b.getShort(index) / 32768.0

    fun read(): Pair<Float, Float> =
        if (SystemClock.elapsedRealtime() - updatedAt > 250) 0f to 0f else left to right
}
