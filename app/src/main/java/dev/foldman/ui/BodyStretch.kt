package dev.foldman.ui

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Makes the artwork fit taller screens without stretching anything that has a shape: extra body is
 * inserted at two places where the case is plain (above the brand name, and between the cassette
 * window and the counter), made of the same texture, so the keys, window and dials keep their size.
 *
 * Everything in the artwork's own pixel coordinates; [top] and [mid] are the rows added at each place.
 */
object BodyStretch {
    const val ART_WIDTH = 941
    const val ART_HEIGHT = 1672

    /** Plain rows just under the top cap: the extra rows are made from these. */
    const val TOP_AT = 118
    const val TOP_ROWS = 20

    /** Plain rows between the cassette window and the counter panel. */
    const val MID_AT = 1112
    const val MID_ROWS = 30

    /** Taller than this and the picture gets bars rather than more stretch (about 21.3:9). */
    const val MAX_EXTRA = 560

    /** Most of the extra goes between the window and the counter, the rest above the brand name. */
    private const val TOP_SHARE = 0.35f

    /** How many rows to add so the artwork fills a screen of [width] × [height] (any unit). */
    fun extraFor(width: Float, height: Float): Int {
        if (width <= 0f || height <= 0f) return 0
        val needed = floor(ART_WIDTH * height / width - ART_HEIGHT).toInt()
        return needed.coerceIn(0, MAX_EXTRA)
    }

    /** Splits [extra] rows into (above the brand name, between window and counter). */
    fun split(extra: Int): Pair<Int, Int> {
        val top = (extra * TOP_SHARE).roundToInt().coerceIn(0, extra)
        return top to (extra - top)
    }

    /** Where a point at row [y] of the artwork ends up once [top] and [mid] rows have been added. */
    fun mapY(y: Float, top: Int, mid: Int): Float = when {
        y >= MID_AT -> y + top + mid
        y >= TOP_AT -> y + top
        else -> y
    }

    /**
     * The artwork's pixels ([src], ARGB, [width] × [ART_HEIGHT]) with the extra rows added.
     *
     * The inserted rows sweep back and forth through the plain band with a whole number of sweeps, so
     * the first inserted row is the band's first row and the last one runs into it again: no seams.
     */
    fun build(src: IntArray, width: Int, top: Int, mid: Int): IntArray {
        require(src.size == width * ART_HEIGHT) { "expected a ${width}x$ART_HEIGHT picture" }
        require(top >= 0 && mid >= 0)
        val height = ART_HEIGHT + top + mid
        val out = IntArray(width * height)
        // rows [0, TOP_AT)
        System.arraycopy(src, 0, out, 0, TOP_AT * width)
        var y = TOP_AT
        y = insert(src, width, TOP_AT, TOP_ROWS, top, out, y)
        // rows [TOP_AT, MID_AT)
        System.arraycopy(src, TOP_AT * width, out, y * width, (MID_AT - TOP_AT) * width)
        y += MID_AT - TOP_AT
        y = insert(src, width, MID_AT, MID_ROWS, mid, out, y)
        // rows [MID_AT, end)
        System.arraycopy(src, MID_AT * width, out, y * width, (ART_HEIGHT - MID_AT) * width)
        return out
    }

    private fun insert(src: IntArray, width: Int, bandStart: Int, bandRows: Int, count: Int, out: IntArray, outRow: Int): Int {
        if (count == 0) return outRow
        // How many back-and-forth sweeps fit in [count] rows at about the band's own pace.
        val sweeps = maxOf(1, (count / (2f * (bandRows - 1))).roundToInt())
        for (e in 0 until count) {
            val phase = e.toFloat() / count * sweeps * 2f
            val t = phase % 2f
            val v = if (t <= 1f) t else 2f - t
            val pos = v * (bandRows - 1)
            val lower = min(floor(pos).toInt(), bandRows - 1)
            val upper = min(lower + 1, bandRows - 1)
            val f = pos - lower
            val a = (bandStart + lower) * width
            val b = (bandStart + upper) * width
            val o = (outRow + e) * width
            for (x in 0 until width) out[o + x] = mix(src[a + x], src[b + x], f)
        }
        return outRow + count
    }

    private fun mix(p: Int, q: Int, f: Float): Int {
        if (f <= 0f || p == q) return p
        val a = lerp(p ushr 24, q ushr 24, f)
        val r = lerp((p shr 16) and 0xFF, (q shr 16) and 0xFF, f)
        val g = lerp((p shr 8) and 0xFF, (q shr 8) and 0xFF, f)
        val b = lerp(p and 0xFF, q and 0xFF, f)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerp(a: Int, b: Int, f: Float): Int = (a + (b - a) * f).roundToInt().coerceIn(0, 255)
}
