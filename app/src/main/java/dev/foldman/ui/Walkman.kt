package dev.foldman.ui

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.foldman.R
import dev.foldman.deck.DeckUi
import dev.foldman.deck.SourceStatus
import dev.foldman.deck.Transport
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the player's controls do; the screen wires these to the view model. */
class WalkmanActions(
    val play: () -> Unit,
    val pause: () -> Unit,
    val stop: () -> Unit,
    val rewind: () -> Unit,
    val fastForward: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val volume: (Float) -> Unit,
    val seek: (Float) -> Unit,
    val tapeType: (Int) -> Unit,
    val noiseReduction: (Boolean) -> Unit,
    val monitor: (Boolean) -> Unit,
    val openShelf: () -> Unit,
    val openSettings: () -> Unit,
)

// The artwork is 941 x 1672; every coordinate below is in those pixels, as the artwork is drawn. On a
// taller screen body is added above the brand name and between the window and the counter, so the
// parts below those places are drawn lower (see BodyStretch).
private const val ART_W = BodyStretch.ART_WIDTH.toFloat()
private const val ART_H = BodyStretch.ART_HEIGHT.toFloat()

private class KeyArt(val id: String, val x0: Float, val x1: Float, val label: String)

private val KEY_ART = listOf(
    KeyArt("rew", 94f, 231f, "Rewind"), KeyArt("play", 245f, 382f, "Play"), KeyArt("ff", 396f, 535f, "Fast forward"),
    KeyArt("stop", 549f, 692f, "Stop, eject"), KeyArt("pause", 708f, 847f, "Pause"),
)
private const val KEY_TOP = 1462f
private const val KEY_H = 142f

private val KNOB_CENTER = Offset(824f, 905f)
private const val KNOB_MIN = -145f
private const val KNOB_MAX = 145f
/** The picture's own pointer sits at this angle (volume about 6). */
private const val KNOB_ART_ANGLE = 28f

/** The cassette window's frame: where its picture is cut from, its top edge, height, and how much bigger it may be drawn. */
private val WINDOW_CROP = Offset(108f, 342f)
private val WINDOW_PIVOT = Offset(385f, 344f)
private val WINDOW_CENTER = Offset(385f, 725f)
private const val WINDOW_H = 763f
private const val WINDOW_GROWTH = 0.10f

private val HUB_TOP = Offset(370f, 578f)
private val HUB_BOTTOM = Offset(370f, 885f)

/** A full reel of tape is this wide in the picture, so a reel's radius in mm becomes pixels at this rate. */
private const val PACK_FULL = 148f
private const val PACK_PX_PER_MM = PACK_FULL / ReelMotion.R_FULL
private const val HUB_RADIUS = 72f
private val PACK_BOX_TOP = Offset(293f, 431f)
private val PACK_BOX_BOTTOM = Offset(293f, 735f)

private const val TRACK_L = 112f
private const val TRACK_R = 656f

private val LED_X = floatArrayOf(766f, 827f, 885f)
private val TAPE_NAMES = arrayOf("Normal", "Chrome", "Metal")

private val ORANGE = Color(0xFFF47A14)

/** The pictures the player is made of. */
private class Art(
    val body: ImageBitmap,
    val window: ImageBitmap,
    val keyUp: Map<String, ImageBitmap>,
    val keyDn: Map<String, ImageBitmap>,
    val playOn: ImageBitmap,
    val capOn: ImageBitmap,
    val capOff: ImageBitmap,
    val hubTop: ImageBitmap,
    val hubBottom: ImageBitmap,
    val coverTop: ImageBitmap,
    val detailTop: ImageBitmap,
    val coverBottom: ImageBitmap,
    val detailBottom: ImageBitmap,
    val knob: ImageBitmap,
    val thumb: ImageBitmap,
    val slide: ImageBitmap,
    val sideB: ImageBitmap,
)

/**
 * The player, drawn from the artwork: a clean copy of the picture with the moving parts (keys, reels,
 * tape on the reels, volume knob, lamps, counter, switches) laid over it, so it looks exactly like the
 * design and every control on it does its job.
 */
