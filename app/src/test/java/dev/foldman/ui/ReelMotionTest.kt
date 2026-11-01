package dev.foldman.ui

import dev.foldman.deck.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ReelMotionTest {
    private fun ReelMotion.run(seconds: Float, transport: Transport, progress: Float) {
        var t = 0f
        while (t < seconds) {
            step(1f / 60f, transport, progress)
            t += 1f / 60f
        }
    }

    @Test
    fun startsAtRest() {
        val reels = ReelMotion()
        assertTrue(reels.isAtRest)
        assertEquals(0f, reels.tapeSpeed, 0f)
    }

    @Test
    fun playReachesTapeSpeedAndSpinsTheReels() {
        val reels = ReelMotion()
        reels.run(3f, Transport.PLAYING, 0f)
        assertEquals(ReelMotion.PLAY_SPEED, reels.tapeSpeed, ReelMotion.PLAY_SPEED * 0.01f)
        assertNotEquals(0f, reels.leftAngle)
        assertNotEquals(0f, reels.rightAngle)
        assertFalse(reels.isAtRest)
    }

    @Test
    fun theEmptyReelSpinsFasterThanTheFullOne() {
        val reels = ReelMotion()
        reels.run(3f, Transport.PLAYING, 0.05f) // nearly all the tape is still on the left reel
        val before = reels.leftDelta to reels.rightDelta
        assertTrue("right (nearly empty) should turn faster: $before", abs(before.second) > abs(before.first))
    }

    @Test
    fun stoppingBringsTheReelsToRest() {
        val reels = ReelMotion()
        reels.run(2f, Transport.PLAYING, 0.3f)
        reels.run(3f, Transport.STOPPED, 0.3f)
        assertTrue(reels.isAtRest)
    }

    @Test
    fun windingIsFasterThanPlayingAndRewindingReverses() {
        val ff = ReelMotion().apply { run(4f, Transport.FAST_FORWARDING, 0.5f) }
        val rew = ReelMotion().apply { run(4f, Transport.REWINDING, 0.5f) }
        assertTrue(ff.tapeSpeed > ReelMotion.PLAY_SPEED * 3)
        assertEquals(-ff.tapeSpeed, rew.tapeSpeed, 1f)
    }

    @Test
    fun tapeAreaIsConservedWhateverThePosition() {
        val reels = ReelMotion()
        val total = ReelMotion.packRadius(1f).let { it * it } + ReelMotion.packRadius(0f).let { it * it }
        for (p in listOf(0f, 0.1f, 0.5f, 0.9f, 1f)) {
            val l = ReelMotion.packRadius(1f - p)
            val r = ReelMotion.packRadius(p)
            assertEquals("at $p", total, l * l + r * r, 0.01f)
        }
        assertEquals(ReelMotion.R_FULL, reels.leftRadius, 0.001f)
        assertEquals(ReelMotion.R_EMPTY, reels.rightRadius, 0.001f)
    }

    @Test
    fun theShownPositionCatchesUpWithTheRealOne() {
        val reels = ReelMotion()
        reels.run(3f, Transport.STOPPED, 0.8f)
        assertEquals(0.8f, reels.shownProgress, 0.005f)
    }
}
