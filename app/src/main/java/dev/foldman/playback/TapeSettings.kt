package dev.foldman.playback

import android.content.SharedPreferences

/** The tape's character, as one immutable snapshot the audio thread can read without locking. */
data class TapeSettings(
    /** 0 NORMAL, 1 CrO2, 2 METAL. */
    val type: Int = 0,
    /** The NR switch: expands the treble away from the hiss. */
    val noiseReduction: Boolean = false,
    /** Wow and flutter, 0..1. */
    val wow: Float = 0f,
    /** Tape hiss, 0..1. */
    val hiss: Float = 0f,
    /** Tape saturation, 0..1. */
    val warmth: Float = 0f,
    /** Low-end head bump, 0..1. */
    val bump: Float = 0f,
    /** Level change in dB (ReplayGain), already decided. */
    val gainDb: Float = 0f,
) {
    /** True when the settings leave the sound exactly as it is on the file. */
    val isNeutral: Boolean
        get() = type == 0 && !noiseReduction && wow <= 0f && hiss <= 0f && warmth <= 0f && bump <= 0f && gainDb == 0f
}

/** Preference keys and defaults for the tape settings. */
object TapeKeys {
    const val TYPE = "tape_type"
    const val NOISE_REDUCTION = "noise_reduction"
    const val WOW = "tape_wow"
    const val HISS = "tape_hiss"
    const val WARMTH = "tape_warmth"
    const val BUMP = "tape_bump"
    const val REPLAY_GAIN = "replay_gain"
    const val AUTO_REVERSE = "auto_reverse"

    /** Every key whose change should refresh the audio engine. */
    val ALL = setOf(TYPE, NOISE_REDUCTION, WOW, HISS, WARMTH, BUMP, REPLAY_GAIN)

    fun load(prefs: SharedPreferences, trackGainDb: Float = 0f): TapeSettings = TapeSettings(
        type = prefs.getInt(TYPE, 0).coerceIn(0, 2),
        noiseReduction = prefs.getBoolean(NOISE_REDUCTION, false),
        wow = prefs.getFloat(WOW, 0f).coerceIn(0f, 1f),
        hiss = prefs.getFloat(HISS, 0f).coerceIn(0f, 1f),
        warmth = prefs.getFloat(WARMTH, 0f).coerceIn(0f, 1f),
        bump = prefs.getFloat(BUMP, 0f).coerceIn(0f, 1f),
        gainDb = if (prefs.getBoolean(REPLAY_GAIN, false)) trackGainDb.coerceIn(-24f, 12f) else 0f,
    )
}

/** Where the audio thread finds the current settings. */
object TapeParams {
    @Volatile
    var current: TapeSettings = TapeSettings()
}
