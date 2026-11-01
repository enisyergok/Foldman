package dev.foldman.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The tape: a little signal chain that turns clean digital audio into audio that has been through a
 * cassette. Plain Kotlin on interleaved floats (−1..1), so it can be tested without Android.
 *
 * Order of the chain, per channel:
 *  1. wow and flutter: a short delay whose length wanders, which bends the pitch the way an uneven
 *     tape speed does;
 *  2. saturation: the soft knee of magnetic tape;
 *  3. hiss, shaped towards the treble;
 *  4. the tape type's curve and the low-end head bump (two shelving filters);
 *  5. the NR expander, which takes the treble down when it is quiet enough to be hiss;
 *  6. level (ReplayGain) and a soft limiter so nothing clips.
 *
 * With neutral settings the output is the input, delayed by [latencyFrames] and otherwise bit for bit
 * the same. Every control is smoothed, so moving one never clicks.
 */
class TapeDsp(private val sampleRate: Int, private val channels: Int, seed: Int = 0x5EED1234) {
    init {
        require(sampleRate >= 8000) { "sample rate too low: $sampleRate" }
        require(channels in 1..8) { "unsupported channel count: $channels" }
    }

    /** The fixed delay the wow/flutter line sits on, so the modulation can swing either side of it. */
    val latencyFrames: Int = max(16, ceil(0.004 * sampleRate).toInt())

    private val size = Integer.highestOneBit(latencyFrames * 4) * 2
    private val mask = size - 1
    private val lines = Array(channels) { FloatArray(size) }
    private var newest = 0

    private val fs = sampleRate.toDouble()
    private val twoPi = 2.0 * PI

    // --- wow and flutter
    private var wowPhase = 0.0
    private var flutterPhase = 0.0
    private var driftTarget = 0.0
    private var driftValue = 0.0
    private var driftCountdown = 0
    private var driftRng = seed or 1
    private var wowSmooth = 0.0

    // --- smoothed controls
    private var warmthSmooth = 0.0
    private var hissSmooth = 0.0
    private var nrSmooth = 0.0
    private var gainSmooth = 1.0

    // --- hiss
    private val noiseState = IntArray(channels) { (seed * (it + 3)) or 1 }
    private val noiseHp = DoubleArray(channels)
    private val hissCoef = lowpassCoef(2500.0)

    // --- shelves
    private val lowShelf = Shelf(channels)
    private val highShelf = Shelf(channels)

    // --- NR expander
    private val nrLow = DoubleArray(channels)
    private val nrEnv = DoubleArray(channels)
    private val nrLowCoef = lowpassCoef(1800.0)
    private val envAttack = timeCoef(0.002)
    private val envRelease = timeCoef(0.04)

    private val controlCoef = timeCoef(0.02)

    /** Clears everything that remembers the audio (after a seek, say). Settings are untouched. */
    fun reset() {
        lines.forEach { it.fill(0f) }
        newest = 0
        noiseHp.fill(0.0)
        nrLow.fill(0.0)
        nrEnv.fill(0.0)
        lowShelf.reset()
        highShelf.reset()
    }

