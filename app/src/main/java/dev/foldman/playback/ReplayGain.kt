package dev.foldman.playback

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment

/** Finds a song's ReplayGain value in its tags, so quiet and loud albums can be brought level. */
object ReplayGain {
    private val NUMBER = Regex("""[-+]?\d+(?:[.,]\d+)?""")

    /** "-6.54 dB" → -6.54; null when there is no number in [text]. */
    fun parse(text: String?): Float? {
        val match = NUMBER.find(text ?: return null) ?: return null
        val value = match.value.replace(',', '.').toFloatOrNull() ?: return null
        return value.takeIf { it.isFinite() }
    }

    /** The track gain in dB, or the album gain when the track has none; null when untagged. */
    @OptIn(UnstableApi::class)
    fun fromMetadata(metadata: Metadata): Float? {
        var track: Float? = null
        var album: Float? = null
        for (i in 0 until metadata.length()) {
            val (name, value) = when (val entry = metadata.get(i)) {
                is TextInformationFrame -> (entry.description ?: "") to entry.values.firstOrNull()
                is VorbisComment -> entry.key to entry.value
                is InternalFrame -> entry.description to entry.text
                else -> continue
            }
            when (name.uppercase()) {
                "REPLAYGAIN_TRACK_GAIN" -> track = parse(value) ?: track
                "REPLAYGAIN_ALBUM_GAIN" -> album = parse(value) ?: album
            }
        }
        return track ?: album
    }
}
