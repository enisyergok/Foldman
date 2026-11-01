package dev.foldman.apple

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import dev.foldman.deck.BackendState
import dev.foldman.deck.DeckBackend
import dev.foldman.deck.NowPlaying
import dev.foldman.deck.QueueEntry
import dev.foldman.deck.SourceStatus
import dev.foldman.playback.Artwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import dev.foldman.apple.ApplePlayerProtocol as P

/**
 * Apple Music streamed by MusicKit's player, which lives in [ApplePlayerService]'s own process so
 * a crash in Apple's native code can't take Foldman down. The deck owns the tape: art, queue,
 * transport and REW/FF act on it directly. If the player's process dies mid-song, the system
 * restarts the service and this puts the same queue back at the same song and position.
 */
class AppleKitBackend(private val context: Context) : DeckBackend {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(BackendState(status = SourceStatus.NeedsDeveloperToken))
    override val state: StateFlow<BackendState> = _state.asStateFlow()

    /** Problems worth telling the user about (player failed to start, was restarted…). */
    val messages = MutableStateFlow<String?>(null)

    private var developerToken: String? = null
    private var userToken: String? = null

    private var service: Messenger? = null
    private var bound = false
    private var snapshot: Bundle? = null

    // What's on the tape, kept so it can be put back after a crash.
    private var songs: List<String> = emptyList()
    private var songIndex = 0
    private var restoreAfterCrash = false
    private val waiting = ArrayDeque<Pair<Int, Bundle?>>()

