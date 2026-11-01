package dev.foldman.playback

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import dev.foldman.deck.LocalTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** Walks a folder picked with the system file picker and finds the music (and cover art) in it. */
class LocalLibrary(private val context: Context) {

    /**
     * Every audio file under [tree], album folders in order, tracks in natural order within them.
     * Files that are unchanged since [previous] keep the tags found then.
     */
    suspend fun scan(
        tree: Uri,
        previous: List<LocalTrack> = emptyList(),
        onProgress: (Int) -> Unit = {},
    ): List<LocalTrack> = withContext(Dispatchers.IO) {
        val known = previous.associateBy { it.uri.toString() }
        val found = mutableListOf<LocalTrack>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = displayName(tree, rootId) ?: "Music"
        walk(tree, rootId, rootName, rootName, known, found, onProgress)
        found.sortedWith(compareBy<LocalTrack, String>(NaturalOrder) { it.folderPath }.thenBy(NaturalOrder) { it.uri.lastPathSegment ?: "" })
    }

    /**
     * Fills in tag titles and artists, which file names only approximate, for every file that has not
     * been read yet. Three files are read at a time; [onProgress] gets (done, total).
     */
    suspend fun readTags(tracks: List<LocalTrack>, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<LocalTrack> = coroutineScope {
        val pending = tracks.indices.filter { !tracks[it].tagged }
        if (pending.isEmpty()) return@coroutineScope tracks
        val result = arrayOfNulls<LocalTrack>(tracks.size)
        val done = AtomicInteger(0)
        val gate = Semaphore(3)
        pending.chunked(32).map { chunk ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val retriever = MediaMetadataRetriever()
                    try {
                        for (i in chunk) {
                            ensureActive()
                            result[i] = readOne(retriever, tracks[i])
                            onProgress(done.incrementAndGet(), pending.size)
                        }
                    } finally {
                        retriever.release()
                    }
                }
            }
        }.awaitAll()
        List(tracks.size) { result[it] ?: tracks[it] }
    }

    private fun readOne(retriever: MediaMetadataRetriever, track: LocalTrack): LocalTrack = runCatching {
        retriever.setDataSource(context, track.uri)
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
        val length = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        track.copy(
            title = title?.takeIf { it.isNotBlank() } ?: track.title,
            artist = artist?.takeIf { it.isNotBlank() } ?: track.artist,
            tagged = true,
            durationMs = length.coerceAtLeast(0L),
        )
        // A file that can't be read is marked done too, so it isn't tried again until it changes.
    }.getOrDefault(track.copy(tagged = true))

    private suspend fun walk(
        tree: Uri, docId: String, folder: String, path: String, known: Map<String, LocalTrack>,
        out: MutableList<LocalTrack>, onProgress: (Int) -> Unit,
    ) {
        coroutineContext.ensureActive()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val dirs = mutableListOf<Pair<String, String>>()
        val audio = mutableListOf<Triple<String, String, Pair<Long, Long>>>()
        var cover: Uri? = null
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val ext = name.substringAfterLast('.', "").lowercase()
                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR -> if (!name.startsWith(".")) dirs += id to name
                    mime.startsWith("audio/") || ext in AUDIO_EXTENSIONS -> audio += Triple(id, name, c.getLong(3) to c.getLong(4))
                    mime.startsWith("image/") && name.substringBeforeLast('.').lowercase() in COVER_NAMES ->
                        cover = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                }
            }
        }
        for ((id, name, stamp) in audio) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            val old = known[uri.toString()]
            out += if (old != null && old.tagged && old.size == stamp.first && old.modified == stamp.second) {
                // Unchanged: keep its tags, but take the folder and cover as they are now.
                old.copy(folder = folder, folderPath = path, coverUri = cover)
            } else {
                LocalTrack(
                    uri = uri, title = titleFromFileName(name), artist = null, folder = folder, coverUri = cover,
                    folderPath = path, size = stamp.first, modified = stamp.second, tagged = false,
                )
            }
        }
        if (audio.isNotEmpty()) onProgress(out.size)
        for ((id, name) in dirs) walk(tree, id, name, "$path/$name", known, out, onProgress)
    }

    private fun displayName(tree: Uri, docId: String): String? = runCatching {
        context.contentResolver.query(
            DocumentsContract.buildDocumentUriUsingTree(tree, docId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    companion object {
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "alac", "aiff", "aif", "mka")
        private val COVER_NAMES = setOf("cover", "folder", "front", "album", "albumart", "albumartsmall")

        /** "03 - Blue Monday.flac" → "Blue Monday". */
        fun titleFromFileName(name: String): String = FileNames.title(name)
    }
}