    /** Processes [frames] interleaved frames of [data] in place. */
    fun process(data: FloatArray, frames: Int, s: TapeSettings) {
        require(data.size >= frames * channels) { "buffer too small" }
        if (frames <= 0) return

        val wowTarget = s.wow.toDouble().coerceIn(0.0, 1.0)
        val warmthTarget = s.warmth.toDouble().coerceIn(0.0, 1.0)
        val typeHissScale = when (s.type) {
            1 -> 0.7
            2 -> 0.5
            else -> 1.0
        }
        val hissTarget = s.hiss.toDouble().coerceIn(0.0, 1.0) * typeHissScale
        val nrTarget = if (s.noiseReduction) 1.0 else 0.0
        val gainTarget = 10.0.pow(s.gainDb.toDouble().coerceIn(-24.0, 12.0) / 20.0)

        // Tone: the tape type's high shelf and the low shelf that holds the type's and the head bump.
        val highDb = if (s.type >= 1) 1.5 else 0.0
        val lowDb = 3.0 * s.bump.toDouble().coerceIn(0.0, 1.0) + if (s.type == 2) 1.0 else 0.0
        highShelf.design(isHigh = true, freq = min(6000.0, fs * 0.4), gainDb = highDb, fs = fs)
        lowShelf.design(isHigh = false, freq = 100.0, gainDb = lowDb, fs = fs)

        val hissLevel = HISS_FULL_SCALE * 2.0
        val base = latencyFrames.toDouble()
        val wowInc = twoPi * WOW_HZ / fs
        val flutterInc = twoPi * FLUTTER_HZ / fs
        // A sine delay of amplitude A samples at f Hz moves the pitch by A·2πf/fs, so for a pitch
        // swing p the amplitude is p·fs/(2πf).
        val wowAmp = WOW_PITCH * fs / (twoPi * WOW_HZ)
        val flutterAmp = FLUTTER_PITCH * fs / (twoPi * FLUTTER_HZ)
        val driftAmp = DRIFT_PITCH * fs / (twoPi * DRIFT_HZ)
        val driftCoef = timeCoef(1.0 / (twoPi * DRIFT_HZ))
        // Even at full depth the swing must stay inside the line, behind the newest sample.
        val maxSwing = base - 4.0

        var i = 0
        for (frame in 0 until frames) {
            wowSmooth = approach(wowSmooth, wowTarget)
            warmthSmooth = approach(warmthSmooth, warmthTarget)
            hissSmooth = approach(hissSmooth, hissTarget)
            nrSmooth = approach(nrSmooth, nrTarget)
            gainSmooth = approach(gainSmooth, gainTarget)

            // --- the wandering delay
            if (driftCountdown <= 0) {
                driftTarget = nextUnit() * 2.0 - 1.0
                driftCountdown = (fs / (DRIFT_HZ * 2.0)).toInt().coerceAtLeast(1)
            }
            driftCountdown--
            driftValue += (driftTarget - driftValue) * driftCoef
            wowPhase += wowInc
            if (wowPhase > twoPi) wowPhase -= twoPi
            flutterPhase += flutterInc
            if (flutterPhase > twoPi) flutterPhase -= twoPi
            val swing = wowSmooth * (wowAmp * sin(wowPhase) + flutterAmp * sin(flutterPhase) + driftAmp * driftValue)
            val delay = base + swing.coerceIn(-maxSwing, maxSwing)

            newest = (newest + 1) and mask
            val whole = floor(delay).toInt()
            val frac = (delay - whole).toFloat()
            val warm = warmthSmooth
            val drive = 1.0 + 1.5 * warm

            for (c in 0 until channels) {
                val line = lines[c]
                line[newest] = data[i]
                var x = hermite(line, newest - whole, frac)

                // saturation, blended in smoothly so zero is exactly clean
                if (warm > 0.0) {
                    val saturated = tanh(drive * x) / drive
                    x = (x + (saturated - x) * warm).toFloat()
                }

                // hiss, high-passed white noise
                if (hissSmooth > 0.0) {
                    val white = nextNoise(c)
                    noiseHp[c] += (white - noiseHp[c]) * hissCoef
                    x += ((white - noiseHp[c]) * hissLevel * hissSmooth).toFloat()
                }

                // tape type and head bump
                if (highShelf.active) x = highShelf.run(c, x)
                if (lowShelf.active) x = lowShelf.run(c, x)

                // NR: when the treble is down at hiss level, take it further down
                if (nrSmooth > 0.0) {
                    nrLow[c] += (x - nrLow[c]) * nrLowCoef
                    val hf = x - nrLow[c]
                    val level = abs(hf)
                    nrEnv[c] += (level - nrEnv[c]) * (if (level > nrEnv[c]) envAttack else envRelease)
                    val gain = if (nrEnv[c] >= NR_THRESHOLD) 1.0 else max(NR_FLOOR, nrEnv[c] / NR_THRESHOLD)
                    x += (hf * (gain - 1.0) * nrSmooth).toFloat()
                } else {
                    // keep the band filter following the signal so switching it on is seamless
                    nrLow[c] += (x - nrLow[c]) * nrLowCoef
                }

                if (gainSmooth != 1.0) x = (x * gainSmooth).toFloat()
                data[i] = softLimit(x)
                i++
            }
        }
    }

    private fun approach(current: Double, target: Double): Double {
        if (current == target) return current
        val next = current + (target - current) * controlCoef
        return if (abs(next - target) < 1e-6) target else next
    }

