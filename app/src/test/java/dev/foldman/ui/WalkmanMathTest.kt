package dev.foldman.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkmanMathTest {
    @Test
    fun clockFormatsMinutesAndSeconds() {
        assertEquals("00:00", clock(0))
        assertEquals("00:09", clock(9_999))
        assertEquals("01:05", clock(65_000))
        assertEquals("04:26", clock(266_000))
        assertEquals("60:00", clock(3_600_000))
    }

    @Test
    fun clockNeverShowsNegativeTime() {
        assertEquals("00:00", clock(-1))
        assertEquals("00:00", clock(-90_000))
    }

    @Test
    fun silenceLightsNoLamps() {
        assertEquals(0, lit(0f))
        assertEquals(0, lit(0.002f))
    }

    @Test
    fun lampsFollowThePrintedScale() {
        // 0 VU (needle at 20/23) is at the "0" on the printed scale, about the 20th lamp of 30.
        assertTrue(lit(20f / 23f) in 19..21)
        // +3 VU (needle at 1.0) is the +3 on the printed scale; the needle's stop (1.08) is a little beyond.
        assertTrue(lit(1f) in 23..25)
        assertTrue(lit(1.08f) in 26..28)
        // Nothing the needle can do lights more lamps than there are.
        assertTrue(lit(5f) <= 30)
    }

    @Test
    fun morelevelNeverLightsFewerLamps() {
        var last = 0
        var v = 0f
        while (v <= 1.1f) {
            val n = lit(v)
            assertTrue("lamps went down at $v", n >= last)
            assertTrue(n in 0..30)
            last = n
            v += 0.005f
        }
    }
}
