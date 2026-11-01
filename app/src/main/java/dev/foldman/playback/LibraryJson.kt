package dev.foldman.playback

import org.json.JSONArray
import org.json.JSONObject

/** One song as the cache keeps it: plain strings and numbers, so it can be written and read back anywhere. */
data class CachedTrack(
    val uri: String,
    val title: String,
    val artist: String?,
    val folder: String,
    val folderPath: String,
    val cover: String?,
    val size: Long,
    val modified: Long,
    val tagged: Boolean,
    val durationMs: Long = 0,
)

/** The cache file's format. */
object LibraryJson {
    /** 2: songs carry their length. */
    const val VERSION = 2

    fun encode(tracks: List<CachedTrack>): String {
        val array = JSONArray()
        for (t in tracks) {
            array.put(
                JSONObject()
                    .put("u", t.uri).put("t", t.title).put("a", t.artist ?: JSONObject.NULL)
                    .put("f", t.folder).put("p", t.folderPath).put("c", t.cover ?: JSONObject.NULL)
                    .put("s", t.size).put("m", t.modified).put("g", t.tagged).put("d", t.durationMs),
            )
        }
        return JSONObject().put("version", VERSION).put("tracks", array).toString()
    }

    /** The songs in [text], or null when it is not a cache this version understands. */
    fun decode(text: String): List<CachedTrack>? = runCatching {
        val root = JSONObject(text)
        if (root.getInt("version") != VERSION) return@runCatching null
        val array = root.getJSONArray("tracks")
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            CachedTrack(
                uri = o.getString("u"),
                title = o.getString("t"),
                artist = if (o.isNull("a")) null else o.getString("a"),
                folder = o.getString("f"),
                folderPath = o.getString("p"),
                cover = if (o.isNull("c")) null else o.getString("c"),
                size = o.getLong("s"),
                modified = o.getLong("m"),
                tagged = o.getBoolean("g"),
                durationMs = o.optLong("d", 0L),
            )
        }
    }.getOrNull()
}
