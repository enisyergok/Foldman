package dev.foldman.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class TapeDspTest {
    private val fs = 48000

    private fun sine(freq: Double, amp: Double, frames: Int, channels: Int = 2, from: Int = 0): FloatArray {
        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            val v = (amp * sin(2.0 * PI * freq * (from + f) / fs)).toFloat()
            for (c in 0 until channels) out[f * channels + c] = v
        }
        return out
    }

    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
        var sum = 0.0
        for (i in from until to) sum += a[i].toDouble() * a[i]
        return sqrt(sum / (to - from))
    }

    private fun db(x: Double) = 20.0 * log10(x)

    /** Runs [input] through in blocks, the way the player feeds it. */
    private fun run(dsp: TapeDsp, input: FloatArray, s: TapeSettings, channels: Int = 2, block: Int = 1024): FloatArray {
        val out = input.copyOf()
        var f = 0
        val frames = input.size / channels
        while (f < frames) {
            val n = minOf(block, frames - f)
            val chunk = out.copyOfRange(f * channels, (f + n) * channels)
            dsp.process(chunk, n, s)
            chunk.copyInto(out, f * channels)
            f += n
        }
        return out
    }

    @Test
    fun neutralSettingsOnlyDelayTheAudio() {
        val dsp = TapeDsp(fs, 2)
        val input = sine(440.0, 0.5, 20000)
        val out = run(dsp, input, TapeSettings())
        val d = dsp.latencyFrames
        for (f in d until 20000) {
            assertEquals("frame $f", input[(f - d) * 2], out[f * 2], 0f)
            assertEquals("frame $f", input[(f - d) * 2 + 1], out[f * 2 + 1], 0f)
        }
    }

    @Test
    fun neutralIsDetected() {
        assertTrue(TapeSettings().isNeutral)
        assertFalse(TapeSettings(hiss = 0.1f).isNeutral)
        assertFalse(TapeSettings(type = 1).isNeutral)
        assertFalse(TapeSettings(gainDb = -3f).isNeutral)
    }

    @Test
    fun replayGainScalesTheLevelExactly() {
        val dsp = TapeDsp(fs, 2)
        val input = sine(1000.0, 0.4, 30000)
        val out = run(dsp, input, TapeSettings(gainDb = -6f))
        // After the smoothing has settled
        val change = db(rms(out, 40000, 59000)) - db(rms(input, 40000, 59000))
        assertEquals(-6.0, change, 0.1)
    }

    @Test
    fun hissIsQuietAndOnlyWhenAsked() {
        val silence = FloatArray(48000 * 2)
        val clean = run(TapeDsp(fs, 2), silence, TapeSettings())
        assertEquals(0.0, rms(clean), 0.0)

        val hissy = run(TapeDsp(fs, 2), silence, TapeSettings(hiss = 1f))
        val level = db(rms(hissy, 20000, hissy.size))
        assertTrue("hiss at full should sit around -62 dBFS, was $level", level in -70.0..-54.0)
    }

    @Test
    fun metalTapeHissesLessThanNormal() {
        val silence = FloatArray(48000 * 2)
        val normal = rms(run(TapeDsp(fs, 2), silence, TapeSettings(hiss = 1f, type = 0)), 20000, silence.size)
        val metal = rms(run(TapeDsp(fs, 2), silence, TapeSettings(hiss = 1f, type = 2)), 20000, silence.size)
        assertTrue(metal < normal)
    }

    @Test
    fun noiseReductionTakesTheHissDown() {
        val silence = FloatArray(48000 * 2)
        val without = rms(run(TapeDsp(fs, 2), silence, TapeSettings(hiss = 1f)), 30000, silence.size)
        val with = rms(run(TapeDsp(fs, 2), silence, TapeSettings(hiss = 1f, noiseReduction = true)), 30000, silence.size)
        val reduction = db(without) - db(with)
        assertTrue("NR should lower hiss by at least 8 dB, did $reduction", reduction > 8.0)
    }

    @Test
    fun noiseReductionLeavesLoudTrebleAlone() {
        val input = sine(6000.0, 0.3, 48000)
        val plain = run(TapeDsp(fs, 2), input, TapeSettings())
        val reduced = run(TapeDsp(fs, 2), input, TapeSettings(noiseReduction = true))
        val change = db(rms(reduced, 20000, reduced.size)) - db(rms(plain, 20000, plain.size))
        assertEquals(0.0, change, 0.5)
    }

    @Test
    fun saturationCompressesPeaksButNotQuietSignals() {
        val quiet = sine(500.0, 0.02, 30000)
        val loud = sine(500.0, 0.6, 30000)
        val s = TapeSettings(warmth = 1f)
        val quietChange = db(rms(run(TapeDsp(fs, 2), quiet, s), 20000, quiet.size)) - db(rms(quiet, 20000, quiet.size))
        val loudChange = db(rms(run(TapeDsp(fs, 2), loud, s), 20000, loud.size)) - db(rms(loud, 20000, loud.size))
        assertEquals(0.0, quietChange, 0.3)
        assertTrue("loud signal should be squashed, changed by $loudChange", loudChange < -1.0)
    }

    @Test
    fun wowAndFlutterBendThePitchWithinTheirDepth() {
        // Count zero crossings per block: pitch is proportional to them.
        val tone = sine(1000.0, 0.5, 48000 * 4, channels = 1)
        val out = run(TapeDsp(fs, 1), tone, TapeSettings(wow = 1f), channels = 1)
        val perHalfSecond = (12000 until out.size - 12000 step 24000).map { start ->
            var crossings = 0
            for (i in start + 1 until start + 24000) if ((out[i - 1] < 0f) != (out[i] < 0f)) crossings++
            crossings
        }
        val nominal = 1000.0 * 2 * 0.5 // 1 kHz, two crossings per cycle, half a second
        val spread = perHalfSecond.maxOf { abs(it - nominal) } / nominal
        assertTrue("pitch should wander a little, spread was $spread", spread > 0.0003)
        assertTrue("pitch must stay within a percent, spread was $spread", spread < 0.01)
    }

    @Test
    fun nothingIsNaNOrOutOfRangeAtFullTilt() {
        val rnd = java.util.Random(7)
        val input = FloatArray(48000 * 2) { (rnd.nextGaussian() * 0.8).toFloat() }
        val out = run(TapeDsp(fs, 2), input, TapeSettings(1, true, 1f, 1f, 1f, 1f, 12f))
        for (v in out) {
            assertFalse(v.isNaN())
            assertTrue("sample $v out of range", abs(v) <= 1.0f)
        }
    }

    @Test
    fun theSoftLimiterIsLinearBelowTheKneeAndNeverClips() {
        assertEquals(0.5f, TapeDsp.softLimit(0.5f), 0f)
        assertEquals(-0.9f, TapeDsp.softLimit(-0.9f), 0f)
        for (x in listOf(0.95f, 1.2f, 4f, 100f)) {
            val y = TapeDsp.softLimit(x)
            assertTrue(y in 0.9f..1.0f)
            assertEquals(-y, TapeDsp.softLimit(-x), 0f)
        }
    }

    @Test
    fun theTapeTypeChangesTheTreble() {
        val treble = sine(10000.0, 0.1, 30000)
        val normal = rms(run(TapeDsp(fs, 2), treble, TapeSettings(type = 0)), 20000, treble.size)
        val cro2 = rms(run(TapeDsp(fs, 2), treble, TapeSettings(type = 1)), 20000, treble.size)
        assertTrue("CrO2 should be brighter", db(cro2) - db(normal) > 0.8)
    }

    @Test
    fun headBumpLiftsTheLowEnd() {
        val bass = sine(80.0, 0.2, 48000)
        val flat = rms(run(TapeDsp(fs, 2), bass, TapeSettings()), 20000, bass.size)
        val bumped = rms(run(TapeDsp(fs, 2), bass, TapeSettings(bump = 1f)), 20000, bass.size)
        val lift = db(bumped) - db(flat)
        assertTrue("bump should lift 80 Hz by about 3 dB, was $lift", lift in 1.5..4.0)
    }

    @Test
    fun resetForgetsTheAudio() {
        val dsp = TapeDsp(fs, 2)
        run(dsp, sine(440.0, 0.5, 5000), TapeSettings())
        dsp.reset()
        val out = run(dsp, FloatArray(2000), TapeSettings())
        assertEquals(0.0, rms(out), 0.0)
    }

    @Test
    fun worksAtOtherSampleRatesAndChannelCounts() {
        for ((rate, channels) in listOf(8000 to 1, 22050 to 2, 44100 to 6, 96000 to 2, 192000 to 2)) {
            val dsp = TapeDsp(rate, channels)
            val buf = FloatArray(rate * channels / 4) { ((it % 100) / 100f - 0.5f) }
            dsp.process(buf, buf.size / channels, TapeSettings(1, true, 1f, 1f, 1f, 1f, 3f))
            for (v in buf) assertFalse("rate $rate ch $channels", v.isNaN())
        }
    }
}
