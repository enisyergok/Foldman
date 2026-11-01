package dev.foldman.deck

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/** Where the tape in the deck comes from. */
enum class Source(val label: String, val packageName: String?) {
    LOCAL("Local folder", null),
    YT_MUSIC("YT Music", "com.google.android.apps.youtube.music"),
    APPLE_MUSIC("Apple Music", "com.apple.android.music"),
}

/**
 * What the mechanism is doing. PAUSED keeps the play key latched with the pinch roller lifted;
 * STOPPED releases every key. The reels animate from this.
 */
enum class Transport { STOPPED, PLAYING, PAUSED, REWINDING, FAST_FORWARDING }

sealed interface SourceStatus {
    data object Ready : SourceStatus
    data object NeedsFolder : SourceStatus
    data class Scanning(val found: Int) : SourceStatus
    data object EmptyFolder : SourceStatus
    data object NeedsAccess : SourceStatus
    data object AppNotRunning : SourceStatus
    data object NotInstalled : SourceStatus
    data object NeedsDeveloperToken : SourceStatus
    data object NeedsAppleSignIn : SourceStatus
}

/** The tape's character, from the settings sheet; all zero is the clean sound of the file. */
data class TapeCharacter(
    val wow: Float = 0f,
    val hiss: Float = 0f,
    val warmth: Float = 0f,
    val bump: Float = 0f,
    val replayGain: Boolean = false,
)

/** What the tape shelf knows about the user's Apple Music library (MusicKit sign-in + Apple Music API). */
data class AppleUi(
    /** MusicKit's auth AAR is in the build, so Foldman can sign in and browse. */
    val sdkAuth: Boolean = false,
    /** MusicKit's playback AAR is in the build, so Foldman streams in-app instead of driving the Apple Music app. */
    val sdkPlayback: Boolean = false,
    val hasDeveloperToken: Boolean = false,
    val signedIn: Boolean = false,
    val playlists: List<dev.foldman.apple.ApplePlaylist> = emptyList(),
    val openPlaylist: dev.foldman.apple.ApplePlaylist? = null,
    val tracks: List<dev.foldman.apple.AppleTrack> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
)

data class LocalTrack(
    val uri: Uri,
    override val title: String,
    override val artist: String?,
    /** The album folder's name, as shown. */
    override val folder: String,
    val coverUri: Uri?,
    /** Where that folder is under the music folder, so two albums with the same name stay apart. */
    override val folderPath: String = folder,
    val size: Long = 0,
    val modified: Long = 0,
    /** The title and artist come from the file's tags (or it has none), not just from its name. */
    val tagged: Boolean = false,
    override val durationMs: Long = 0,
) : TapeSong {
    override val key: String get() = uri.toString()
}

/** A song in the play queue: a local file (id = index) or another app's queue item. */
data class QueueEntry(
    val id: Long,
    val title: String,
    val subtitle: String?,
    val artwork: Bitmap? = null,
    val artworkUri: Uri? = null,
)

data class NowPlaying(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artwork: Bitmap? = null,
    /** Changes whenever the artwork does, so the label palette is only worked out once per cover. */
    val artworkKey: Any? = null,
    val durationMs: Long = 0,
)

data class BackendState(
    val status: SourceStatus = SourceStatus.Ready,
    val nowPlaying: NowPlaying = NowPlaying(),
    val isPlaying: Boolean = false,
    val ended: Boolean = false,
    /** The player is meant to be playing (even if it is momentarily buffering or has just reached the end). */
    val wantsPlay: Boolean = false,
    val trackIndex: Int = -1,
    val trackCount: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val queue: List<QueueEntry> = emptyList(),
    val currentQueueId: Long = -1,
)

/** One tape source: the local Media3 player, or another app's media session. */
interface DeckBackend {
    val state: StateFlow<BackendState>
    fun positionMs(): Long
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun skipToNext()
    fun skipToPrevious()
    fun playQueueItem(id: Long)
    /** Re-checks permissions and sessions, e.g. when the app comes back to the foreground. */
    fun refresh() {}
    fun release()
}
