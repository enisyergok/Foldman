package dev.foldman

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Writes small WAV files for the tests to play. */
object TestAudio {
    /** A [seconds]-long tone (a chord of [freq] and its fifth), as 16-bit PCM. */
    fun wav(file: File, seconds: Double, rate: Int = 44100, channels: Int = 2, freq: Double = 440.0, amp: Double = 0.5): File {
        val frames = (seconds * rate).toInt()
        val data = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) {
            val t = f.toDouble() / rate
            val v = amp * 0.6 * (sin(2 * PI * freq * t) + 0.5 * sin(2 * PI * freq * 1.5 * t))
            val s = (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
            repeat(channels) { data.putShort(s) }
        }
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            header.putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
            header.put("data".toByteArray()).putInt(data.capacity())
            out.write(header.array())
            out.write(data.array())
        }
        return file
    }
}
