package dev.foldman.ui

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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
)

// The artwork is 941 x 1672; every coordinate below is in those pixels.
private const val ART_W = 941f
private const val ART_H = 1672f

private class KeyArt(val id: String, val x0: Float, val x1: Float)

private val KEY_ART = listOf(
    KeyArt("rew", 94f, 231f), KeyArt("play", 245f, 382f), KeyArt("ff", 396f, 535f),
    KeyArt("stop", 549f, 692f), KeyArt("pause", 708f, 847f),
)
private const val KEY_TOP = 1462f
private const val KEY_H = 142f

private val KNOB_CENTER = Offset(824f, 905f)
private const val KNOB_MIN = -145f
private const val KNOB_MAX = 145f
/** The picture's own pointer sits at this angle (volume about 6). */
private const val KNOB_ART_ANGLE = 28f

private val HUB_TOP = Offset(370f, 578f)
private val HUB_BOTTOM = Offset(370f, 885f)

private const val TRACK_L = 112f
private const val TRACK_R = 656f

private val LED_X = floatArrayOf(766f, 827f, 885f)

private val ORANGE = Color(0xFFF47A14)

/**
 * The player, drawn from the artwork: a clean copy of the picture with the moving parts (keys, reels,
 * volume knob, lamps, counter, switches) laid over it, so it looks exactly like the design and every
 * control on it does its job.
 */
