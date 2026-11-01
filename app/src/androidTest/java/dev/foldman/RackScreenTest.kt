package dev.foldman

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.foldman.deck.DeckViewModel
import dev.foldman.deck.LocalTrack
import dev.foldman.deck.Transport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * The cassette rack on a real (emulated) phone, driven through the screen's own semantics (so it does
 * not depend on the timing of the accessibility tree): open it from the cassette, carry tapes into the
 * deck, read a card, and make a mixtape from the blank tape.
 */
@RunWith(AndroidJUnit4::class)
class RackScreenTest {
    private val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(permission).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val vm: DeckViewModel get() = ViewModelProvider(compose.activity)[DeckViewModel::class.java]

    @After
    fun tidy() {
        vm.ui.value.mixtapes.forEach { m -> compose.runOnUiThread { vm.deleteMixtape(m.id) } }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = context.getExternalFilesDir("shots") ?: return
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(60)
        }
        val ui = vm.ui.value
        throw AssertionError("timed out waiting for: $what (tape=${ui.tape.id}, transport=${ui.transport}, queue=${ui.queue.size}, title=${ui.title})")
    }

    private fun song(title: String, album: String, artist: String, freq: Double): LocalTrack {
        val file = TestAudio.wav(File(context.cacheDir, "rack/$album/$title.wav"), 60.0, rate = 8000, channels = 1, freq = freq)
        return LocalTrack(
            uri = Uri.fromFile(file), title = title, artist = artist, folder = album, coverUri = null,
            folderPath = "Music/$album", size = file.length(), modified = file.lastModified(), tagged = true, durationMs = 60_000,
        )
    }

    private fun cassette() = compose.onNode(hasContentDescription("Cassette", substring = true))

    private fun openRack() {
        cassette().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("CASSETTE RACK")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun has(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** Carries the tape whose card is described by [desc] to the middle of the cassette window by its cover. */
    private fun carryToDeck(desc: String, picture: String? = null) {
        val deck = cassette().fetchSemanticsNode().boundsInRoot
        val card = compose.onNode(hasContentDescription(desc, substring = true)).fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        val start = Offset(card.left + 40 * density, card.center.y)
        val end = deck.center
        val root = compose.onRoot()
        // like a finger: each step is its own injection, with the UI given time to react in between
        root.performTouchInput { down(start) }
        compose.waitForIdle()
        for (i in 1..20) {
            root.performTouchInput { moveTo(start + (end - start) * (i / 20f)) }
            compose.waitForIdle()
        }
        if (picture != null) shot(picture)
        root.performTouchInput { up() }
        compose.waitForIdle()
    }

    private fun stopAndWaitUntilStill() {
        compose.runOnUiThread { vm.stop() }
        waitFor("STOP") { vm.ui.value.transport == Transport.STOPPED }
        Thread.sleep(1500) // the reels coast to a stop, and the screen goes quiet
    }

    @Test
    fun theRack() {
        compose.waitForIdle()
        compose.runOnUiThread {
            vm.loadTracksForTest(
                listOf(
                    song("Alpha One", "Album A", "Band A", 330.0), song("Alpha Two", "Album A", "Band A", 440.0), song("Alpha Three", "Album A", "Band A", 550.0),
                    song("Beta One", "Album B", "Band B", 660.0), song("Beta Two", "Album B", "Band B", 770.0),
                    song("Lonely Song", "Singles", "Solo", 880.0),
                ),
            )
        }
        waitFor("the library") { vm.ui.value.tracks.size == 6 && vm.ui.value.tape.id == "library" }
        compose.waitForIdle()

        // tap the cassette: the rack opens
        openRack()
        assertTrue(has("ALBUMS 3") && has("SONGS 6") && has("MIXTAPES 0"))
        shot("10-rack-albums")

        // carry Album B into the deck by its cover
        carryToDeck("Album B. ", picture = "10b-carrying")
        waitFor("Album B in the deck") { vm.ui.value.tape.id == "album:Music/Album B" }
        waitFor("its songs as the queue") { vm.ui.value.queue.size == 2 }
        assertEquals("a tape is put in, not played", Transport.STOPPED, vm.ui.value.transport)
        assertEquals("the library is untouched", 6, vm.ui.value.tracks.size)
        compose.waitForIdle()
        shot("11-album-in-deck")

        // PLAY plays it
        compose.runOnUiThread { vm.play() }
        waitFor("PLAY") { vm.ui.value.transport == Transport.PLAYING }
        waitFor("the first song") { vm.ui.value.title == "Beta One" }
        stopAndWaitUntilStill()

        // the songs tab: a single song as a tape
        openRack()
        compose.onNodeWithText("SONGS 6").performClick()
        compose.waitForIdle()
        shot("12-rack-songs")
        carryToDeck("Lonely Song. ")
        waitFor("a single in the deck") { vm.ui.value.tape.id.startsWith("song:") && vm.ui.value.queue.size == 1 }
        compose.waitForIdle()

        // hold a card to read it
        openRack()
        compose.onNodeWithText("ALBUMS 3").performClick()
        compose.onNode(hasContentDescription("Album A. ", substring = true)).performTouchInput { longClick() }
        compose.waitUntil(10_000) { has("INSERT") }
        assertTrue(has("Alpha Three") && has("Alpha One"))
        shot("13-card")
        compose.onNodeWithText("CLOSE").performClick()
        compose.waitForIdle()

        // the blank mixtape
        compose.onNodeWithText("MIXTAPES 0").performClick()
        compose.waitForIdle()
        shot("14-rack-mixtapes")
        compose.onNodeWithContentDescription("Blank mixtape").performClick()
        compose.waitUntil(10_000) { has("NEW MIXTAPE") }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Road trip")
        compose.onNode(hasContentDescription("Beta Two, add to the tape")).performClick()
        compose.onNode(hasContentDescription("Alpha One, add to the tape")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("ON THIS TAPE · 2", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        shot("15-mixtape-editor")
        compose.onNodeWithText("SAVE").performClick()
        waitFor("the mixtape") { vm.ui.value.mixtapes.size == 1 }
        assertEquals("Road trip", vm.ui.value.mixtapes[0].name)
        assertEquals(2, vm.ui.value.mixtapes[0].keys.size)
        compose.waitUntil(10_000) { has("MIXTAPES 1") }
        shot("16-mixtape-in-rack")

        // and the mixtape goes in the deck, songs in the order they were picked
        carryToDeck("Road trip. ")
        waitFor("the mixtape in the deck") { vm.ui.value.tape.id.startsWith("mix:") && vm.ui.value.queue.size == 2 }
        compose.runOnUiThread { vm.play() }
        waitFor("the mixtape's first song") { vm.ui.value.transport == Transport.PLAYING && vm.ui.value.title == "Beta Two" }
        shot("17-mixtape-playing")
        assertEquals("Road trip", vm.ui.value.tape.title)
        stopAndWaitUntilStill()
    }
}
