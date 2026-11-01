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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * The cassette rack on a real (emulated) phone: open it from the cassette, carry a tape into the
 * deck, read a card, and make a mixtape from the blank tape.
 */
@RunWith(AndroidJUnit4::class)
class RackScreenTest {
    private val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val scenario = ActivityScenarioRule(MainActivity::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(permission).around(scenario)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val density get() = context.resources.displayMetrics.density
    private lateinit var vm: DeckViewModel

    init {
        Configurator.getInstance().waitForIdleTimeout = 0
        Configurator.getInstance().waitForSelectorTimeout = 0
    }

    @Before
    fun grabTheViewModel() {
        scenario.scenario.onActivity { vm = ViewModelProvider(it)[DeckViewModel::class.java] }
    }

    @After
    fun tidy() {
        // leave nothing of the test's mixtapes behind for the next run
        vm.ui.value.mixtapes.forEach { m -> scenario.scenario.onActivity { vm.deleteMixtape(m.id) } }
    }

    private fun withVm(block: (DeckViewModel) -> Unit) = scenario.scenario.onActivity { block(vm) }

    /** A slow emulator sometimes makes a system app miss a beat and shows a box over the test: wait it out. */
    private fun dismissSystemBox() {
        if (device.hasObject(By.textContains("isn't responding"))) device.findObject(By.text("Wait"))?.click()
    }

    private fun shot(name: String) {
        Thread.sleep(300)
        val dir = context.getExternalFilesDir("shots") ?: return
        dir.mkdirs()
        device.takeScreenshot(File(dir, "$name.png"))
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

    private fun find(selectorDesc: String): UiObject2 =
        dismissSystemBox().let { device }.wait(Until.findObject(By.descStartsWith(selectorDesc)), 10_000) ?: throw AssertionError("nothing described \"$selectorDesc\" on screen")

    private fun findText(text: String): UiObject2 =
        device.wait(Until.findObject(By.text(text)), 10_000) ?: throw AssertionError("no text \"$text\" on screen")

    private fun song(title: String, album: String, artist: String, freq: Double): LocalTrack {
        val file = TestAudio.wav(File(context.cacheDir, "rack/$album/$title.wav"), 60.0, rate = 8000, channels = 1, freq = freq)
        return LocalTrack(
            uri = Uri.fromFile(file), title = title, artist = artist, folder = album, coverUri = null,
            folderPath = "Music/$album", size = file.length(), modified = file.lastModified(), tagged = true, durationMs = 60_000,
        )
    }

    /** Carries the tape whose card is described by [cardDesc] (by its cover) to the middle of the cassette window. */
    private fun carryToDeck(cardDesc: String, deck: android.graphics.Rect) {
        val bounds = find(cardDesc).visibleBounds
        val startX = bounds.left + (40 * density).toInt()
        val startY = bounds.centerY()
        device.drag(startX, startY, deck.centerX(), deck.centerY(), 60)
    }

    @Test
    fun theRack() {
        Thread.sleep(1500)
        dismissSystemBox()
        withVm {
            it.loadTracksForTest(
                listOf(
                    song("Alpha One", "Album A", "Band A", 330.0), song("Alpha Two", "Album A", "Band A", 440.0), song("Alpha Three", "Album A", "Band A", 550.0),
                    song("Beta One", "Album B", "Band B", 660.0), song("Beta Two", "Album B", "Band B", 770.0),
                    song("Lonely Song", "Singles", "Solo", 880.0),
                ),
            )
        }
        waitFor("the library") { vm.ui.value.tracks.size == 6 && vm.ui.value.tape.id == "library" }
        val deck = find("Cassette").visibleBounds

        // tap the cassette: the rack opens
        find("Cassette").click()
        findText("CASSETTE RACK")
        assertNotNull(device.wait(Until.findObject(By.text("ALBUMS 3")), 5_000))
        assertNotNull(device.findObject(By.text("SONGS 6")))
        assertNotNull(device.findObject(By.text("MIXTAPES 0")))
        shot("10-rack-albums")

        // carry Album B into the deck by its cover
        carryToDeck("Album B", deck)
        waitFor("Album B in the deck") { vm.ui.value.tape.id == "album:Music/Album B" }
        waitFor("its songs as the queue") { vm.ui.value.queue.size == 2 }
        assertEquals("a tape is put in, not played", Transport.STOPPED, vm.ui.value.transport)
        assertEquals("the library is untouched", 6, vm.ui.value.tracks.size)
        shot("11-album-in-deck")

        // PLAY plays it
        find("Play").click()
        waitFor("PLAY") { vm.ui.value.transport == Transport.PLAYING }
        waitFor("the first song") { vm.ui.value.title == "Beta One" }
        find("Stop, eject").click()
        waitFor("STOP") { vm.ui.value.transport == Transport.STOPPED }

        // the songs tab: a single song as a tape
        find("Cassette").click()
        findText("CASSETTE RACK")
        findText("SONGS 6").click()
        Thread.sleep(400)
        shot("12-rack-songs")
        carryToDeck("Lonely Song", deck)
        waitFor("a single in the deck") { vm.ui.value.tape.id.startsWith("song:") && vm.ui.value.queue.size == 1 }

        // hold a card to read it
        find("Cassette").click()
        findText("CASSETTE RACK")
        findText("ALBUMS 3").click()
        Thread.sleep(300)
        find("Album A").longClick()
        Thread.sleep(1000)
        shot("13-card")
        assertNotNull("the card did not open", device.wait(Until.findObject(By.text("INSERT")), 10_000))
        assertNotNull(device.findObject(By.text("Alpha Three")))
        findText("CLOSE").click()
        Thread.sleep(300)

        // the blank mixtape
        findText("MIXTAPES 0").click()
        Thread.sleep(300)
        shot("14-rack-mixtapes")
        find("Blank mixtape").click()
        findText("NEW MIXTAPE")
        // (the name is left blank: a blank name becomes "Mixtape 1")
        assertNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000))
        device.wait(Until.findObject(By.descStartsWith("Beta Two")), 10_000).click()
        device.wait(Until.findObject(By.descStartsWith("Alpha One")), 10_000).click()
        assertNotNull("the mixtape count", device.wait(Until.findObject(By.textStartsWith("ON THIS TAPE · 2")), 10_000))
        shot("15-mixtape-editor")
        findText("SAVE").click()
        waitFor("the mixtape") { vm.ui.value.mixtapes.size == 1 && vm.ui.value.mixtapes[0].name == "Mixtape 1" }
        assertEquals(2, vm.ui.value.mixtapes[0].keys.size)
        assertNotNull(device.wait(Until.findObject(By.text("MIXTAPES 1")), 10_000))
        shot("16-mixtape-in-rack")

        // and the mixtape goes in the deck, songs in the order they were picked
        carryToDeck("Mixtape 1", deck)
        waitFor("the mixtape in the deck") { vm.ui.value.tape.id.startsWith("mix:") && vm.ui.value.queue.size == 2 }
        find("Play").click()
        waitFor("the mixtape's first song") { vm.ui.value.transport == Transport.PLAYING && vm.ui.value.title == "Beta Two" }
        shot("17-mixtape-playing")
        assertTrue(vm.ui.value.tape.title == "Mixtape 1")
        find("Stop, eject").click()
    }
}
