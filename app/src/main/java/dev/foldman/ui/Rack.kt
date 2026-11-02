package dev.foldman.ui

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foldman.deck.DeckUi
import dev.foldman.deck.Mixtape
import dev.foldman.deck.Source
import dev.foldman.deck.SourceStatus
import dev.foldman.deck.Tape
import dev.foldman.deck.TapeKind
import dev.foldman.deck.Tapes
import dev.foldman.deck.LocalTrack
import dev.foldman.playback.Artwork
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the rack can do. */
class RackActions(
    val insert: (Tape<LocalTrack>) -> Unit,
    val createMixtape: (name: String, keys: List<String>) -> Unit,
    val saveMixtape: (id: String, name: String, keys: List<String>) -> Unit,
    val deleteMixtape: (id: String) -> Unit,
    val addToMixtape: (id: String, key: String) -> Unit,
    val pickFolder: () -> Unit,
    val shelf: ShelfActions,
)

private val RackInk = Color(0xFFF2EBDC)
private val RackDim = Color(0xFFB9B2A8)
private val RackOrange = Color(0xFFF0722B)
private val RackPanel = Brush.verticalGradient(listOf(Color(0xFF3C3733), Color(0xFF2A2522), Color(0xFF1D1917)))
private val RackRim = Brush.verticalGradient(listOf(Color(0xFFD9A06A), Color(0x88A8693A), Color(0xFF8A5A33)))
private val RackLine = Color(0x2EFFFFFF)
private val Hand = TextStyle(fontFamily = FontFamily.Cursive, color = DeckColors.Ink, fontSize = 20.sp)
private val Typed = TextStyle(fontFamily = FontFamily.Monospace, color = DeckColors.Ink, fontSize = 12.sp)

/** A tape being carried: where the finger is, in the rack's own coordinates. */
private class Carry(val tape: Tape<LocalTrack>, val at: Offset)

private class EditorState(val mixtapeId: String?, val name: String, val keys: List<String>)

private val Tape<LocalTrack>.kindColor: Color
    get() = when (kind) {
        TapeKind.ALBUM -> Color(0xFF3A6FB5)
        TapeKind.SONG -> Color(0xFFE9DFC8)
        TapeKind.MIXTAPE -> RackOrange
        TapeKind.LIBRARY -> Color(0xFF8F9297)
    }

/**
 * The cassette rack: every album, song and mixtape on the phone as a tape. Drag a tape into the
 * deck (the cassette window behind) to put it in, hold one to read its card.
 */
