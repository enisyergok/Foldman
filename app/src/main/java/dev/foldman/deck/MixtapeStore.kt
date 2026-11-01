package dev.foldman.deck

import android.content.Context
import java.io.File

/** Keeps the mixtapes in the app's private storage. */
class MixtapeStore(context: Context) {
    private val file = File(context.filesDir, "mixtapes.json")

    fun load(): List<Mixtape> {
        if (!file.isFile) return emptyList()
        return runCatching { MixtapeJson.decode(file.readText()) }.getOrNull() ?: emptyList()
    }

    /** Written to a temporary file first, so a crash can never leave half a file behind. */
    fun save(list: List<Mixtape>) {
        runCatching {
            val temp = File(file.path + ".tmp")
            temp.writeText(MixtapeJson.encode(list))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }
}
