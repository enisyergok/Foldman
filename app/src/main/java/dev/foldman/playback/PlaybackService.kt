package dev.foldman.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.foldman.MainActivity

/** Plays the local folder in the background, with the system media notification and controls. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var prefs: SharedPreferences
    /** ReplayGain of the song that is playing, from its tags (0 when it has none). */
    private var trackGainDb = 0f
    private val toneListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in TapeKeys.ALL) refreshTone()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("foldman", Context.MODE_PRIVATE)
        refreshTone()
        prefs.registerOnSharedPreferenceChangeListener(toneListener)

        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                // The tape first, then the tap that feeds the VU lamps, so they show what you hear.
                .setAudioProcessors(arrayOf<AudioProcessor>(TapeAudioProcessor(), TeeAudioProcessor(AudioLevels)))
                .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) = readGain(tracks)
        })
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
    }

    private fun refreshTone() {
        TapeParams.current = TapeKeys.load(prefs, trackGainDb)
    }

    private fun readGain(tracks: Tracks) {
        var gain: Float? = null
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO || !group.isSelected) continue
            for (i in 0 until group.length) {
                val metadata = group.getTrackFormat(i).metadata ?: continue
                gain = ReplayGain.fromMetadata(metadata) ?: gain
            }
        }
        trackGainDb = gain ?: 0f
        refreshTone()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(toneListener)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