@Composable
fun CassetteRack(
    ui: DeckUi,
    actions: RackActions,
    deckBounds: Rect,
    onHover: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var showShelf by rememberSaveable { mutableStateOf(false) }
    var carry by remember { mutableStateOf<Carry?>(null) }
    var card by remember { mutableStateOf<Tape<LocalTrack>?>(null) }
    var editor by remember { mutableStateOf<EditorState?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    val library = ui.tracks
    val albums = remember(library) { Tapes.albums(library) }
    val songs = remember(library) { Tapes.songs(library) }
    val mixtapes = remember(library, ui.mixtapes) { Tapes.mixtapes(ui.mixtapes, library) }
    val tapes = when (tab) {
        0 -> albums
        1 -> songs
        else -> mixtapes
    }
    val shown = remember(tapes, query) { Tapes.search(tapes, query) }
    val onShelf = showShelf || ui.source != Source.LOCAL

    val hovering = carry?.let { deckBounds.translate(-origin).contains(it.at) } ?: false
    LaunchedEffect(hovering) { onHover(hovering) }
    DisposableEffect(Unit) { onDispose { onHover(false) } }

    val carrying = carry != null
    val scrim by animateFloatAsState(if (carrying) 0.10f else 0.55f, label = "scrim")
    val lift by animateFloatAsState(if (carrying) 1f else 0f, label = "lift")

    Box(
        modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .semantics { paneTitle = "Cassette rack" },
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim))
                .pointerInput(Unit) { detectTapGestures { onClose() } },
        )
        // The panel steps aside while a tape is carried, so the deck can be seen.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.76f)
                .padding(horizontal = 4.dp)
                .padding(bottom = 6.dp)
                .graphicsLayer {
                    translationY = size.height * 0.62f * lift
                    alpha = 1f - 0.85f * lift
                }
                .clip(RoundedCornerShape(26.dp))
                .background(RackPanel)
                .drawBehind {
                    // the brushed sheen across the metal
                    drawRect(Brush.linearGradient(listOf(Color(0x22FFFFFF), Color.Transparent, Color(0x14FFFFFF)), Offset(0f, 0f), Offset(size.width, size.height * 0.5f)))
                }
                .border(BorderStroke(1.3.dp, RackRim), RoundedCornerShape(26.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 18.dp)
                .padding(top = 18.dp),
        ) {
            if (onShelf) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SOURCES", style = EngravedCaps.copy(color = RackDim, fontSize = 10.sp, letterSpacing = 3.sp), modifier = Modifier.weight(1f))
                    if (ui.source == Source.LOCAL) {
                        Text(
                            "◂ RACK",
                            style = EngravedCaps.copy(color = RackOrange, fontSize = 10.sp),
                            modifier = Modifier.clickable { showShelf = false }.padding(8.dp),
                        )
                    }
                }
                TapeShelf(ui, actions.shelf, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.height(10.dp))
            } else {
                RackHeader(
                    count = tapes.size.takeIf { ui.status == SourceStatus.Ready } ?: 0,
                    onSources = { showShelf = true },
                    onFolder = actions.pickFolder,
                )
                Spacer(Modifier.height(8.dp))
                when (val status = ui.status) {
                    SourceStatus.NeedsFolder -> RackPrompt(
                        "Pick a folder of music on this phone and every album and song in it becomes a tape.",
                        "Choose music folder", actions.pickFolder,
                    )
                    is SourceStatus.Scanning -> Text("Winding on… ${status.found} songs found", color = RackDim, fontSize = 14.sp)
                    SourceStatus.EmptyFolder -> RackPrompt("No music in that folder.", "Choose another folder", actions.pickFolder)
                    else -> {
                        Tabs(tab, listOf("ALBUMS ${albums.size}", "SONGS ${songs.size}", "MIXTAPES ${mixtapes.size}")) { tab = it }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            textStyle = TextStyle(color = RackInk, fontSize = 15.sp),
                            leadingIcon = { Magnifier() },
                            placeholder = { Text("Search the rack", fontSize = 15.sp, fontStyle = FontStyle.Italic) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = RackInk, unfocusedTextColor = RackInk, cursorColor = RackOrange,
                                focusedBorderColor = RackOrange, unfocusedBorderColor = Color(0x55FFFFFF),
                                focusedContainerColor = Color(0x33000000), unfocusedContainerColor = Color(0x33000000),
                                focusedPlaceholderColor = RackDim, unfocusedPlaceholderColor = RackDim,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), userScrollEnabled = !carrying, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(shown, key = { it.id }) { tape ->
                                TapeCard(
                                    tape = tape, inDeck = tape.id == ui.tape.id,
                                    onInsert = { actions.insert(tape); onClose() },
                                    onHold = { card = tape },
                                    onHoldEnd = { card = null },
                                    onDragStart = { rootPos -> android.util.Log.d("FoldmanRack", "foldman rack: carry start at $rootPos origin=$origin"); card = null; carry = Carry(tape, rootPos - origin) },
                                    onDrag = { rootPos -> carry = Carry(tape, rootPos - origin) },
                                    onDragEnd = { rootPos ->
                                        val dropped = carry
                                        android.util.Log.d("FoldmanRack", "foldman rack: carry end at $rootPos origin=$origin deck=$deckBounds carried=${dropped?.tape?.id}")
                                        carry = null
                                        if (rootPos != null && dropped != null && deckBounds.translate(-origin).contains(rootPos - origin)) {
                                            actions.insert(dropped.tape)
                                            onClose()
                                        }
                                    },
                                )
                            }
                            if (tab == 2) {
                                item(key = "blank") { BlankMixtape { editor = EditorState(null, "", emptyList()) } }
                            }
                            if (shown.isEmpty() && tab != 2) {
                                item(key = "none") { Text("Nothing here.", color = RackDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(14.dp).height(1.dp).background(RackLine))
                            Text(
                                "DRAG A TAPE'S COVER OR HANDLE INTO THE DECK · HOLD TO READ ITS CARD",
                                style = EngravedCaps.copy(color = RackDim, fontSize = 7.sp, letterSpacing = 0.9.sp),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                            )
                            Box(Modifier.width(14.dp).height(1.dp).background(RackLine))
                        }
                    }
                }
            }
        }

        carry?.let { DragCassette(it.tape, it.at) }

        card?.let { tape ->
            TapeCardSheet(
                tape = tape, ui = ui, mixtapes = ui.mixtapes,
                onInsert = { card = null; actions.insert(tape); onClose() },
                onAddTo = { id -> actions.addToMixtape(id, tape.songs.first().key) },
                onNewMixtapeWith = {
                    card = null
                    tab = 2
                    editor = EditorState(null, "", tape.songs.map { it.key })
                },
                onEdit = {
                    val m = ui.mixtapes.firstOrNull { Tapes.mixtapeId(it.id) == tape.id }
                    card = null
                    if (m != null) editor = EditorState(m.id, m.name, m.keys)
                },
                onClose = { card = null },
            )
        }

        editor?.let { state ->
            MixtapeEditor(
                state = state, library = library,
                onSave = { name, keys ->
                    if (state.mixtapeId == null) actions.createMixtape(name, keys) else actions.saveMixtape(state.mixtapeId, name, keys)
                    editor = null
                },
                onDelete = state.mixtapeId?.let { id -> { actions.deleteMixtape(id); editor = null } },
                onCancel = { editor = null },
            )
        }
    }
}