@Composable
fun WalkmanPlayer(ui: DeckUi, anim: DeckAnimations, a: WalkmanActions, modifier: Modifier = Modifier) {
    val bg = ImageBitmap.imageResource(R.drawable.wm_bg)
    val keyUp = KEY_ART.associate { it.id to ImageBitmap.imageResource(keyRes(it.id, down = false)) }
    val keyDn = KEY_ART.filter { it.id != "play" }.associate { it.id to ImageBitmap.imageResource(keyRes(it.id, down = true)) }
    val playOn = ImageBitmap.imageResource(R.drawable.wm_key_play_on)
    val capOn = ImageBitmap.imageResource(R.drawable.wm_cap_play_on)
    val capOff = ImageBitmap.imageResource(R.drawable.wm_cap_play_off)
    val hubTop = ImageBitmap.imageResource(R.drawable.wm_hub_top)
    val hubBottom = ImageBitmap.imageResource(R.drawable.wm_hub_bottom)
    val knob = ImageBitmap.imageResource(R.drawable.wm_knob)
    val thumb = ImageBitmap.imageResource(R.drawable.wm_thumb)
    val slide = ImageBitmap.imageResource(R.drawable.wm_slide_knob)

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
        val s = min(maxWidth.value / ART_W, maxHeight.value / ART_H)
        Box(Modifier.align(Alignment.Center).size((ART_W * s).dp, (ART_H * s).dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / ART_W
                scale(k, k, Offset.Zero) {
                    img(bg, 0f, 0f)
                    drawTitle(titleLayout)
                    drawReels(anim, hubTop, hubBottom)
                    drawStatus(t, labelLayout, timeLayout, shown, thumb)
                    drawLamps(needles.left, 1277f, 15f)
                    drawLamps(needles.right, 1316f, 14.5f)
                    drawKeys(t, pressed, keyUp, keyDn, playOn, capOn, capOff)
                    drawKnob(knob, volumeAngle.value)
                    drawTapeLamps(ui.tapeType, pressed["tape"] == true)
                    drawSlide(slide, nr)
                    if (ui.monitor) drawMonitorSpark()
                }
            }

            // ---- transport keys: act on touch-down, like the real mechanism
            val keyActions = mapOf("rew" to a.rewind, "play" to a.play, "ff" to a.fastForward, "stop" to a.stop, "pause" to a.pause)
            for (ka in KEY_ART) {
                Hotspot(ka.x0 - 3f, KEY_TOP, ka.x1 - ka.x0 + 6f, KEY_H, s, ka.id, pressed, keyActions.getValue(ka.id))
            }

            // ---- cassette window: tap for the tape shelf, swipe up/down for next/previous song
            val openShelf by rememberUpdatedState(a.openShelf)
            val next by rememberUpdatedState(a.next)
            val previous by rememberUpdatedState(a.previous)
            Box(
                Modifier.at(116f, 350f, 545f, 750f, s)
                    .semantics { role = Role.Button; contentDescription = "Tape shelf" }
                    .pointerInput(Unit) { detectTapGestures(onTap = { openShelf() }) }
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
                Modifier.at(96f, 1176f, 594f, 64f, s)
                    .semantics { contentDescription = "Tape position" }
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
                Modifier.at(KNOB_CENTER.x - 84f, KNOB_CENTER.y - 84f, 168f, 168f, s)
                    .semantics { contentDescription = "Volume" }
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
            Hotspot(736f, 318f, 84f, 44f, s, "tape", pressed, { setType((type + 1) % 3) })
            Hotspot(722f, 384f, 80f, 62f, s, "tape-normal", null, { setType(0) })
            Hotspot(804f, 384f, 62f, 62f, s, "tape-cro2", null, { setType(1) })
            Hotspot(868f, 384f, 70f, 62f, s, "tape-metal", null, { setType(2) })

            // ---- noise reduction switch and MONITOR switch
            val setNr by rememberUpdatedState(a.noiseReduction)
            val nrOn by rememberUpdatedState(ui.noiseReduction)
            Switch(746f, 562f, 152f, 62f, s, "Noise reduction") { setNr(!nrOn) }
            val setMonitor by rememberUpdatedState(a.monitor)
            val monitorOn by rememberUpdatedState(ui.monitor)
            Switch(784f, 46f, 96f, 52f, s, "Monitor") { setMonitor(!monitorOn) }
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

private fun Modifier.at(x: Float, y: Float, w: Float, h: Float, s: Float): Modifier =
    offset((x * s).dp, (y * s).dp).size((w * s).dp, (h * s).dp)

/** A touch area that clicks and buzzes like a key, acting the moment it is touched. */
@Composable
private fun Hotspot(
    x: Float, y: Float, w: Float, h: Float, s: Float, name: String,
    pressed: MutableMap<String, Boolean>?, onDown: () -> Unit,
) {
    val view = LocalView.current
    val sounds = LocalMechSounds.current
    val haptics = LocalHaptics.current
    val action by rememberUpdatedState(onDown)
    Box(
        Modifier.at(x, y, w, h, s)
            .semantics { role = Role.Button; contentDescription = name }
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
private fun Switch(x: Float, y: Float, w: Float, h: Float, s: Float, name: String, onToggle: () -> Unit) {
    val view = LocalView.current
    val sounds = LocalMechSounds.current
    val haptics = LocalHaptics.current
    val action by rememberUpdatedState(onToggle)
    Box(
        Modifier.at(x, y, w, h, s)
            .semantics { role = Role.Switch; contentDescription = name }
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

private fun DrawScope.img(image: ImageBitmap, x: Float, y: Float, alpha: Float = 1f) {
    val size = IntSize(image.width, image.height)
    drawImage(
        image = image, srcOffset = IntOffset.Zero, srcSize = size,
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = size,
        alpha = alpha, filterQuality = FilterQuality.High,
    )
}

private fun DrawScope.drawTitle(layout: androidx.compose.ui.text.TextLayoutResult) {
    clipRect(186f, 598f, 262f, 1048f) {
        // Written up the label, from the bottom, the way the handwriting ran.
        rotate(-90f, Offset(222f, 1040f)) {
            drawText(layout, topLeft = Offset(222f, 1040f - layout.size.height / 2f))
        }
    }
}

private fun DrawScope.drawReels(anim: DeckAnimations, top: ImageBitmap, bottom: ImageBitmap) {
    // With the cassette stood on end the take-up reel is at the top.
    reel(top, HUB_TOP, anim.reels.rightAngle, anim.reels.rightDelta)
    reel(bottom, HUB_BOTTOM, anim.reels.leftAngle, anim.reels.leftDelta)
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
    label: androidx.compose.ui.text.TextLayoutResult,
    time: androidx.compose.ui.text.TextLayoutResult,
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
private fun lit(v: Float): Int {
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

private fun DrawScope.drawKeys(
    t: Transport,
    pressed: Map<String, Boolean>,
    up: Map<String, ImageBitmap>,
    dn: Map<String, ImageBitmap>,
    playOn: ImageBitmap,
    capOn: ImageBitmap,
    capOff: ImageBitmap,
) {
    val latched = mapOf(
        "rew" to (t == Transport.REWINDING),
        "play" to (t == Transport.PLAYING || t == Transport.PAUSED),
        "ff" to (t == Transport.FAST_FORWARDING),
        "pause" to (t == Transport.PAUSED),
        "stop" to false,
    )
    for (ka in KEY_ART) {
        val sunk = latched.getValue(ka.id) || pressed[ka.id] == true
        val image = if (ka.id == "play") (if (sunk) playOn else up.getValue("play")) else if (sunk) dn.getValue(ka.id) else up.getValue(ka.id)
        img(image, ka.x0 - 3f, KEY_TOP)
    }
    img(if (latched.getValue("play")) capOn else capOff, 280f, 1424f)
}

private fun DrawScope.drawKnob(knob: ImageBitmap, angle: Float) {
    val r = knob.width / 2f
    rotate(angle - KNOB_ART_ANGLE, KNOB_CENTER) { img(knob, KNOB_CENTER.x - r, KNOB_CENTER.y - r) }
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

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
