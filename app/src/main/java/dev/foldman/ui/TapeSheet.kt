package dev.foldman.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foldman.deck.DeckUi
import dev.foldman.playback.TapeKeys
import kotlin.math.ceil
import kotlin.math.roundToInt

/** What the settings sheet can change. */
class TapeSheetActions(
    val character: (key: String, value: Float) -> Unit,
    val reset: () -> Unit,
    val replayGain: (Boolean) -> Unit,
    val autoReverse: (Boolean) -> Unit,
    val sleep: (Int) -> Unit,
)

private val SLEEP_CHOICES = listOf(0, 15, 30, 45, 60, 90)
private val Dim = Color(0xFFB9B2A8)

/** The tape's character, auto-reverse and the sleep timer; opened by pressing and holding the cassette. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TapeSheet(ui: DeckUi, actions: TapeSheetActions, modifier: Modifier = Modifier) {
    val c = ui.character
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Tape", color = Color(0xFFF2EBDC), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Make the music sound like it has been through a cassette. Everything at zero is the clean sound of the file. " +
                "These, and the TAPE and NR controls on the player, apply to music from a local folder.",
            color = Dim, fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Character", color = Color(0xFFF2EBDC), fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = actions.reset) { Text("Reset") }
        }
        CharacterSlider("Wow and flutter", c.wow) { actions.character(TapeKeys.WOW, it) }
        CharacterSlider("Hiss", c.hiss) { actions.character(TapeKeys.HISS, it) }
        CharacterSlider("Saturation", c.warmth) { actions.character(TapeKeys.WARMTH, it) }
        CharacterSlider("Head bump", c.bump) { actions.character(TapeKeys.BUMP, it) }

        Spacer(Modifier.height(8.dp))
        SwitchRow("Level songs (ReplayGain)", "Evens out quiet and loud albums, from their tags.", c.replayGain, actions.replayGain)
        SwitchRow(
            "Auto-reverse",
            "At the end of the tape, turn it over (side ${if (ui.side == 0) "B" else "A"}) and keep playing.",
            ui.autoReverse, actions.autoReverse,
        )

        Spacer(Modifier.height(8.dp))
        Text("Sleep timer", color = Color(0xFFF2EBDC), fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(
            if (ui.sleepMinutes > 0) "Stops in ${ceil(ui.sleepRemainingMs / 60000.0).toInt().coerceAtLeast(1)} min, fading out at the end."
            else "Off",
            color = Dim, fontSize = 13.sp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (minutes in SLEEP_CHOICES) {
                FilterChip(
                    selected = ui.sleepMinutes == minutes,
                    onClick = { actions.sleep(minutes) },
                    label = { Text(if (minutes == 0) "Off" else "$minutes") },
                )
            }
        }
        Text("minutes", color = Dim, fontSize = 12.sp)
    }
}

@Composable
private fun CharacterSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(label, color = Color(0xFFE6DFD2), fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", color = Dim, fontSize = 13.sp)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = 0f..1f,
            modifier = Modifier.semantics { contentDescription = "$label ${(value * 100).roundToInt()} percent" },
        )
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row is the switch, so a screen reader reads the label with it.
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color(0xFFE6DFD2), fontSize = 14.sp)
            Text(detail, color = Dim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