@Composable
private fun RackHeader(count: Int, onSources: () -> Unit, onFolder: () -> Unit) {
    Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("CASSETTE RACK", style = EngravedCaps.copy(color = RackInk, fontSize = 15.sp, letterSpacing = 3.sp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(1.dp).height(20.dp).background(RackLine))
        Spacer(Modifier.width(12.dp))
        Text(
            if (count == 1) "1 TAPE" else "$count TAPES",
            style = EngravedCaps.copy(color = RackOrange, fontSize = 12.sp, letterSpacing = 1.5.sp),
            maxLines = 1, softWrap = false,
        )
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(20.dp).background(RackLine))
        Text("FOLDER", style = EngravedCaps.copy(color = RackInk, fontSize = 11.sp, letterSpacing = 2.sp), modifier = Modifier.clickable(onClick = onFolder).padding(horizontal = 14.dp, vertical = 8.dp))
        Box(Modifier.width(1.dp).height(20.dp).background(RackLine))
        Text("SOURCES", style = EngravedCaps.copy(color = RackInk, fontSize = 11.sp, letterSpacing = 2.sp), modifier = Modifier.clickable(onClick = onSources).padding(start = 14.dp, top = 8.dp, bottom = 8.dp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(RackLine))
}

@Composable
private fun Tabs(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, label ->
            if (i > 0) Box(Modifier.width(1.dp).height(24.dp).background(RackLine))
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) }
                    .drawBehind {
                        if (i == selected) {
                            val w = size.width * 0.62f
                            val x = (size.width - w) / 2f
                            drawRect(RackOrange.copy(alpha = 0.25f), Offset(x, size.height - 5.dp.toPx()), Size(w, 5.dp.toPx()))
                            drawRect(RackOrange, Offset(x, size.height - 2.dp.toPx()), Size(w, 2.dp.toPx()))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = EngravedCaps.copy(color = if (i == selected) Color.White else RackDim, fontSize = 13.sp, letterSpacing = 2.sp))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(RackLine))
}