@Composable
fun WalkmanPlayer(
    ui: DeckUi,
    anim: DeckAnimations,
    a: WalkmanActions,
    modifier: Modifier = Modifier,
    /** Told where the cassette window is on screen, so the rack knows where a dragged tape can be dropped. */
    onWindowBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    /** A tape is being held over the window. */
    dropHover: Boolean = false,
) {
    val bg = ImageBitmap.imageResource(R.drawable.wm_bg)
    val window = ImageBitmap.imageResource(R.drawable.wm_window)
    val keyUp = KEY_ART.associate { it.id to ImageBitmap.imageResource(keyRes(it.id, down = false)) }
    val keyDn = KEY_ART.filter { it.id != "play" }.associate { it.id to ImageBitmap.imageResource(keyRes(it.id, down = true)) }
    val playOn = ImageBitmap.imageResource(R.drawable.wm_key_play_on)
    val capOn = ImageBitmap.imageResource(R.drawable.wm_cap_play_on)
    val capOff = ImageBitmap.imageResource(R.drawable.wm_cap_play_off)
    val hubTop = ImageBitmap.imageResource(R.drawable.wm_hub_top)
    val hubBottom = ImageBitmap.imageResource(R.drawable.wm_hub_bottom)
    val coverTop = ImageBitmap.imageResource(R.drawable.wm_cover_top)
    val detailTop = ImageBitmap.imageResource(R.drawable.wm_detail_top)
    val coverBottom = ImageBitmap.imageResource(R.drawable.wm_cover_bottom)
    val detailBottom = ImageBitmap.imageResource(R.drawable.wm_detail_bottom)
    val knob = ImageBitmap.imageResource(R.drawable.wm_knob)
    val thumb = ImageBitmap.imageResource(R.drawable.wm_thumb)
    val slide = ImageBitmap.imageResource(R.drawable.wm_slide_knob)
    val sideB = ImageBitmap.imageResource(R.drawable.wm_side_b)

    val needles = rememberNeedles(ui.transport, ui.source)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val view = LocalView.current
    val pressed = remember { mutableStateMapOf<String, Boolean>() }
    var dragSeek by remember { mutableFloatStateOf(-1f) }
    val nr = animateFloatAsState(if (ui.noiseReduction) 1f else 0f, tween(140), label = "nr")
    val volumeAngle = animateFloatAsState(KNOB_MIN + (KNOB_MAX - KNOB_MIN) * ui.volume.coerceIn(0f, 1f), tween(60), label = "vol")

    // MONITOR keeps the screen lit while the tape runs, so the reels and lamps can be watched.
    val running = ui.transport != Transport.STOPPED && ui.transport != Transport.PAUSED
    DisposableEffect(ui.monitor, running) {
        view.keepScreenOn = ui.monitor && running
        onDispose { view.keepScreenOn = false }
    }

    val t = ui.transport
    val label = when (t) {
        Transport.PLAYING -> "PLAY"
        Transport.PAUSED -> "PAUSE"
        Transport.STOPPED -> "STOP"
        Transport.REWINDING -> "REW"
        Transport.FAST_FORWARDING -> "FF"
    }
    val labelStyle = with(density) {
        TextStyle(
            fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 26f.toSp(),
            letterSpacing = 8.5f.toSp(), color = Color(0xFFE4E4E8),
        )
    }
    val timeStyle = with(density) {
        TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 26f.toSp(), color = Color(0xFFC4BEBB))
    }
    val shown = if (dragSeek >= 0f) dragSeek else ui.progress
    val elapsed = if (dragSeek >= 0f) (dragSeek * ui.durationMs).toLong() else ui.positionMs
    val labelLayout = remember(label, labelStyle) { measurer.measure(label, labelStyle) }
    val timeText = "${clock(elapsed)} / ${clock(ui.durationMs)}"
    val timeLayout = remember(timeText, timeStyle) { measurer.measure(timeText, timeStyle) }
    val titleText = listOf(ui.title, ui.artist).filter { it.isNotBlank() }.joinToString(" - ")
        .ifBlank { if (ui.status == SourceStatus.NeedsFolder) "Tap to load a tape" else "My Mixtape" }
    val titleStyle = with(density) { TextStyle(fontFamily = FontFamily.Cursive, fontSize = 34f.toSp(), color = Color(0xFF2B2622)) }
    val titleLayout = remember(titleText, titleStyle) {
        measurer.measure(titleText, titleStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = 430))
    }

    BoxWithConstraints(modifier.background(Color(0xFF060606))) {
        // Taller than the artwork: add plain body in two places so it fills the screen.
        val extra = BodyStretch.extraFor(maxWidth.value, maxHeight.value)
        val (topRows, midRows) = BodyStretch.split(extra)
        val artHeight = ART_H + extra
        val s = min(maxWidth.value / ART_W, maxHeight.value / artHeight)
        val body = remember(bg, topRows, midRows) { stretchedBody(bg, topRows, midRows) }
        val art = Art(
            body, window, keyUp, keyDn, playOn, capOn, capOff, hubTop, hubBottom, coverTop, detailTop, coverBottom, detailBottom,
            knob, thumb, slide, sideB,
        )
        val dyTop = topRows.toFloat()
        val dyLow = (topRows + midRows).toFloat()
        // With room to spare below it, the cassette window is drawn larger (it grows down and sideways
        // from its top edge, as far as the margins of the case allow).
        val ws = 1f + min(WINDOW_GROWTH, max(0f, (midRows - 60f) / WINDOW_H))

        Box(Modifier.align(Alignment.Center).size((ART_W * s).dp, (artHeight * s).dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / ART_W
                scale(k, k, Offset.Zero) {
                    img(art.body, 0f, 0f)
                    if (ui.monitor) drawMonitorSpark()
                    // the cassette, the dials and switches: below the extra rows above the brand name
                    translate(0f, dyTop) {
                        // A tape dropping in (or the door popping) makes the whole window give a little.
                        val bump = 1f + 0.06f * anim.insert.value - 0.03f * anim.pop.value
                        scale(bump, bump, WINDOW_CENTER) {
                            scale(ws, ws, WINDOW_PIVOT) {
                                img(art.window, WINDOW_CROP.x, WINDOW_CROP.y)
                                drawTitle(titleLayout)
                                if (ui.side == 1) img(art.sideB, 200f, 422f)
                                drawReels(anim, ui.side == 1, art)
                                if (dropHover) drawDropGlow()
                            }
                        }
                        drawTapeLamps(ui.tapeType, pressed["tape"] == true)
                        drawSlide(art.slide, nr)
                        drawKnob(art.knob, volumeAngle.value)
                    }
                    // the counter, lamps and keys: below the extra rows between the window and the counter
                    translate(0f, dyLow) {
                        drawStatus(t, labelLayout, timeLayout, shown, art.thumb)
                        drawLamps(needles.left, 1277f, 15f)
                        drawLamps(needles.right, 1316f, 14.5f)
                        drawKeys(t, pressed, art)
                    }
                }
            }

            // What a screen reader says as the music changes (not the clock, which would never stop).
            val spoken = "$titleText. $label" + if (ui.side == 1) ". Side B" else ""
            Box(
                Modifier.size(1.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = spoken
                },
            )

            // ---- transport keys: act on touch-down, like the real mechanism
            val keyActions = mapOf("rew" to a.rewind, "play" to a.play, "ff" to a.fastForward, "stop" to a.stop, "pause" to a.pause)
            val latched = mapOf(
                "rew" to (t == Transport.REWINDING),
                "play" to (t == Transport.PLAYING || t == Transport.PAUSED),
                "ff" to (t == Transport.FAST_FORWARDING),
                "pause" to (t == Transport.PAUSED),
                "stop" to false,
            )
            for (ka in KEY_ART) {
                Hotspot(
                    ka.x0 - 3f, KEY_TOP + dyLow, ka.x1 - ka.x0 + 6f, KEY_H, s, ka.id, ka.label, pressed, keyActions.getValue(ka.id),
                    state = if (ka.id == "stop") null else if (latched.getValue(ka.id)) "Down" else "Up",
                )
            }

            // ---- cassette window: tap for the tape shelf, hold for the tape settings, swipe up/down for next/previous song
            val openShelf by rememberUpdatedState(a.openShelf)
            val openSettings by rememberUpdatedState(a.openSettings)
            val next by rememberUpdatedState(a.next)
            val previous by rememberUpdatedState(a.previous)
            Box(
                Modifier.at(
                    WINDOW_PIVOT.x - (WINDOW_PIVOT.x - 110f) * ws, WINDOW_PIVOT.y + dyTop, 550f * ws, WINDOW_H * ws, s,
                )
                    .onGloballyPositioned { onWindowBounds(it.boundsInRoot()) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Cassette: $titleText"
                        onClick(label = "Open the tape shelf") { openShelf(); true }
                        customActions = listOf(
                            CustomAccessibilityAction("Tape settings") { openSettings(); true },
                            CustomAccessibilityAction("Next song") { next(); true },
                            CustomAccessibilityAction("Previous song") { previous(); true },
                        )
                    }
                    .pointerInput(Unit) { detectTapGestures(onTap = { openShelf() }, onLongPress = { openSettings() }) }
                    .pointerInput(Unit) {
                        var dy = 0f
                        detectVerticalDragGestures(
                            onDragStart = { dy = 0f },
                            onDragEnd = {
                                if (dy < -90f) next() else if (dy > 90f) previous()
                            },
                        ) { change, delta -> change.consume(); dy += delta }
                    },
            )

            // ---- progress bar
            val seek by rememberUpdatedState(a.seek)
            Box(
                Modifier.at(96f, 1176f + dyLow, 594f, 64f, s)
                    .semantics {
                        contentDescription = "Tape position"
                        stateDescription = "${clock(elapsed)} of ${clock(ui.durationMs)}"
                        progressBarRangeInfo = ProgressBarRangeInfo(shown.coerceIn(0f, 1f), 0f..1f)
                        setProgress { value -> seek(value.coerceIn(0f, 1f)); true }
                    }
                    .pointerInput(Unit) {
                        fun frac(x: Float) = ((x / size.width * 594f + 96f - TRACK_L) / (TRACK_R - TRACK_L)).coerceIn(0f, 1f)
                        detectTapGestures(onTap = { seek(frac(it.x)) })
                    }
                    .pointerInput(Unit) {
                        fun frac(x: Float) = ((x / size.width * 594f + 96f - TRACK_L) / (TRACK_R - TRACK_L)).coerceIn(0f, 1f)
                        detectHorizontalDragGestures(
                            onDragStart = { dragSeek = frac(it.x) },
                            onDragEnd = { if (dragSeek >= 0f) seek(dragSeek); dragSeek = -1f },
                            onDragCancel = { dragSeek = -1f },
                        ) { change, _ -> change.consume(); dragSeek = frac(change.position.x) }
                    },
            )

            // ---- volume knob: turn it with a finger around its centre
            val setVolume by rememberUpdatedState(a.volume)
            Box(
                Modifier.at(KNOB_CENTER.x - 84f, KNOB_CENTER.y - 84f + dyTop, 168f, 168f, s)
                    .semantics {
                        contentDescription = "Volume"
                        stateDescription = "${(ui.volume * 10).roundToInt()} of 10"
                        progressBarRangeInfo = ProgressBarRangeInfo(ui.volume.coerceIn(0f, 1f), 0f..1f)
                        setProgress { value -> setVolume(value.coerceIn(0f, 1f)); true }
                    }
                    .pointerInput(Unit) {
                        fun turn(p: Offset) {
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val deg = Math.toDegrees(atan2((p.x - c.x).toDouble(), (c.y - p.y).toDouble())).toFloat()
                            val clamped = deg.coerceIn(KNOB_MIN, KNOB_MAX)
                            setVolume((clamped - KNOB_MIN) / (KNOB_MAX - KNOB_MIN))
                        }
                        detectDragGestures(onDragStart = { turn(it) }) { change, _ -> change.consume(); turn(change.position) }
                    },
            )

            // ---- tape type: the TAPE button steps through the three, or tap one of the names
            val setType by rememberUpdatedState(a.tapeType)
            val type by rememberUpdatedState(ui.tapeType)
            Hotspot(
                736f, 318f + dyTop, 84f, 44f, s, "tape", "Tape type", pressed, { setType((type + 1) % 3) },
                state = TAPE_NAMES[ui.tapeType.coerceIn(0, 2)],
            )
            Hotspot(722f, 384f + dyTop, 80f, 62f, s, "tape-normal", "Normal tape", null, { setType(0) }, state = if (ui.tapeType == 0) "Selected" else null)
            Hotspot(804f, 384f + dyTop, 62f, 62f, s, "tape-cro2", "Chrome tape", null, { setType(1) }, state = if (ui.tapeType == 1) "Selected" else null)
            Hotspot(868f, 384f + dyTop, 70f, 62f, s, "tape-metal", "Metal tape", null, { setType(2) }, state = if (ui.tapeType == 2) "Selected" else null)

            // ---- noise reduction switch and MONITOR switch
            val setNr by rememberUpdatedState(a.noiseReduction)
            val nrOn by rememberUpdatedState(ui.noiseReduction)
            Switch(746f, 562f + dyTop, 152f, 62f, s, "Noise reduction", ui.noiseReduction) { setNr(!nrOn) }
            val setMonitor by rememberUpdatedState(a.monitor)
            val monitorOn by rememberUpdatedState(ui.monitor)
            Switch(784f, 46f, 96f, 52f, s, "Monitor: keep the screen on while playing", ui.monitor) { setMonitor(!monitorOn) }
        }
    }
}

