package dev.foldman.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BodyStretchTest {
    private val w = 8

    /** A picture whose every row has a different grey, so rows can be told apart. */
    private fun ramp(): IntArray = IntArray(w * BodyStretch.ART_HEIGHT) { i ->
        val row = i / w
        val g = row % 256
        (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    private fun row(img: IntArray, y: Int) = img.copyOfRange(y * w, (y + 1) * w)
    private fun grey(p: Int) = p and 0xFF

    @Test
    fun noExtraMeansNoChange() {
        val src = ramp()
        assertArrayEquals(src, BodyStretch.build(src, w, 0, 0))
    }

    @Test
    fun sizeGrowsByExactlyTheExtra() {
        val out = BodyStretch.build(ramp(), w, 147, 272)
        assertEquals(w * (BodyStretch.ART_HEIGHT + 147 + 272), out.size)
    }

    @Test
    fun rowsOutsideTheBandsAreUntouchedJustMoved() {
        val src = ramp()
        val top = 100
        val mid = 200
        val out = BodyStretch.build(src, w, top, mid)
        for (y in listOf(0, 50, BodyStretch.TOP_AT - 1)) assertArrayEquals(row(src, y), row(out, y))
        for (y in listOf(BodyStretch.TOP_AT, 400, 900, BodyStretch.MID_AT - 1)) {
            assertArrayEquals("row $y", row(src, y), row(out, y + top))
        }
        for (y in listOf(BodyStretch.MID_AT, 1300, BodyStretch.ART_HEIGHT - 1)) {
            assertArrayEquals("row $y", row(src, y), row(out, y + top + mid))
        }
    }

    @Test
    fun insertedRowsStayInsideTheBandAndJoinWithoutSeams() {
        val src = ramp()
        val top = 147
        val mid = 272
        val out = BodyStretch.build(src, w, top, mid)
        // top insertion occupies output rows [TOP_AT, TOP_AT + top)
        for (e in 0 until top) {
            val g = grey(row(out, BodyStretch.TOP_AT + e)[0])
            assertTrue("row $e grey $g", g in BodyStretch.TOP_AT..(BodyStretch.TOP_AT + BodyStretch.TOP_ROWS - 1))
        }
        // starts at the band's first row and ends right next to it
        assertEquals(BodyStretch.TOP_AT, grey(row(out, BodyStretch.TOP_AT)[0]))
        assertTrue(abs(grey(row(out, BodyStretch.TOP_AT + top - 1)[0]) - BodyStretch.TOP_AT) <= 2)
        // the same in the middle
        val midStart = BodyStretch.MID_AT + top
        assertEquals(BodyStretch.MID_AT % 256, grey(row(out, midStart)[0]))
        assertTrue(abs(grey(row(out, midStart + mid - 1)[0]) - BodyStretch.MID_AT % 256) <= 2)
        for (e in 0 until mid) {
            val g = grey(row(out, midStart + e)[0])
            assertTrue("row $e grey $g", g in (BodyStretch.MID_AT % 256)..(BodyStretch.MID_AT % 256 + BodyStretch.MID_ROWS - 1))
        }
    }

    @Test
    fun neighbouringInsertedRowsNeverJumpMuchMoreThanTheBandsOwnPace() {
        val out = BodyStretch.build(ramp(), w, 400, 400)
        var prev = grey(row(out, BodyStretch.TOP_AT)[0])
        for (e in 1 until 400) {
            val g = grey(row(out, BodyStretch.TOP_AT + e)[0])
            assertTrue("jump at $e: $prev -> $g", abs(g - prev) <= 2)
            prev = g
        }
    }

    @Test
    fun extraForCommonScreens() {
        // 9:16 is the artwork itself
        assertEquals(0, BodyStretch.extraFor(900f, 1600f))
        // 9:20 and 9:19.5 phones
        assertEquals(419, BodyStretch.extraFor(1080f, 2400f))
        assertTrue(BodyStretch.extraFor(1080f, 2340f) in 350..380)
        // wider than the artwork: nothing to add
        assertEquals(0, BodyStretch.extraFor(1600f, 1200f))
        // absurdly tall: capped
        assertEquals(BodyStretch.MAX_EXTRA, BodyStretch.extraFor(500f, 4000f))
        assertEquals(0, BodyStretch.extraFor(0f, 100f))
    }

    @Test
    fun splitAddsUp() {
        for (extra in listOf(0, 1, 7, 100, 419, 560)) {
            val (t, m) = BodyStretch.split(extra)
            assertEquals(extra, t + m)
            assertTrue(t >= 0 && m >= 0)
        }
    }

    @Test
    fun mapYShiftsEverythingBelowEachBand() {
        assertEquals(50f, BodyStretch.mapY(50f, 100, 200), 0f)
        assertEquals(500f + 100f, BodyStretch.mapY(500f, 100, 200), 0f)
        assertEquals(1300f + 300f, BodyStretch.mapY(1300f, 100, 200), 0f)
    }
}