/** The magnifier in the search field. */
@Composable
private fun Magnifier() {
    Canvas(Modifier.size(22.dp)) {
        val c = Color(0xFFD8D2C6)
        val r = size.minDimension * 0.30f
        val centre = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(c, r, centre, style = Stroke(2.dp.toPx()))
        drawLine(c, centre + Offset(r * 0.72f, r * 0.72f), Offset(size.width * 0.88f, size.height * 0.88f), 2.4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** A small Sony-style cassette for a rack card: the label stripe, the reels and the grade (HF, UX, EF) vary per tape. */
@Composable
private fun CassetteThumb(tape: Tape<LocalTrack>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val grade = remember(tape.id) { Math.floorMod(tape.id.hashCode(), 3) }
    val name = listOf("HF", "UX", "EF")[grade] + if (tape.durationMs <= 40 * 60_000L) "60" else "90"
    val accent = listOf(Color(0xFFE8742A), Color(0xFFD9A441), Color(0xFF4F82C6))[grade]
    val dark = grade == 1
    val brand = remember { measurer.measure("SONY", TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 6.sp, color = Color(0xFF3A3532))) }
    val model = remember(name) { measurer.measure(name, TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 7.sp, color = accent)) }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val corner = CornerRadius(4.dp.toPx())
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFF1EBDD), Color(0xFFD9D1C0))), cornerRadius = corner)
        drawRoundRect(Color(0xFF6E675C), cornerRadius = corner, style = Stroke(1.dp.toPx()))
        // the label
        val label = Rect(w * 0.07f, h * 0.10f, w * 0.93f, h * 0.76f)
        drawRoundRect(Color(0xFFF8F3E6), label.topLeft, label.size, CornerRadius(2.dp.toPx()))
        // the colour stripes across the label's lower left
        val stripe = Path().apply {
            moveTo(label.left, label.bottom - label.height * 0.34f)
            lineTo(label.left + label.width * 0.42f, label.top + label.height * 0.04f)
            lineTo(label.left + label.width * 0.58f, label.top + label.height * 0.04f)
            lineTo(label.left, label.bottom - label.height * 0.04f)
            close()
        }
        drawPath(stripe, accent.copy(alpha = 0.85f))
        drawText(brand, topLeft = Offset(label.left + 3.dp.toPx(), label.top + 1.dp.toPx()))
        // the tape window with the two reels
        val window = Rect(w * 0.20f, h * 0.24f, w * 0.80f, h * 0.54f)
        drawRoundRect(Color(0xFF3A3532), window.topLeft, window.size, CornerRadius(h * 0.15f))
        for (cx in listOf(0.36f, 0.64f)) {
            val c = Offset(w * cx, window.center.y)
            drawCircle(Color(0xFFE9E4D8), h * 0.125f, c)
            drawCircle(Color(0xFF2A2624), h * 0.06f, c)
            for (k in 0 until 6) {
                val a = k * (Math.PI / 3.0)
                drawCircle(Color(0xFFE9E4D8), 0.9.dp.toPx(), c + Offset((Math.cos(a) * h * 0.075f).toFloat(), (Math.sin(a) * h * 0.075f).toFloat()))
            }
        }
        drawText(model, topLeft = Offset(label.right - model.size.width - 3.dp.toPx(), label.bottom - model.size.height - 0.5.dp.toPx()))
        // the shell's bottom
        val base = Path().apply {
            moveTo(w * 0.16f, h * 0.78f); lineTo(w * 0.84f, h * 0.78f); lineTo(w * 0.90f, h * 0.97f); lineTo(w * 0.10f, h * 0.97f); close()
        }
        drawPath(base, if (dark) Color(0xFF2B2826) else Color(0xFFB9B2A4))
        drawCircle(if (dark) Color(0xFF6B655C) else Color(0xFF8D867A), 1.2.dp.toPx(), Offset(w * 0.30f, h * 0.88f))
        drawCircle(if (dark) Color(0xFF6B655C) else Color(0xFF8D867A), 1.2.dp.toPx(), Offset(w * 0.70f, h * 0.88f))
    }
}

@Composable
private fun RackPrompt(text: String, button: String, onClick: () -> Unit) {
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(text, color = RackDim, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(12.dp))
        RackButton(button, onClick)
    }
}