private fun keyRes(id: String, down: Boolean): Int = when (id) {
    "rew" -> if (down) R.drawable.wm_key_rew_dn else R.drawable.wm_key_rew_up
    "play" -> R.drawable.wm_key_play_up
    "ff" -> if (down) R.drawable.wm_key_ff_dn else R.drawable.wm_key_ff_up
    "stop" -> if (down) R.drawable.wm_key_stop_dn else R.drawable.wm_key_stop_up
    else -> if (down) R.drawable.wm_key_pause_dn else R.drawable.wm_key_pause_up
}

/** The artwork with the extra body rows added (see [BodyStretch]); the artwork itself when there are none. */
private fun stretchedBody(bg: ImageBitmap, top: Int, mid: Int): ImageBitmap {
    if (top == 0 && mid == 0) return bg
    val w = bg.width
    val src = IntArray(w * bg.height)
    bg.readPixels(src, 0, 0, w, bg.height, 0, w)
    val out = BodyStretch.build(src, w, top, mid)
    return Bitmap.createBitmap(out, w, bg.height + top + mid, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun Modifier.at(x: Float, y: Float, w: Float, h: Float, s: Float): Modifier =
    offset((x * s).dp, (y * s).dp).size((w * s).dp, (h * s).dp)

/** A touch area that clicks and buzzes like a key, acting the moment it is touched. */
@Composable
private fun Hotspot(
    x: Float, y: Float, w: Float, h: Float, s: Float, name: String, description: String,
    pressed: MutableMap<String, Boolean>?, onDown: () -> Unit, state: String? = null,
) {
    val view = LocalView.current
    val sounds = LocalMechSounds.current
    val haptics = LocalHaptics.current
    val action by rememberUpdatedState(onDown)
    Box(
        Modifier.at(x, y, w, h, s)
            .semantics {
                role = Role.Button
                contentDescription = description
                if (state != null) stateDescription = state
                // A screen reader's double tap
                onClick { action(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed?.set(name, true)
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
                    sounds?.keyDown()
                    action()
                    tryAwaitRelease()
                    pressed?.set(name, false)
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_RELEASE)
                    sounds?.keyUp()
                })
            },
    )
}

/** A slide switch's touch area: a tap flips it. */
@Composable
private fun Switch(x: Float, y: Float, w: Float, h: Float, s: Float, name: String, on: Boolean, onToggle: () -> Unit) {
    val view = LocalView.current
    val sounds = LocalMechSounds.current
    val haptics = LocalHaptics.current
    val action by rememberUpdatedState(onToggle)
    Box(
        Modifier.at(x, y, w, h, s)
            .semantics {
                role = Role.Switch
                contentDescription = name
                toggleableState = if (on) ToggleableState.On else ToggleableState.Off
                onClick { action(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    sounds?.keyUp()
                    action()
                })
            },
    )
}

// ---------------------------------------------------------------------------------- drawing

private fun DrawScope.img(image: ImageBitmap, x: Float, y: Float, alpha: Float = 1f, blendMode: BlendMode = BlendMode.SrcOver) {
    val size = IntSize(image.width, image.height)
    drawImage(
        image = image, srcOffset = IntOffset.Zero, srcSize = size,
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = size,
        alpha = alpha, blendMode = blendMode, filterQuality = FilterQuality.High,
    )
}

private fun DrawScope.drawTitle(layout: TextLayoutResult) {
    clipRect(186f, 598f, 262f, 1048f) {
        // Written up the label, from the bottom, the way the handwriting ran.
        rotate(-90f, Offset(222f, 1040f)) {
            drawText(layout, topLeft = Offset(222f, 1040f - layout.size.height / 2f))
        }
    }
}

/**
 * The two reels: the tape on each (the take-up reel at the top on side A grows as the song plays, the
 * supply reel shrinks) and the hubs that turn. Side B runs the tape the other way: the reels swap
 * jobs and turn the other way round.
 */
private fun DrawScope.drawReels(anim: DeckAnimations, sideB: Boolean, art: Art) {
    val reels = anim.reels
    val topMm = if (sideB) reels.leftRadius else reels.rightRadius
    val bottomMm = if (sideB) reels.rightRadius else reels.leftRadius
    drawPack(art.coverTop, art.detailTop, PACK_BOX_TOP, HUB_TOP, topMm)
    drawPack(art.coverBottom, art.detailBottom, PACK_BOX_BOTTOM, HUB_BOTTOM, bottomMm)
    val topAngle = if (sideB) -reels.leftAngle else reels.rightAngle
    val topDelta = if (sideB) -reels.leftDelta else reels.rightDelta
    val bottomAngle = if (sideB) -reels.rightAngle else reels.leftAngle
    val bottomDelta = if (sideB) -reels.rightDelta else reels.leftDelta
    reel(art.hubTop, HUB_TOP, topAngle, topDelta)
    reel(art.hubBottom, HUB_BOTTOM, bottomAngle, bottomDelta)
}

/**
 * Shrinks the tape on a reel to [radiusMm]. The artwork shows a full reel, so a smaller one is that
 * picture with the outer part painted over in the dark of the case (keeping the printing and glints
 * that sit on the glass), through a circle the size of the tape that is left.
 */
private fun DrawScope.drawPack(cover: ImageBitmap, detail: ImageBitmap, box: Offset, hub: Offset, radiusMm: Float) {
    val radius = (radiusMm * PACK_PX_PER_MM).coerceAtLeast(HUB_RADIUS)
    if (radius >= PACK_FULL - 0.5f) return
    val bounds = Rect(box.x, box.y, box.x + cover.width, box.y + cover.height)
    drawIntoCanvas { canvas ->
        canvas.saveLayer(bounds, Paint())
        img(cover, box.x, box.y)
        img(detail, box.x, box.y, blendMode = BlendMode.Plus)
        drawCircle(Color.Black, radius = radius, center = hub, blendMode = BlendMode.Clear)
        canvas.restore()
    }
}

private fun DrawScope.reel(hub: ImageBitmap, c: Offset, angle: Float, delta: Float) {
    val r = hub.width / 2f
    // Winding blurs the spokes: a couple of fainter copies trailing the sharp one.
    if (abs(delta) > 7f) {
        rotate(angle - delta * 0.66f, c) { img(hub, c.x - r, c.y - r) }
        rotate(angle - delta * 0.33f, c) { img(hub, c.x - r, c.y - r, 0.6f) }
    }
    rotate(angle, c) { img(hub, c.x - r, c.y - r, if (abs(delta) > 7f) 0.7f else 1f) }
}

private fun DrawScope.drawStatus(
    t: Transport,
    label: TextLayoutResult,
    time: TextLayoutResult,
    progress: Float,
    thumb: ImageBitmap,
) {
    // Mode symbol, in the order the mechanism would show it.
    val y0 = 1158f
    val y1 = 1179f
    val ym = (y0 + y1) / 2f
    when (t) {
        Transport.PLAYING -> drawPath(tri(119f, y0, 140f, ym, 119f, y1), ORANGE)
        Transport.PAUSED -> {
            drawRect(ORANGE, Offset(121f, y0), Size(6f, 21f)); drawRect(ORANGE, Offset(132f, y0), Size(6f, 21f))
        }
        Transport.STOPPED -> drawRect(ORANGE, Offset(121f, y0 + 1f), Size(18f, 19f))
        Transport.REWINDING -> {
            drawPath(tri(129f, y0, 118f, ym, 129f, y1), ORANGE); drawPath(tri(141f, y0, 130f, ym, 141f, y1), ORANGE)
        }
        Transport.FAST_FORWARDING -> {
            drawPath(tri(118f, y0, 129f, ym, 118f, y1), ORANGE); drawPath(tri(130f, y0, 141f, ym, 130f, y1), ORANGE)
        }
    }
    val baseline = 1177f
    drawText(label, topLeft = Offset(168f, baseline - label.firstBaseline))
    drawText(time, topLeft = Offset(667f - time.size.width, baseline - time.firstBaseline))

    // Position bar: orange up to the nub, grey beyond.
    val cx = TRACK_L + (TRACK_R - TRACK_L) * progress.coerceIn(0f, 1f)
    drawRoundRect(Color(0xFF6B6764), Offset(109f, 1204.5f), Size(559f, 7f), CornerRadius(3.5f))
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFFF9230), Color(0xFFF37410), Color(0xFFE56A08)), 1204f, 1212f),
        Offset(109f, 1204.5f), Size(cx - 109f + 4f, 7f), CornerRadius(3.5f),
    )
    img(thumb, cx - thumb.width / 2f, 1208f - thumb.height / 2f)
}

private fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Path().apply {
    moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close()
}

private const val LAMPS = 30
private val DB_X = floatArrayOf(168f, 248f, 320f, 396f, 473f, 547f, 620f)
private val DB_V = floatArrayOf(-20f, -10f, -6f, -3f, 0f, 3f, 6f)

/** How many of the 30 lamps a needle value (0 = −20 VU … 1 = +3 VU) lights, following the printed scale. */
internal fun lit(v: Float): Int {
    if (v <= 0.004f) return 0
    val vu = -20f + 23f * v.coerceIn(0f, 1.1f)
    var x = DB_X.last()
    for (i in 0 until DB_V.size - 1) {
        if (vu <= DB_V[i + 1]) {
            x = DB_X[i] + (DB_X[i + 1] - DB_X[i]) * ((vu - DB_V[i]) / (DB_V[i + 1] - DB_V[i])).coerceIn(0f, 1f)
            break
        }
    }
    return (((x - 169f) / 15.8f).toInt() + 1).coerceIn(0, LAMPS)
}

private fun DrawScope.drawLamps(needle: Float, y: Float, h: Float) {
    val on = lit(needle)
    for (i in 0 until LAMPS) {
        val x = 163f + i * 15.8f
        val corner = CornerRadius(2.4f)
        if (i < on) {
            val c = lerp(Color(0xFFFCC82D), Color(0xFFF06A0C), (i / 22f).coerceIn(0f, 1f))
            drawRoundRect(c.copy(alpha = 0.26f), Offset(x - 3f, y - 3f), Size(18.2f, h + 6f), CornerRadius(5f))
            drawRoundRect(c, Offset(x, y), Size(12.2f, h), corner)
            drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(x + 1f, y + 1f), Size(10.2f, h * 0.32f), CornerRadius(1.6f))
        } else {
            drawRoundRect(Color(0xFF424040), Offset(x, y), Size(12.2f, h), corner)
            drawRoundRect(Color(0xFF5A5856), Offset(x, y), Size(12.2f, 2f), CornerRadius(1f))
        }
    }
}

