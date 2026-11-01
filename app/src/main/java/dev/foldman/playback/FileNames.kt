package dev.foldman.playback

/** Reading a song's title out of its file name. */
object FileNames {
    private val TRACK_NUMBER = Regex("""^\s*(\d{1,3}|[A-D]\d{1,2})\s*[-._)\s]\s*""")

    /** "03 - Blue Monday.flac" → "Blue Monday". */
    fun title(name: String): String {
        val stem = name.substringBeforeLast('.')
        return stem.replace(TRACK_NUMBER, "").replace('_', ' ').trim().ifEmpty { stem }
    }
}
