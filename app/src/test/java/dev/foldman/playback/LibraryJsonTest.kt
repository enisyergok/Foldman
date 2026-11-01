package dev.foldman.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryJsonTest {
    private val sample = listOf(
        CachedTrack("content://x/doc/1", "Blue Monday", "New Order", "Power", "Music/Power", "content://x/doc/c", 123456L, 1700000000000L, true),
        CachedTrack("content://x/doc/2", "Weird \"Title\" \\ with / chars ✓", null, "Disc 1", "Music/A/Disc 1", null, 0L, 0L, false),
    )

    @Test
    fun roundTripKeepsEverything() {
        assertEquals(sample, LibraryJson.decode(LibraryJson.encode(sample)))
    }

    @Test
    fun emptyListRoundTrips() {
        assertEquals(emptyList<CachedTrack>(), LibraryJson.decode(LibraryJson.encode(emptyList())))
    }

    @Test
    fun damagedOrForeignCachesAreRefusedNotTrusted() {
        assertNull(LibraryJson.decode(""))
        assertNull(LibraryJson.decode("not json"))
        assertNull(LibraryJson.decode("""{"version":99,"tracks":[]}"""))
        assertNull(LibraryJson.decode("""{"version":1}"""))
        assertNull(LibraryJson.decode("""{"version":1,"tracks":[{"u":"only a uri"}]}"""))
        // cut off in the middle, as a crash during writing would leave it
        assertNull(LibraryJson.decode(LibraryJson.encode(sample).dropLast(20)))
    }

    @Test
    fun tracksWithTheSameFolderNameStayApart() {
        val a = sample[0].copy(folder = "CD1", folderPath = "Music/Album A/CD1")
        val b = sample[0].copy(uri = "content://x/doc/9", folder = "CD1", folderPath = "Music/Album B/CD1")
        val back = LibraryJson.decode(LibraryJson.encode(listOf(a, b)))!!
        assertEquals(2, back.map { it.folderPath }.toSet().size)
    }
}