@Composable
private fun RackButton(text: String, onClick: () -> Unit, enabled: Boolean = true, accent: Boolean = false) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (accent && enabled) RackOrange else Color(0x22FFFFFF))
            .border(BorderStroke(1.dp, if (enabled) Color(0x44FFFFFF) else Color(0x14FFFFFF)), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text.uppercase(),
            style = EngravedCaps.copy(color = if (!enabled) RackDim.copy(alpha = 0.5f) else if (accent) Color.Black else RackInk, fontSize = 10.sp),
        )
    }
}

/** The mark of an empty tape at the end of the mixtapes: tap it to make a new one. */
@Composable
private fun BlankMixtape(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    RackDim.copy(alpha = 0.6f), cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            }
            .semantics { role = Role.Button; contentDescription = "Blank mixtape" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("BLANK MIXTAPE", style = EngravedCaps.copy(color = RackInk, fontSize = 11.sp, letterSpacing = 3.sp))
            Text("Tap to make your own", color = RackDim, fontSize = 11.sp)
        }
    }
}

/**
 * One tape in the rack. A tap puts it in the deck; holding without moving reads its card; moving
 * sideways (or from its cover, in any direction) picks it up to carry to the deck.
 */
@Composable
private fun TapeCard(
    tape: Tape<LocalTrack>,
    inDeck: Boolean,
    onInsert: () -> Unit,
    onHold: () -> Unit,
    onHoldEnd: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (Offset?) -> Unit,
) {
    // The card moves with the panel while a tape is carried, so where the finger is on screen is worked out
    // from the card's layout as it is at that moment, never from where it was when it was first laid out.
    var layout by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val insert by rememberUpdatedState(onInsert)
    val hold by rememberUpdatedState(onHold)
    val holdEnd by rememberUpdatedState(onHoldEnd)
    val dragStart by rememberUpdatedState(onDragStart)
    val dragMove by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val shape = RoundedCornerShape(18.dp)
    val meta = when (tape.kind) {
        TapeKind.ALBUM, TapeKind.MIXTAPE -> Tapes.lengthText(tape.durationMs)
        else -> Tapes.lengthText(tape.durationMs)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .onGloballyPositioned { layout = it }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF47413D), Color(0xFF2E2927), Color(0xFF252120))))
            .drawBehind {
                if (inDeck) drawRoundRect(RackOrange.copy(alpha = 0.22f), cornerRadius = CornerRadius(18.dp.toPx()), style = Stroke(7.dp.toPx()))
            }
            .border(if (inDeck) BorderStroke(2.dp, RackOrange) else BorderStroke(1.dp, Brush.verticalGradient(listOf(Color(0x44FFFFFF), Color(0x14FFFFFF)))), shape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "${tape.title}. ${tape.subtitle}" + if (inDeck) ". In the deck" else ""
                onClick(label = "Put in the deck") { insert(); true }
                customActions = listOf(CustomAccessibilityAction("Read its card") { hold(); true })
            }
            .pointerInput(Unit) {
                val gripLeft = 96.dp.toPx()
                val gripRight = 42.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val slop = viewConfiguration.touchSlop
                    fun onScreen(p: Offset): Offset = layout?.takeIf { it.isAttached }?.localToRoot(p) ?: p
                    android.util.Log.d("FoldmanRack", "foldman rack: down at ${down.position}")
                    val held = awaitLongPressOrCancellation(down.id)
                    android.util.Log.d("FoldmanRack", "foldman rack: long press=${held != null}")
                    if (held != null) {
                        // Held still: the card opens. If the finger then moves, the tape is picked up instead.
                        hold()
                        var picked = false
                        var last = held.position
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (!picked && (change.position - held.position).getDistance() > slop * 2f) {
                                picked = true
                                holdEnd()
                                dragStart(onScreen(change.position))
                            }
                            if (picked) {
                                change.consume()
                                dragMove(onScreen(change.position))
                            }
                            last = change.position
                        }
                        if (picked) dragEnd(onScreen(last))
                        return@awaitEachGesture
                    }
                    val now = currentEvent.changes.firstOrNull { it.id == down.id }
                    if (now == null || !now.pressed) {
                        insert()
                        return@awaitEachGesture
                    }
                    val dx = now.position.x - down.position.x
                    val dy = now.position.y - down.position.y
                    val sideways = abs(dx) > abs(dy) * 1.2f
                    // Up and down on the card is the list scrolling, unless its cover or handle was grabbed.
                    val onGrip = down.position.x <= gripLeft || down.position.x >= size.width - gripRight
                    if (!sideways && !onGrip) return@awaitEachGesture
                    // The list's own scrolling may already have claimed this move (it looks first); a tape grabbed
                    // by its cover, handle or sideways is carried all the same, and the list is held still meanwhile.
                    dragStart(onScreen(now.position))
                    now.consume()
                    var last = now.position
                    var completed = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { completed = true; break }
                        change.consume()
                        last = change.position
                        dragMove(onScreen(change.position))
                    }
                    dragEnd(if (completed) onScreen(last) else null)
                }
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(4.dp))
        CassetteThumb(tape, Modifier.width(76.dp).height(48.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(1.dp).height(42.dp).background(RackLine))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tape.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tape.subtitle, color = RackDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(1.dp).height(42.dp).background(RackLine))
        Column(Modifier.padding(start = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(meta, color = RackInk, fontSize = 15.sp)
            if (inDeck) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "IN DECK", style = EngravedCaps.copy(color = RackOrange, fontSize = 10.sp),
                    modifier = Modifier.border(BorderStroke(1.dp, RackOrange), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        // the handle: grab it to carry the tape in any direction
        Text("⋮", color = RackInk, fontSize = 24.sp, modifier = Modifier.padding(start = 6.dp, end = 4.dp))
    }
}

@Composable
private fun CoverThumb(uri: Uri?, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = uri?.let { Artwork.thumbnail(context, it)?.asImageBitmap() }
    }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF4A3F36)), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text("♪", color = RackDim, fontSize = 22.sp)
    }
}