private fun DrawScope.drawKeys(t: Transport, pressed: Map<String, Boolean>, art: Art) {
    val latched = mapOf(
        "rew" to (t == Transport.REWINDING),
        "play" to (t == Transport.PLAYING || t == Transport.PAUSED),
        "ff" to (t == Transport.FAST_FORWARDING),
        "pause" to (t == Transport.PAUSED),
        "stop" to false,
    )
    for (ka in KEY_ART) {
        val sunk = latched.getValue(ka.id) || pressed[ka.id] == true
        val image = when {
            ka.id == "play" -> if (sunk) art.playOn else art.keyUp.getValue("play")
            sunk -> art.keyDn.getValue(ka.id)
            else -> art.keyUp.getValue(ka.id)
        }
        img(image, ka.x0 - 3f, KEY_TOP)
    }
    img(if (latched.getValue("play")) art.capOn else art.capOff, 280f, 1424f)
}

private fun DrawScope.drawKnob(knob: ImageBitmap, angle: Float) {
    val r = knob.width / 2f
    rotate(angle - KNOB_ART_ANGLE, KNOB_CENTER) { img(knob, KNOB_CENTER.x - r, KNOB_CENTER.y - r) }
}

/** The orange glow round the cassette window while a tape is held over it. */
private fun DrawScope.drawDropGlow() {
    val corner = CornerRadius(26f)
    drawRoundRect(ORANGE.copy(alpha = 0.30f), Offset(98f, 332f), Size(574f, 787f), corner, style = Stroke(26f))
    drawRoundRect(ORANGE.copy(alpha = 0.95f), Offset(106f, 340f), Size(558f, 771f), corner, style = Stroke(6f))
}

