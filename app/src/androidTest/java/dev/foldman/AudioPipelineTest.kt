package dev.foldman

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.foldman.playback.AudioLevels
import dev.foldman.playback.PlaybackEngine
import dev.foldman.playback.TapeParams
import dev.foldman.playback.TapeSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The real player with the tape in its audio path, on a real Android runtime. */
@RunWith(AndroidJUnit4::class)
class AudioPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private class Result(val error: Throwable?, val ended: Boolean, val peak: Float, val positionMs: Long)

    @After
    fun tidy() {
        TapeParams.current = TapeSettings()
    }

    private fun play(file: File, settings: TapeSettings, timeoutSeconds: Long = 25): Result {
        TapeParams.current = settings
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val holder = AtomicReference<ExoPlayer>()
        instrumentation.runOnMainSync {
            val player = PlaybackEngine.createPlayer(context)
            holder.set(player)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) done.countDown()
                }

                override fun onPlayerError(e: PlaybackException) {
                    error.set(e)
                    done.countDown()
                }
            })
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
        }
        var peak = 0f
        val deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1000
        while (done.count > 0 && SystemClock.elapsedRealtime() < deadline) {
            peak = maxOf(peak, AudioLevels.read().first)
            Thread.sleep(20)
        }
        var position = 0L
        instrumentation.runOnMainSync {
            position = holder.get().currentPosition
            holder.get().release()
        }
        return Result(error.get(), done.count == 0L, peak, position)
    }

    private fun wav(name: String, seconds: Double, rate: Int = 44100, channels: Int = 2) =
        TestAudio.wav(File(context.cacheDir, name), seconds, rate, channels)

    @Test
    fun playsThroughTheTapeWithNeutralSettings() {
        val r = play(wav("neutral.wav", 2.0), TapeSettings())
        assertNull("player error: ${r.error}", r.error)
        assertTrue("never reached the end", r.ended)
        assertTrue("level meter saw nothing (peak ${r.peak})", r.peak > 0.05f)
    }

    @Test
    fun playsThroughTheTapeWithEverythingOn() {
        val s = TapeSettings(type = 2, noiseReduction = true, wow = 1f, hiss = 1f, warmth = 1f, bump = 1f, gainDb = 6f)
        val r = play(wav("tilt.wav", 2.0), s)
        assertNull("player error: ${r.error}", r.error)
        assertTrue("never reached the end", r.ended)
        assertTrue("level meter saw nothing (peak ${r.peak})", r.peak > 0.05f)
    }

    @Test
    fun handlesOtherSampleRatesAndMono() {
        for ((rate, channels) in listOf(8000 to 1, 22050 to 2, 48000 to 2, 96000 to 2)) {
            val r = play(wav("fmt-$rate-$channels.wav", 1.5, rate, channels), TapeSettings(type = 1, wow = 0.5f, hiss = 0.3f))
            assertNull("player error at $rate Hz/$channels ch: ${r.error}", r.error)
            assertTrue("never reached the end at $rate Hz/$channels ch", r.ended)
        }
    }

    @Test
    fun changingTheTapeWhilePlayingDoesNotBreakIt() {
        TapeParams.current = TapeSettings()
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val holder = AtomicReference<ExoPlayer>()
        instrumentation.runOnMainSync {
            val player = PlaybackEngine.createPlayer(context)
            holder.set(player)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) done.countDown()
                }

                override fun onPlayerError(e: PlaybackException) {
                    error.set(e)
                    done.countDown()
                }
            })
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(wav("live.wav", 4.0))))
            player.prepare()
            player.play()
        }
        val settings = listOf(
            TapeSettings(type = 1), TapeSettings(type = 2, noiseReduction = true), TapeSettings(wow = 1f, hiss = 1f),
            TapeSettings(warmth = 1f, bump = 1f, gainDb = -6f), TapeSettings(),
        )
        for (s in settings) {
            TapeParams.current = s
            Thread.sleep(500)
            // and a seek in the middle, which flushes the tape's delay line
            instrumentation.runOnMainSync { holder.get().seekTo(500) }
        }
        done.await(20, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { holder.get().release() }
        assertNull("player error: ${error.get()}", error.get())
        assertEquals("never reached the end", 0L, done.count)
    }
}
