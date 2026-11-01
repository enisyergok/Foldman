package dev.foldman.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.audiofx.Equalizer
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.foldman.MainActivity
import dev.foldman.deck.DeckViewModel

/** Plays the local folder in the background, with the system media notification and controls. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var equalizer: Equalizer? = null
    private lateinit var prefs: SharedPreferences
    private val toneListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DeckViewModel.KEY_TAPE_TYPE || key == DeckViewModel.KEY_NOISE_REDUCTION) applyTone()
    }

    override fun onCreate() {
        super.onCreate()
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(AudioLevels)))
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
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()

        prefs = getSharedPreferences("foldman", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(toneListener)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = attachTone(audioSessionId)
        })
        attachTone(player.audioSessionId)
    }

    /** The deck's tape type and NR switch, as an equaliser on the player's own audio session. */
    private fun attachTone(sessionId: Int) {
        equalizer?.let { runCatching { it.release() } }
        equalizer = null
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        equalizer = runCatching { Equalizer(0, sessionId).apply { enabled = true } }.getOrNull()
        applyTone()
    }

    private fun applyTone() {
        val eq = equalizer ?: return
        val type = prefs.getInt(DeckViewModel.KEY_TAPE_TYPE, 0)
        val reduction = prefs.getBoolean(DeckViewModel.KEY_NOISE_REDUCTION, false)
        runCatching {
            val range = eq.bandLevelRange
            for (band in 0 until eq.numberOfBands) {
                val hz = eq.getCenterFreq(band.toShort()) / 1000
                var gain = 0
                when (type) {
                    // CrO2: brighter, with a little more air.
                    1 -> gain += if (hz >= 3000) 450 else if (hz >= 1000) 150 else 0
                    // Metal: the widest response, fuller at both ends.
                    2 -> gain += if (hz < 250) 500 else if (hz >= 3000) 500 else 0
                }
                // NR: takes the hiss band down.
                if (reduction && hz >= 6000) gain -= 600
                eq.setBandLevel(band.toShort(), gain.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(toneListener)
        equalizer?.let { runCatching { it.release() } }
        equalizer = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