private fun DrawScope.drawTapeLamps(type: Int, buttonDown: Boolean) {
    val c = Offset(LED_X[type.coerceIn(0, 2)], 423f)
    drawCircle(Brush.radialGradient(listOf(Color(0xCCFF9A2A), Color.Transparent), c, 17f), 17f, c)
    drawCircle(Color(0xFFFF8A1E), 6.5f, c)
    drawCircle(Color(0xFFFFE2A0), 3f, c - Offset(1f, 1f))
    if (buttonDown) drawRoundRect(Color.Black.copy(alpha = 0.32f), Offset(742f, 324f), Size(70f, 32f), CornerRadius(5f))
}

private fun DrawScope.drawSlide(knob: ImageBitmap, position: State<Float>) {
    img(knob, 758f + 58f * position.value, 573f)
}

private fun DrawScope.drawMonitorSpark() {
    val c = Offset(858f, 76f)
    drawCircle(Brush.radialGradient(listOf(Color(0x99FF9A2A), Color.Transparent), c, 20f), 20f, c)
    drawLine(Color(0x66FFAA40), Offset(840f, 76f), Offset(880f, 76f), 3f, StrokeCap.Round)
    drawRoundRect(Color(0xFFFFB030), Offset(850f, 70f), Size(16f, 11f), CornerRadius(2f))
    drawRoundRect(Color(0xFFFFE6A0), Offset(853f, 73f), Size(10f, 4f), CornerRadius(2f))
}

internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