/** The tape in the finger: a small white cassette with the title on its label, tilted as if lifted. */
@Composable
private fun DragCassette(tape: Tape<LocalTrack>, at: Offset) {
    val measurer = rememberTextMeasurer()
    val title = remember(tape) { measurer.measure(tape.title, TextStyle(fontFamily = FontFamily.Cursive, fontSize = 15.sp, color = Color(0xFF2B2622)), maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = androidx.compose.ui.unit.Constraints(maxWidth = 380)) }
    val width = 176.dp
    val height = 112.dp
    Canvas(
        Modifier
            .size(width, height)
            .offset { IntOffset((at.x - width.toPx() / 2f).roundToInt(), (at.y - height.toPx() / 2f).roundToInt()) }
            .graphicsLayer { rotationZ = -7f; shadowElevation = 28f; shape = RoundedCornerShape(10.dp); clip = false },
    ) {
        val r = CornerRadius(10.dp.toPx())
        drawRoundRect(Color(0xFFEDE8DC), cornerRadius = r)
        drawRoundRect(tape.kindColor, topLeft = Offset(0f, 0f), size = Size(size.width, 9.dp.toPx()), cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()))
        // label
        val pad = 10.dp.toPx()
        drawRoundRect(Color(0xFFFAF6EC), Offset(pad, 16.dp.toPx()), Size(size.width - pad * 2, 30.dp.toPx()), CornerRadius(4.dp.toPx()))
        drawText(title, topLeft = Offset(pad + 6.dp.toPx(), 18.dp.toPx() + (26.dp.toPx() - title.size.height) / 2f))
        // window with the two hubs
        val winTop = 52.dp.toPx()
        drawRoundRect(Color(0xFF2A2622), Offset(pad + 18.dp.toPx(), winTop), Size(size.width - pad * 2 - 36.dp.toPx(), 40.dp.toPx()), CornerRadius(8.dp.toPx()))
        for (cx in listOf(size.width * 0.34f, size.width * 0.66f)) {
            val c = Offset(cx, winTop + 20.dp.toPx())
            drawCircle(Color(0xFFE9E6DF), 13.dp.toPx(), c)
            drawCircle(Color(0xFF2A2622), 7.dp.toPx(), c)
            for (i in 0 until 6) {
                val a = Math.toRadians(i * 60.0)
                drawLine(Color(0xFFE9E6DF), c, Offset(c.x + 7.dp.toPx() * Math.cos(a).toFloat(), c.y + 7.dp.toPx() * Math.sin(a).toFloat()), 1.5.dp.toPx())
            }
        }
        drawRoundRect(Color.Black.copy(alpha = 0.18f), Offset(0f, 0f), size, r, style = Stroke(1.dp.toPx()))
    }
}

