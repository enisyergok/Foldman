package dev.foldman

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.foldman.deck.DeckViewModel
import dev.foldman.deck.LocalTrack
import dev.foldman.deck.Transport
import dev.foldman.playback.TapeParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** The whole player on a real (emulated) phone: look at it, load a tape, press the keys. */
@RunWith(AndroidJUnit4::class)
class PlayerScreenTest {
    private val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(permission).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val vm: DeckViewModel get() = ViewModelProvider(compose.activity)[DeckViewModel::class.java]

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = context.getExternalFilesDir("shots") ?: return
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $what (transport=${vm.ui.value.transport}, side=${vm.ui.value.side}, pos=${vm.ui.value.positionMs})")
    }

    private fun loadTape(count: Int = 3, seconds: Double = 3.0) {
        val tracks = (1..count).map { n ->
            val name = "0$n - Track $n.wav"
            val file = TestAudio.wav(File(context.cacheDir, "tape/$name"), seconds, freq = 330.0 + 110 * n)
            LocalTrack(
                uri = Uri.fromFile(file), title = "Track $n", artist = "Test Band", folder = "Test Album",
                coverUri = null, folderPath = "Test Album", size = file.length(), modified = file.lastModified(), tagged = true,
            )
        }
        compose.runOnUiThread { vm.loadTracksForTest(tracks) }
        waitFor("the tape to load") { vm.ui.value.tracks.size == count && vm.ui.value.trackCount == count }
    }

    @Test
    fun theWholePlayer() {
        compose.waitForIdle()
        val display = context.resources.displayMetrics
        val tag = "${display.widthPixels}x${display.heightPixels}"
        shot("01-launch-$tag")

        // every control is there, with its name
        for (name in listOf("Rewind", "Play", "Fast forward", "Stop, eject", "Pause", "Volume", "Tape position", "Tape type", "Noise reduction")) {
            compose.onNodeWithContentDescription(name).assertExists()
        }
        compose.onNode(hasContentDescription("Cassette", substring = true)).assertExists()

        loadTape()
        shot("02-loaded-$tag")

        // PLAY latches, the tape runs, the clock and lamps move
        compose.onNodeWithContentDescription("Play").performClick()
        waitFor("PLAY") { vm.ui.value.transport == Transport.PLAYING }
        waitFor("the position to move") { vm.ui.value.positionMs > 600 }
        Thread.sleep(800)
        shot("03-playing-$tag")
        assertTrue("title should be the first song", vm.ui.value.title == "Track 1")

        // the position bar seeks
        compose.onNodeWithContentDescription("Tape position").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        waitFor("the seek") { abs(vm.ui.value.progress - 0.5f) < 0.2f && vm.ui.value.positionMs > 1000 }

        // the volume knob moves the phone's volume
        compose.onNodeWithContentDescription("Volume").performSemanticsAction(SemanticsActions.SetProgress) { it(0.4f) }
        waitFor("the volume") { abs(vm.ui.value.volume - 0.4f) < 0.12f }

        // PAUSE
        compose.onNodeWithContentDescription("Pause").performClick()
        waitFor("PAUSE") { vm.ui.value.transport == Transport.PAUSED }
        shot("04-paused-$tag")
        compose.onNodeWithContentDescription("Play").performClick()
        waitFor("PLAY again") { vm.ui.value.transport == Transport.PLAYING }

        // fast forward and stop
        compose.onNodeWithContentDescription("Fast forward").performClick()
        waitFor("FF") { vm.ui.value.transport == Transport.FAST_FORWARDING }
        Thread.sleep(400)
        shot("05-winding-$tag")
        compose.onNodeWithContentDescription("Stop, eject").performClick()
        waitFor("STOP") { vm.ui.value.transport == Transport.STOPPED }

        // the TAPE button steps through the three types, and the audio engine hears it
        repeat(2) { compose.onNodeWithContentDescription("Tape type").performClick() }
        waitFor("the tape type") { vm.ui.value.tapeType == 2 && TapeParams.current.type == 2 }
        compose.onNodeWithContentDescription("Noise reduction").performClick()
        waitFor("NR") { vm.ui.value.noiseReduction && TapeParams.current.noiseReduction }
        shot("06-metal-nr-$tag")

        // hold the cassette: the tape settings
        compose.onNode(hasContentDescription("Cassette", substring = true)).performTouchInput { longClick() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Wow and flutter")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("Wow and flutter", substring = true))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        waitFor("wow in the engine") { abs(TapeParams.current.wow - 0.5f) < 0.01f }
        compose.onNode(hasText("Auto-reverse") and isToggleable()).performClick()
        waitFor("auto-reverse") { vm.ui.value.autoReverse }
        compose.onNode(hasText("Level songs (ReplayGain)") and isToggleable()).performClick()
        waitFor("replay gain") { vm.ui.value.character.replayGain }
        shot("07-settings-$tag")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()

        // the sleep timer can be set and cancelled
        compose.runOnUiThread { vm.setSleepTimer(1) }
        waitFor("sleep timer on") { vm.ui.value.sleepMinutes == 1 && vm.ui.value.sleepRemainingMs > 0 }
        compose.runOnUiThread { vm.setSleepTimer(0) }
        waitFor("sleep timer off") { vm.ui.value.sleepMinutes == 0 }

        // auto-reverse: play the last song to its end and the tape turns over
        compose.runOnUiThread { vm.playTrack(2) }
        waitFor("last song") { vm.ui.value.trackIndex == 2 && vm.ui.value.transport == Transport.PLAYING }
        compose.runOnUiThread { vm.seekToFraction(0.9f) }
        waitFor("side B", 20_000) { vm.ui.value.side == 1 }
        Thread.sleep(700)
        assertEquals(0, vm.ui.value.trackIndex)
        shot("08-side-b-$tag")

        // and back to a clean sound
        compose.runOnUiThread { vm.resetTapeCharacter(); vm.setTapeType(0); vm.setNoiseReduction(false) }
        waitFor("back to clean") { TapeParams.current.wow == 0f && TapeParams.current.type == 0 }
        compose.onNodeWithContentDescription("Stop, eject").performClick()
        shot("09-end-$tag")
    }
}
