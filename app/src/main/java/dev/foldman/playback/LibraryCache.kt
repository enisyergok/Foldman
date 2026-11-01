package dev.foldman.playback

import android.content.Context
import android.net.Uri
import dev.foldman.deck.LocalTrack
import java.io.File
import java.security.MessageDigest

/**
 * Remembers what was found in a music folder, tags included, so the next start shows the library at
 * once and only new or changed files have to be read again.
 */
class LibraryCache(private val context: Context) {
    private fun file(tree: Uri): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(tree.toString().toByteArray())
        return File(context.filesDir, "library-" + digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    fun load(tree: Uri): List<LocalTrack>? {
        val f = file(tree)
        if (!f.isFile) return null
        val cached = runCatching { LibraryJson.decode(f.readText()) }.getOrNull() ?: return null
        return cached.map {
            LocalTrack(
                uri = Uri.parse(it.uri), title = it.title, artist = it.artist, folder = it.folder,
                coverUri = it.cover?.let(Uri::parse), folderPath = it.folderPath, size = it.size,
                modified = it.modified, tagged = it.tagged, durationMs = it.durationMs,
            )
        }
    }

    /** Written to a temporary file first, so a crash can never leave half a cache behind. */
    fun save(tree: Uri, tracks: List<LocalTrack>) {
        runCatching {
            val text = LibraryJson.encode(
                tracks.map {
                    CachedTrack(
                        it.uri.toString(), it.title, it.artist, it.folder, it.folderPath,
                        it.coverUri?.toString(), it.size, it.modified, it.tagged, it.durationMs,
                    )
                },
            )
            val target = file(tree)
            val temp = File(target.path + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }
}
