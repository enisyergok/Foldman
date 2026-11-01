package dev.foldman.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamesTest {
    @Test
    fun stripsTrackNumbersAndTheExtension() {
        assertEquals("Blue Monday", FileNames.title("03 - Blue Monday.flac"))
        assertEquals("Blue Monday", FileNames.title("3. Blue Monday.mp3"))
        assertEquals("Blue Monday", FileNames.title("A1 Blue Monday.wav"))
        assertEquals("Blue Monday", FileNames.title("Blue_Monday.m4a"))
        assertEquals("Blue Monday", FileNames.title("012_Blue_Monday.ogg"))
    }

    @Test
    fun keepsNamesThatAreOnlyANumber() {
        assertEquals("1999", FileNames.title("1999.mp3"))
        assertEquals("7", FileNames.title("7.flac"))
    }

    @Test
    fun keepsNamesWithoutAnExtension() {
        assertEquals("Song", FileNames.title("Song"))
    }
}