/** The tape's card (the J-card of a real cassette): its cover and what is on it, and what can be done with it. */
@Composable
private fun TapeCardSheet(
    tape: Tape<LocalTrack>,
    ui: DeckUi,
    mixtapes: List<Mixtape>,
    onInsert: () -> Unit,
    onAddTo: (String) -> Unit,
    onNewMixtapeWith: () -> Unit,
    onEdit: () -> Unit,
    onClose: () -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onClose() } }
            // a new pane: screen readers announce it and read it first
            .semantics { paneTitle = "Card of ${tape.title}"; isTraversalGroup = true },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(20.dp)
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(DeckColors.Paper)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                CoverThumb(tape.songs.firstOrNull()?.coverUri, Modifier.size(104.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tape.title, style = Hand.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(tape.subtitle, style = Hand.copy(fontSize = 17.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    val kind = when (tape.kind) {
                        TapeKind.ALBUM -> "ALBUM"
                        TapeKind.SONG -> "SINGLE"
                        TapeKind.MIXTAPE -> "MIXTAPE"
                        TapeKind.LIBRARY -> "EVERYTHING"
                    }
                    Text("$kind · ${Tapes.lengthText(tape.durationMs)}", style = Typed.copy(fontSize = 11.sp, color = DeckColors.Ink.copy(alpha = 0.6f)))
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(DeckColors.Accent))
            Spacer(Modifier.height(8.dp))
            if (choosing) {
                Text("ADD THIS SONG TO", style = EngravedCaps.copy(color = DeckColors.Ink, fontSize = 9.sp))
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(mixtapes, key = { it.id }) { m ->
                        Text(
                            m.name, style = Hand.copy(fontSize = 20.sp),
                            modifier = Modifier.fillMaxWidth().clickable { onAddTo(m.id); onClose() }.padding(vertical = 8.dp),
                        )
                    }
                    item(key = "new") {
                        Text(
                            "+ New mixtape", style = Hand.copy(fontSize = 20.sp, color = DeckColors.AccentDeep),
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onNewMixtapeWith).padding(vertical = 8.dp),
                        )
                    }
                }
            } else {
                if (tape.songs.size > 1) {
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        itemsIndexed(tape.songs, key = { _, s -> s.key }) { i, song ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("%02d".format(i + 1), style = Typed.copy(color = DeckColors.Ink.copy(alpha = 0.5f)), modifier = Modifier.width(30.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, style = Hand.copy(fontSize = 18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val sub = song.artist
                                    if (!sub.isNullOrBlank() && tape.kind != TapeKind.ALBUM) Text(sub, style = Typed.copy(fontSize = 10.sp, color = DeckColors.Ink.copy(alpha = 0.55f)), maxLines = 1)
                                }
                                Text(Tapes.lengthText(song.durationMs), style = Typed.copy(color = DeckColors.Ink.copy(alpha = 0.6f)))
                            }
                        }
                    }
                } else {
                    val song = tape.songs.firstOrNull()
                    if (song != null) {
                        Text("Album: ${song.folder}", style = Typed)
                        Text("Length: ${Tapes.lengthText(song.durationMs)}", style = Typed)
                        Text("Folder: ${song.folderPath}", style = Typed.copy(fontSize = 10.sp, color = DeckColors.Ink.copy(alpha = 0.6f)), maxLines = 2)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CardButton("Insert", onInsert, enabled = tape.songs.isNotEmpty())
                if (tape.kind == TapeKind.SONG && !choosing) CardButton("Add to mixtape", { choosing = true })
                if (tape.kind == TapeKind.MIXTAPE) CardButton("Edit", onEdit)
                CardButton(if (choosing) "Back" else "Close", { if (choosing) choosing = false else onClose() })
            }
        }
    }
}