    private fun hermite(line: FloatArray, at: Int, t: Float): Float {
        val y0 = line[(at - 1) and mask]
        val y1 = line[at and mask]
        val y2 = line[(at + 1) and mask]
        val y3 = line[(at + 2) and mask]
        if (t == 0f) return y1
        val c1 = 0.5f * (y2 - y0)
        val c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
        val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)
        return ((c3 * t + c2) * t + c1) * t + y1
    }

    private fun nextUnit(): Double {
        var x = driftRng
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        driftRng = x
        return (x ushr 8) / 16777216.0
    }

    /** White noise in −1..1 for [channel]. */
    private fun nextNoise(channel: Int): Double {
        var x = noiseState[channel]
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        noiseState[channel] = x
        return (x ushr 8) / 8388608.0 - 1.0
    }

    private fun lowpassCoef(freq: Double): Double = 1.0 - exp(-twoPi * min(freq, fs * 0.45) / fs)

    private fun timeCoef(seconds: Double): Double = 1.0 - exp(-1.0 / (fs * seconds))

    /** A shelving filter (RBJ cookbook) that can be switched off entirely when its gain is zero. */
    private class Shelf(channels: Int) {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
        private var a1 = 0.0; private var a2 = 0.0
        private val z1 = DoubleArray(channels)
        private val z2 = DoubleArray(channels)
        private var gainDb = 0.0
        private var freq = 0.0
        var active = false
            private set

        fun reset() {
            z1.fill(0.0); z2.fill(0.0)
        }

        fun design(isHigh: Boolean, freq: Double, gainDb: Double, fs: Double) {
            if (gainDb == this.gainDb && freq == this.freq) return
            this.gainDb = gainDb
            this.freq = freq
            if (abs(gainDb) < 0.01) {
                if (active) reset()
                active = false
                return
            }
            active = true
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * freq / fs
            val cosW = cos(w0)
            val alpha = sin(w0) / sqrt(2.0)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            val a0: Double
            if (isHigh) {
                b0 = a * ((a + 1) + (a - 1) * cosW + twoSqrtAAlpha)
                b1 = -2.0 * a * ((a - 1) + (a + 1) * cosW)
                b2 = a * ((a + 1) + (a - 1) * cosW - twoSqrtAAlpha)
                a0 = (a + 1) - (a - 1) * cosW + twoSqrtAAlpha
                a1 = 2.0 * ((a - 1) - (a + 1) * cosW)
                a2 = (a + 1) - (a - 1) * cosW - twoSqrtAAlpha
            } else {
                b0 = a * ((a + 1) - (a - 1) * cosW + twoSqrtAAlpha)
                b1 = 2.0 * a * ((a - 1) - (a + 1) * cosW)
                b2 = a * ((a + 1) - (a - 1) * cosW - twoSqrtAAlpha)
                a0 = (a + 1) + (a - 1) * cosW + twoSqrtAAlpha
                a1 = -2.0 * ((a - 1) + (a + 1) * cosW)
                a2 = (a + 1) + (a - 1) * cosW - twoSqrtAAlpha
            }
            b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0
        }

        fun run(channel: Int, x: Float): Float {
            val input = x.toDouble()
            val y = b0 * input + z1[channel]
            z1[channel] = b1 * input - a1 * y + z2[channel]
            z2[channel] = b2 * input - a2 * y
            return y.toFloat()
        }
    }

    companion object {
        /** Hiss at full: about −62 dBFS RMS. */
        const val HISS_FULL_SCALE = 7.9e-4
        const val WOW_HZ = 0.9
        const val FLUTTER_HZ = 9.5
        const val DRIFT_HZ = 0.35
        /** Pitch swing at full depth, as a fraction of the speed: 0.2%, 0.07% and 0.06%. */
        const val WOW_PITCH = 0.0020
        const val FLUTTER_PITCH = 0.0007
        const val DRIFT_PITCH = 0.0006
        /** The NR expander acts on everything above about 1.8 kHz that is below about −38 dBFS, at most −16 dB. */
        const val NR_THRESHOLD = 0.012
        const val NR_FLOOR = 0.15

        /** Linear up to 0.9, then a smooth knee that never goes past 1. */
        fun softLimit(x: Float): Float {
            val a = abs(x)
            if (a <= 0.9f) return x
            val limited = 0.9f + 0.1f * tanh((a - 0.9f) / 0.1f)
            return if (x < 0f) -limited else limited
        }
    }
}
