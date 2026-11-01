package dev.foldman.deck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapesTest {
    private data class Song(
        override val key: String,
        override val title: String,
        override val artist: String? = null,
        override val folder: String = "Album",
        override val folderPath: String = "Music/Album",
        override val durationMs: Long = 60_000,
    ) : TapeSong

    private val songs = listOf(
        Song("a1", "Blue Monday", "New Order", "Power", "Music/Power", 449_000),
        Song("a2", "Age of Consent", "New Order", "Power", "Music/Power", 315_000),
        Song("b1", "Heart of Glass", "Blondie", "Parallel Lines", "Music/Parallel Lines", 234_000),
        Song("b2", "One Way or Another", "Blondie", "Parallel Lines", "Music/Parallel Lines", 215_000),
        Song("c1", "Ghost", null, "Singles", "Music/Singles", 180_000),
        Song("d1", "Intro", "Various", "CD1", "Music/Box A/CD1"),
        Song("d2", "Intro", "Other", "CD1", "Music/Box B/CD1"),
    )

    @Test
    fun albumsAreTheFoldersInTheirOwnOrder() {
        val albums = Tapes.albums(songs)
        assertEquals(listOf("Power", "Parallel Lines", "Singles", "CD1", "CD1"), albums.map { it.title })
        assertEquals(listOf(2, 2, 1, 1, 1), albums.map { it.songs.size })
        assertTrue(albums.all { it.kind == TapeKind.ALBUM })
    }

    @Test
    fun twoAlbumsWithTheSameFolderNameStayTwoTapes() {
        val cds = Tapes.albums(songs).filter { it.title == "CD1" }
        assertEquals(2, cds.size)
        assertEquals(2, cds.map { it.id }.toSet().size)
    }

    @Test
    fun albumSubtitleNamesTheArtistOrSaysVarious() {
        val power = Tapes.albums(songs).first { it.title == "Power" }
        assertEquals("New Order · 2 songs", power.subtitle)
        val mixed = Tapes.albums(listOf(Song("x", "A", "One", folderPath = "m"), Song("y", "B", "Two", folderPath = "m")))
        assertEquals("Various artists · 2 songs", mixed.single().subtitle)
        val unknown = Tapes.albums(listOf(Song("z", "C", null, folderPath = "n")))
        assertEquals("1 song", unknown.single().subtitle)
    }

    @Test
    fun albumLengthIsTheSumOfItsSongs() {
        assertEquals(449_000L + 315_000L, Tapes.albums(songs).first().durationMs)
    }

    @Test
    fun everySongIsATapeOfItsOwn() {
        val tapes = Tapes.songs(songs)
        assertEquals(songs.size, tapes.size)
        assertTrue(tapes.all { it.kind == TapeKind.SONG && it.songs.size == 1 })
        assertEquals("New Order", tapes[0].subtitle)
        assertEquals("Singles", tapes[4].subtitle) // no artist: the album shows instead
    }

    @Test
    fun theLibraryIsOneTapeOfEverything() {
        val all = Tapes.library(songs)
        assertEquals(TapeKind.LIBRARY, all.kind)
        assertEquals(songs.size, all.songs.size)
        assertEquals("7 songs", all.subtitle)
    }

    @Test
    fun aMixtapeKeepsItsOrderAndSkipsSongsThatHaveGone() {
        val byKey = songs.associateBy { it.key }
        val tape = Tapes.mixtape(Mixtape("m1", "Road trip", listOf("c1", "gone", "a1", "b2")), byKey)
        assertEquals(listOf("c1", "a1", "b2"), tape.songs.map { it.key })
        assertEquals("3 songs · 1 missing", tape.subtitle)
        assertEquals("mix:m1", tape.id)
        assertEquals(TapeKind.MIXTAPE, tape.kind)
    }

    @Test
    fun findLooksTapesUpByTheirId() {
        val mixes = listOf(Mixtape("m1", "Mix", listOf("a1", "b1")))
        assertEquals(TapeKind.LIBRARY, Tapes.find("library", songs, mixes)!!.kind)
        assertEquals("Power", Tapes.find(Tapes.albumId("Music/Power"), songs, mixes)!!.title)
        assertEquals("Ghost", Tapes.find(Tapes.songId("c1"), songs, mixes)!!.title)
        assertEquals(listOf("a1", "b1"), Tapes.find("mix:m1", songs, mixes)!!.songs.map { it.key })
        assertNull(Tapes.find("album:Music/Nope", songs, mixes))
        assertNull(Tapes.find("song:nope", songs, mixes))
        assertNull(Tapes.find("mix:nope", songs, mixes))
        assertNull(Tapes.find(null, songs, mixes))
        assertNull(Tapes.find("what", songs, mixes))
        assertNull(Tapes.find("library", emptyList<Song>(), mixes))
        assertNotNull(Tapes.find("mix:m1", songs, mixes))
    }

    @Test
    fun searchIgnoresCaseAndLooksAtTheSubtitleToo() {
        val tapes = Tapes.songs(songs)
        assertEquals(listOf("Blue Monday", "Age of Consent"), Tapes.search(tapes, "new order").map { it.title })
        assertEquals(listOf("Heart of Glass"), Tapes.search(tapes, "  GLASS ").map { it.title })
        assertEquals(tapes.size, Tapes.search(tapes, "").size)
        assertEquals(0, Tapes.search(tapes, "zzz").size)
    }

    @Test
    fun lengthsReadAsClockTime() {
        assertEquals("–", Tapes.lengthText(0))
        assertEquals("0:09", Tapes.lengthText(9_400))
        assertEquals("3:41", Tapes.lengthText(221_000))
        assertEquals("1:02:09", Tapes.lengthText(3_729_000))
        assertEquals("1 song", Tapes.countText(1))
        assertEquals("0 songs", Tapes.countText(0))
    }

    @Test
    fun mixtapeFileRoundTripsAndRefusesDamage() {
        val list = listOf(Mixtape("1", "Road \"trip\" ✓", listOf("a", "b/c d")), Mixtape("2", "Empty", emptyList()))
        assertEquals(list, MixtapeJson.decode(MixtapeJson.encode(list)))
        assertEquals(emptyList<Mixtape>(), MixtapeJson.decode(MixtapeJson.encode(emptyList())))
        assertNull(MixtapeJson.decode(""))
        assertNull(MixtapeJson.decode("{}"))
        assertNull(MixtapeJson.decode("""{"version":9,"mixtapes":[]}"""))
        assertNull(MixtapeJson.decode(MixtapeJson.encode(list).dropLast(12)))
    }
}
