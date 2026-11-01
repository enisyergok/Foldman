package dev.foldman.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Puts the player's audio through [TapeDsp]. Handles 16-bit and float PCM; any other format makes
 * the processor inactive, so such audio passes through untouched rather than failing.
 */
@OptIn(UnstableApi::class)
class TapeAudioProcessor(private val settings: () -> TapeSettings = { TapeParams.current }) : BaseAudioProcessor() {
    private var dsp: TapeDsp? = null
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val encoding = inputAudioFormat.encoding
        val supported = (encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT) &&
            inputAudioFormat.channelCount in 1..8 && inputAudioFormat.sampleRate >= 8000
        if (!supported) {
            dsp = null
            return AudioFormat.NOT_SET
        }
        dsp = TapeDsp(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val tape = dsp
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        if (tape == null) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        val channels = inputAudioFormat.channelCount
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val frameBytes = channels * if (isFloat) 4 else 2
        val frames = remaining / frameBytes
        val samples = frames * channels
        if (scratch.size < samples) scratch = FloatArray(samples)

        val source = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val start = source.position()
        if (isFloat) {
            for (i in 0 until samples) scratch[i] = source.getFloat(start + i * 4)
        } else {
            for (i in 0 until samples) scratch[i] = source.getShort(start + i * 2) / 32768f
        }

        tape.process(scratch, frames, settings())

        if (isFloat) {
            for (i in 0 until samples) output.putFloat(scratch[i])
        } else {
            for (i in 0 until samples) {
                output.putShort((scratch[i] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        // A trailing partial frame (never expected) is passed through as it is.
        val used = frames * frameBytes
        for (i in used until remaining) output.put(source.get(start + i))
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    /** At the end of the stream the last few milliseconds are still inside the tape's delay line. */
    override fun onQueueEndOfStream() {
        val tape = dsp ?: return
        val channels = inputAudioFormat.channelCount
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val frames = tape.latencyFrames
        val samples = frames * channels
        val tail = FloatArray(samples)
        tape.process(tail, frames, settings())
        val output = replaceOutputBuffer(samples * if (isFloat) 4 else 2).order(ByteOrder.nativeOrder())
        if (isFloat) {
            for (v in tail) output.putFloat(v)
        } else {
            for (v in tail) output.putShort((v * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
        }
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        dsp?.reset()
    }

    override fun onReset() {
        dsp = null
        scratch = FloatArray(0)
    }
}
