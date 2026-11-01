package dev.foldman

import android.Manifest
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import dev.foldman.deck.DeckViewModel
import dev.foldman.deck.LocalTrack
import dev.foldman.deck.Transport
import dev.foldman.playback.TapeParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The whole player on a real (emulated) phone, driven with real touches the way a person would:
 * look at it, load a tape, press the keys. Screenshots are saved for looking at.
 */
@RunWith(AndroidJUnit4::class)
class PlayerScreenTest {
    private val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val scenario = ActivityScenarioRule(MainActivity::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(permission).around(scenario)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private fun <T> onActivity(block: (MainActivity) -> T): T {
        var result: Any? = null
        scenario.scenario.onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private val vm: DeckViewModel get() = onActivity { ViewModelProvider(it)[DeckViewModel::class.java] }

    /** Runs [block] on the main thread with the view model. */
    private fun withVm(block: (DeckViewModel) -> Unit) = onActivity { block(ViewModelProvider(it)[DeckViewModel::class.java]) }

    init {
        // The reels never stop turning while a tape plays, so there is no quiet moment to wait for.
        Configurator.getInstance().waitForIdleTimeout = 0
        Configurator.getInstance().waitForSelectorTimeout = 0
    }

    private fun shot(name: String) {
        Thread.sleep(250)
        val dir = context.getExternalFilesDir("shots") ?: return
        dir.mkdirs()
        device.takeScreenshot(File(dir, "$name.png"))
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        val ui = vm.ui.value
        throw AssertionError("timed out waiting for: $what (transport=${ui.transport}, side=${ui.side}, pos=${ui.positionMs}, title=${ui.title})")
    }

    private fun find(desc: String): UiObject2 =
        device.wait(Until.findObject(By.desc(desc)), 10_000) ?: throw AssertionError("no control called \"$desc\" on screen")

    private fun tap(desc: String) = find(desc).click()

    private fun loadTape(count: Int = 3, seconds: Double = 120.0) {
        val tracks = (1..count).map { n ->
            // 8 kHz mono keeps two-minute songs small; the emulator is slow, so the songs are long
            val file = TestAudio.wav(File(context.cacheDir, "tape/0$n - Track $n.wav"), seconds, rate = 8000, channels = 1, freq = 330.0 + 110 * n)
            LocalTrack(
                uri = Uri.fromFile(file), title = "Track $n", artist = "Test Band", folder = "Test Album",
                coverUri = null, folderPath = "Test Album", size = file.length(), modified = file.lastModified(), tagged = true,
            )
        }
        withVm { it.loadTracksForTest(tracks) }
        waitFor("the tape to load") { vm.ui.value.tracks.size == count && vm.ui.value.trackCount == count }
    }

    @Test
    fun theWholePlayer() {
        Thread.sleep(1500)
        val display = context.resources.displayMetrics
        val tag = "${display.widthPixels}x${display.heightPixels}"
        shot("01-launch-$tag")

        // every control is there, with its name
        for (name in listOf("Rewind", "Play", "Fast forward", "Stop, eject", "Pause", "Volume", "Tape position", "Tape type", "Noise reduction")) {
            assertNotNull("missing control: $name", device.wait(Until.findObject(By.desc(name)), 10_000))
        }
        assertNotNull(device.wait(Until.findObject(By.descContains("Cassette")), 5_000))

        loadTape()
        shot("02-loaded-$tag")

        // PLAY latches, the tape runs, the clock and lamps move
        tap("Play")
        waitFor("PLAY") { vm.ui.value.transport == Transport.PLAYING }
        waitFor("the position to move") { vm.ui.value.positionMs > 600 }
        assertEquals("title of the first song", "Track 1", vm.ui.value.title)
        Thread.sleep(600)
        shot("03-playing-$tag")

        // seeking, as the position bar does it
        withVm { it.seekToFraction(0.5f) }
        waitFor("the seek") { abs(vm.ui.value.progress - 0.5f) < 0.2f && vm.ui.value.positionMs > 1000 }

        // volume
        withVm { it.setVolume(0.4f) }
        waitFor("the volume") { abs(vm.ui.value.volume - 0.4f) < 0.12f }

        // PAUSE
        tap("Pause")
        waitFor("PAUSE") { vm.ui.value.transport == Transport.PAUSED }
        shot("04-paused-$tag")
        tap("Play")
        waitFor("PLAY again") { vm.ui.value.transport == Transport.PLAYING }

        // fast forward and stop
        tap("Fast forward")
        waitFor("FF") { vm.ui.value.transport == Transport.FAST_FORWARDING }
        Thread.sleep(300)
        shot("05-winding-$tag")
        tap("Stop, eject")
        waitFor("STOP") { vm.ui.value.transport == Transport.STOPPED }

        // the TAPE button steps through the three types, and the audio engine hears it
        repeat(2) { tap("Tape type"); Thread.sleep(150) }
        waitFor("the tape type") { vm.ui.value.tapeType == 2 && TapeParams.current.type == 2 }
        tap("Noise reduction")
        waitFor("NR") { vm.ui.value.noiseReduction && TapeParams.current.noiseReduction }
        shot("06-metal-nr-$tag")

        // hold the cassette: the tape settings
        find("Cassette: Track 1 - Test Band").longClick()
        assertNotNull("tape settings did not open", device.wait(Until.findObject(By.text("Wow and flutter")), 10_000))
        shot("07-settings-$tag")
        device.findObject(By.text("Auto-reverse")).click()
        waitFor("auto-reverse") { vm.ui.value.autoReverse }
        device.findObject(By.text("Level songs (ReplayGain)")).click()
        waitFor("replay gain") { vm.ui.value.character.replayGain }
        withVm { it.setTapeCharacter("tape_wow", 0.5f) }
        waitFor("wow in the engine") { abs(TapeParams.current.wow - 0.5f) < 0.01f }
        device.pressBack()
        Thread.sleep(500)

        // the sleep timer can be set and cancelled
        withVm { it.setSleepTimer(1) }
        waitFor("sleep timer on") { vm.ui.value.sleepMinutes == 1 && vm.ui.value.sleepRemainingMs > 0 }
        withVm { it.setSleepTimer(0) }
        waitFor("sleep timer off") { vm.ui.value.sleepMinutes == 0 }

        // auto-reverse: play the last song to its end and the tape turns over
        withVm { it.playTrack(2) }
        waitFor("last song") { vm.ui.value.trackIndex == 2 && vm.ui.value.transport == Transport.PLAYING }
        withVm { it.seekToFraction(0.97f) }
        waitFor("side B", 30_000) { vm.ui.value.side == 1 }
        Thread.sleep(1200)
        assertEquals(0, vm.ui.value.trackIndex)
        shot("08-side-b-$tag")

        // and back to a clean sound
        withVm { it.resetTapeCharacter(); it.setTapeType(0); it.setNoiseReduction(false) }
        waitFor("back to clean") { TapeParams.current.wow == 0f && TapeParams.current.type == 0 }
        tap("Stop, eject")
        shot("09-end-$tag")
    }
}
