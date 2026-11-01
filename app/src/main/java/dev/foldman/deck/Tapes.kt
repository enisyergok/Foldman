package dev.foldman.deck

/** What the cassette rack needs to know about a song. */
interface TapeSong {
    /** Identifies the song for good (its file address). */
    val key: String
    val title: String
    val artist: String?
    /** The album folder's name, as shown. */
    val folder: String
    /** Where that folder is under the music folder. */
    val folderPath: String
    val durationMs: Long
}

enum class TapeKind { LIBRARY, ALBUM, SONG, MIXTAPE }

/** A tape in the rack: an album, a single song, a mixtape, or everything on the phone. */
data class Tape<T : TapeSong>(
    val id: String,
    val kind: TapeKind,
    val title: String,
    val subtitle: String,
    val songs: List<T>,
) {
    val durationMs: Long get() = songs.sumOf { it.durationMs }
}

/** The tape that is in the deck, as the screen needs to know it. */
data class TapeInfo(val id: String = LIBRARY_ID, val kind: TapeKind = TapeKind.LIBRARY, val title: String = "") {
    companion object {
        const val LIBRARY_ID = "library"
    }
}

/** A mixtape the person made: a name and the songs on it, in order. */
data class Mixtape(val id: String, val name: String, val keys: List<String>)

/** Sorting a music folder into tapes. */
object Tapes {
    const val LIBRARY_ID = TapeInfo.LIBRARY_ID
    private const val ALBUM = "album:"
    private const val SONG = "song:"
    const val MIXTAPE = "mix:"

    fun albumId(folderPath: String) = ALBUM + folderPath
    fun songId(key: String) = SONG + key
    fun mixtapeId(id: String) = MIXTAPE + id

    /** Everything in the folder, as one long tape. */
    fun <T : TapeSong> library(songs: List<T>): Tape<T> =
        Tape(LIBRARY_ID, TapeKind.LIBRARY, "All music", countText(songs.size), songs)

    /** One tape per album folder, in the order the folders come. */
    fun <T : TapeSong> albums(songs: List<T>): List<Tape<T>> {
        val byFolder = LinkedHashMap<String, MutableList<T>>()
        for (s in songs) byFolder.getOrPut(s.folderPath) { mutableListOf() }.add(s)
        return byFolder.map { (path, list) ->
            val artists = list.mapNotNull { it.artist?.takeIf(String::isNotBlank) }.distinct()
            val artist = when {
                artists.size == 1 -> artists[0]
                artists.size > 1 -> "Various artists"
                else -> null
            }
            Tape(albumId(path), TapeKind.ALBUM, list.first().folder, listOfNotNull(artist, countText(list.size)).joinToString(" · "), list)
        }
    }

    /** One tape per song. */
    fun <T : TapeSong> songs(songs: List<T>): List<Tape<T>> =
        songs.map { Tape(songId(it.key), TapeKind.SONG, it.title, it.artist?.takeIf(String::isNotBlank) ?: it.folder, listOf(it)) }

    /** The mixtape with its songs found in [library]; songs that have gone from the folder are left out. */
    fun <T : TapeSong> mixtape(m: Mixtape, library: Map<String, T>): Tape<T> {
        val found = m.keys.mapNotNull { library[it] }
        val missing = m.keys.size - found.size
        val text = countText(found.size) + if (missing > 0) " · $missing missing" else ""
        return Tape(mixtapeId(m.id), TapeKind.MIXTAPE, m.name, text, found)
    }

    fun <T : TapeSong> mixtapes(list: List<Mixtape>, songs: List<T>): List<Tape<T>> {
        val byKey = songs.associateBy { it.key }
        return list.map { mixtape(it, byKey) }
    }

    /** The tape called [id], wherever it is kept; null if it has gone. */
    fun <T : TapeSong> find(id: String?, songs: List<T>, mixtapes: List<Mixtape>): Tape<T>? {
        if (id == null || songs.isEmpty() && !id.startsWith(MIXTAPE)) return null
        return when {
            id == LIBRARY_ID -> library(songs)
            id.startsWith(ALBUM) -> albums(songs).firstOrNull { it.id == id }
            id.startsWith(SONG) -> songs.firstOrNull { songId(it.key) == id }?.let { s ->
                Tape(id, TapeKind.SONG, s.title, s.artist?.takeIf(String::isNotBlank) ?: s.folder, listOf(s))
            }
            id.startsWith(MIXTAPE) -> mixtapes.firstOrNull { mixtapeId(it.id) == id }?.let { mixtape(it, songs.associateBy { s -> s.key }) }
            else -> null
        }
    }

    /** The tapes whose title or subtitle contains [query] (any case); all of them for a blank query. */
    fun <T : TapeSong> search(tapes: List<Tape<T>>, query: String): List<Tape<T>> {
        val q = query.trim()
        if (q.isEmpty()) return tapes
        return tapes.filter { it.title.contains(q, ignoreCase = true) || it.subtitle.contains(q, ignoreCase = true) }
    }

    fun countText(n: Int) = if (n == 1) "1 song" else "$n songs"

    /** "3:41", or "1:02:09" past an hour; "–" when the length is not known. */
    fun lengthText(ms: Long): String {
        if (ms <= 0) return "–"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