    private var artworkUrl: String? = null
    private var artwork: Bitmap? = null
    private var artworkJob: Job? = null

    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                P.MSG_STATE -> onSnapshot(msg.data)
                P.MSG_ERROR -> messages.value = msg.data.getString(P.ERROR)
            }
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = Messenger(binder)
            send(P.MSG_REGISTER)
            sendTokens()
            if (restoreAfterCrash && songs.isNotEmpty()) {
                restoreAfterCrash = false
                val s = snapshot
                send(P.MSG_PLAY_SONGS, Bundle().apply {
                    putStringArray(P.IDS, songs.toTypedArray())
                    putInt(P.INDEX, songIndex.coerceIn(0, songs.lastIndex))
                    putLong(P.POSITION, s?.let(::position) ?: -1L)
                    putBoolean(P.PLAY, s?.getBoolean(P.PLAYING) ?: true)
                })
                messages.value = "Apple's player stopped unexpectedly and was restarted."
            }
            sendWaiting()
            publish()
        }

        // The player's process died. The system restarts the service and calls onServiceConnected again.
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            restoreAfterCrash = songs.isNotEmpty()
            snapshot = snapshot?.let { Bundle(it).apply { putLong(P.POSITION, position(it)); putLong(P.AT, SystemClock.elapsedRealtime()) } }
            publish()
        }

        override fun onBindingDied(name: ComponentName?) {
            unbind()
            bind()
        }
    }

    fun setTokens(developer: String?, user: String?) {
        developerToken = developer
        userToken = user
        if (developer != null && user != null) bind() else unbind()
        sendTokens()
        publish()
    }

    /** Streams [catalogIds] from [startIndex]. */
    fun playSongs(catalogIds: List<String>, startIndex: Int) {
        songs = catalogIds
        songIndex = startIndex
        bind()
        send(P.MSG_PLAY_SONGS, Bundle().apply {
            putStringArray(P.IDS, catalogIds.toTypedArray())
            putInt(P.INDEX, startIndex)
            putBoolean(P.PLAY, true)
        })
    }

    private fun bind() {
        if (bound) return
        bound = context.bindService(Intent(context, ApplePlayerService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun unbind() {
        waiting.clear()
        if (!bound) return
        runCatching { context.unbindService(connection) }
        bound = false
        service = null
    }

    private fun sendTokens() = send(P.MSG_TOKENS, Bundle().apply {
        putString(P.DEV, developerToken)
        putString(P.USER, userToken)
    })

    private fun send(what: Int, data: Bundle? = null) {
        val s = service
        if (s == null) {
            holdUntilConnected(what, data)
            return
        }
        try {
            s.send(Message.obtain(null, what).apply {
                replyTo = replies
                data?.let { this.data = it }
            })
        } catch (_: RemoteException) {
            // The process is gone; onServiceDisconnected handles the restart.
        }
    }

    /** Commands that arrive before the player's process is up (or while it restarts) wait for it. */
    private fun holdUntilConnected(what: Int, data: Bundle?) {
        // These are sent anyway the moment the connection is made.
        if (what == P.MSG_REGISTER || what == P.MSG_TOKENS) return
        // A new queue replaces whatever was waiting.
        if (what == P.MSG_PLAY_SONGS) waiting.clear()
        if (waiting.size >= MAX_WAITING) waiting.removeFirst()
        waiting.addLast(what to data?.let { Bundle(it) })
    }

    private fun sendWaiting() {
        val items = waiting.toList()
        waiting.clear()
        for ((what, data) in items) send(what, data)
    }

    private fun onSnapshot(data: Bundle) {
        snapshot = data
        val index = data.getInt(P.INDEX, -1)
        if (index >= 0) songIndex = index
        updateArtwork(data.getString(P.ART))
        publish()
    }

    private fun position(s: Bundle): Long {
        val pos = s.getLong(P.POSITION)
        return if (s.getBoolean(P.PLAYING)) pos + (SystemClock.elapsedRealtime() - s.getLong(P.AT)) else pos
    }

    private fun publish() {
        val status = when {
            developerToken == null -> SourceStatus.NeedsDeveloperToken
            userToken == null -> SourceStatus.NeedsAppleSignIn
            else -> SourceStatus.Ready
        }
        val s = snapshot
        if (s == null) {
            _state.value = BackendState(status = status)
            return
        }
        val ids = s.getLongArray(P.QUEUE_IDS) ?: LongArray(0)
        val titles = s.getStringArray(P.QUEUE_TITLES).orEmpty()
        val artists = s.getStringArray(P.QUEUE_ARTISTS).orEmpty()
        val arts = s.getStringArray(P.QUEUE_ART).orEmpty()
        val index = s.getInt(P.INDEX, -1)
        val alive = service != null
        _state.value = BackendState(
            status = status,
            nowPlaying = NowPlaying(
                title = s.getString(P.TITLE),
                artist = s.getString(P.ARTIST),
                album = s.getString(P.ALBUM),
                artwork = artwork,
                artworkKey = artworkUrl.takeIf { artwork != null },
                durationMs = s.getLong(P.DURATION),
            ),
            isPlaying = alive && (s.getBoolean(P.PLAYING) || s.getBoolean(P.BUFFERING)),
            trackIndex = index,
            trackCount = s.getInt(P.COUNT, ids.size),
            hasNext = alive && s.getBoolean(P.HAS_NEXT),
            hasPrevious = alive && s.getBoolean(P.HAS_PREVIOUS),
            queue = ids.indices.map { i ->
                QueueEntry(
                    ids[i], titles.getOrNull(i).orEmpty(), artists.getOrNull(i)?.takeIf { it.isNotEmpty() },
                    artworkUri = arts.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let(Uri::parse),
                )
            },
            currentQueueId = s.getLong(P.CURRENT_ID, -1L),
        )
    }

    private fun updateArtwork(url: String?) {
        if (url == artworkUrl) return
        artworkUrl = url
        artwork = null
        artworkJob?.cancel()
        if (url == null) return
        artworkJob = scope.launch {
            val bmp = Artwork.load(context, Uri.parse(url))
            if (artworkUrl == url) {
                artwork = bmp
                publish()
            }
        }
    }

    override fun positionMs(): Long = snapshot?.let(::position) ?: 0L
    override fun play() = send(P.MSG_PLAY)
    override fun pause() = send(P.MSG_PAUSE)
    override fun seekTo(positionMs: Long) = send(P.MSG_SEEK, Bundle().apply { putLong(P.POSITION, positionMs) })
    override fun skipToNext() = send(P.MSG_NEXT)
    override fun skipToPrevious() = send(P.MSG_PREVIOUS)
    override fun playQueueItem(id: Long) = send(P.MSG_SKIP_TO, Bundle().apply { putLong(P.ID, id) })

    private companion object {
        const val MAX_WAITING = 16
    }

    override fun release() {
        unbind()
        scope.cancel()
    }
}