@Composable
private fun CardButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .border(1.5.dp, DeckColors.Ink.copy(alpha = if (enabled) 1f else 0.35f), RoundedCornerShape(3.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text.uppercase(), style = EngravedCaps.copy(color = DeckColors.Ink.copy(alpha = if (enabled) 1f else 0.35f), fontSize = 10.sp))
    }
}

/** Making and editing a mixtape: a name, and the songs on it in order. */
@Composable
private fun MixtapeEditor(
    state: EditorState,
    library: List<LocalTrack>,
    onSave: (String, List<String>) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(state.name) }
    var keys by remember { mutableStateOf(state.keys) }
    var query by remember { mutableStateOf("") }
    val byKey = remember(library) { library.associateBy { it.key } }
    val chosen = keys.mapNotNull { byKey[it] }
    val candidates = remember(library, query) {
        if (query.isBlank()) library
        else library.filter { it.title.contains(query.trim(), true) || it.artist?.contains(query.trim(), true) == true || it.folder.contains(query.trim(), true) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF2A1F17), Color(0xFF1C1510))))
            .pointerInput(Unit) { detectTapGestures { } }
            .semantics { paneTitle = "Mixtape editor"; isTraversalGroup = true }
            .padding(14.dp),
    ) {
        Text(if (state.mixtapeId == null) "NEW MIXTAPE" else "EDIT MIXTAPE", style = EngravedCaps.copy(color = RackOrange, fontSize = 11.sp, letterSpacing = 3.sp))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "ON THIS TAPE · ${chosen.size} · ${Tapes.lengthText(chosen.sumOf { it.durationMs })}",
            style = EngravedCaps.copy(color = RackDim, fontSize = 9.sp, letterSpacing = 2.sp),
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF))) {
            if (chosen.isEmpty()) {
                Text("Pick songs from the list below.", color = RackDim, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(chosen, key = { _, s -> s.key }) { i, song ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("%02d".format(i + 1), color = RackDim, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, color = RackInk, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist ?: song.folder, color = RackDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            MiniAction("▲", "Move up", enabled = i > 0) { keys = keys.swap(i, i - 1) }
                            MiniAction("▼", "Move down", enabled = i < chosen.size - 1) { keys = keys.swap(i, i + 1) }
                            MiniAction("✕", "Remove") { keys = keys - song.key }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("ADD SONGS", style = EngravedCaps.copy(color = RackDim, fontSize = 9.sp, letterSpacing = 2.sp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search songs", fontSize = 13.sp) }, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.weight(1.3f).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF))) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(candidates, key = { it.key }) { song ->
                    val picked = song.key in keys
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { keys = if (picked) keys - song.key else keys + song.key }
                            .semantics { contentDescription = "${song.title}, ${if (picked) "on the tape" else "add to the tape"}" }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(song.title, color = RackInk, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist ?: song.folder, color = RackDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (picked) "✓" else "+", color = if (picked) RackOrange else RackDim, fontSize = 18.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            RackButton("Save", { onSave(name, keys) }, enabled = keys.isNotEmpty(), accent = true)
            RackButton("Cancel", onCancel)
            if (onDelete != null) RackButton("Delete", onDelete)
        }
    }
}

@Composable
private fun MiniAction(symbol: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = if (enabled) RackInk else RackDim.copy(alpha = 0.3f), fontSize = 14.sp)
    }
}

/** [this] with the items at [a] and [b] swapped. */
internal fun List<String>.swap(a: Int, b: Int): List<String> {
    if (a !in indices || b !in indices) return this
    val copy = toMutableList()
    val t = copy[a]
    copy[a] = copy[b]
    copy[b] = t
    return copy
}
