package app.gamenative.duo.mods

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R

/**
 * "Dual screen" control next to a game's Play button: a switch that turns the selected dual-screen mod on and off,
 * joined to a chevron that opens the explorer of every mod for the game (bundled and community), where the mod the
 * switch applies is chosen. Shown for games with a bundled mod or community packages; nothing for other games or when
 * the game is not installed. Never waits on the network: the cached list shows first.
 */
@Composable
fun DualScreenToggle(appId: String, installLocation: String?, isInstalled: Boolean) {
    if (!isInstalled || installLocation.isNullOrBlank()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(appId, installLocation) { ModHubState(context.applicationContext, appId, installLocation, scope) }
    var explorer by remember(appId) { mutableStateOf(false) }
    LaunchedEffect(state) { state.load() }
    if (!state.visible) return

    val hasSwitch = state.switchable.isNotEmpty() || state.bundled != null || state.on
    val switchBusy = state.busy == ModHubState.SWITCH || !state.loaded
    val selected = state.active ?: state.selected
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.12f), contentColor = Color.White) {
        Row(modifier = Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            if (hasSwitch) {
                val toggle = { if (!switchBusy) state.setOn(!state.on) { explorer = true } }
                Row(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .focusOutline(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                        .gamepadA(toggle)
                        .clickable(enabled = state.busy == null && state.loaded, role = Role.Switch, onClick = toggle)
                        .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Splitscreen, contentDescription = null, modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.widthIn(max = 160.dp)) {
                        Text(stringResource(R.string.duo_mod_toggle_label), style = MaterialTheme.typography.labelLarge)
                        Text(
                            selected?.let { shortTitle(it) } ?: stringResource(R.string.duo_hub_choose),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(modifier = Modifier.size(width = 52.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                        if (switchBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            // The whole row is the touch/focus target; the switch only shows the state.
                            Switch(checked = state.on, onCheckedChange = null)
                        }
                    }
                }
                VerticalDivider(modifier = Modifier.fillMaxHeight().padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.25f))
            }
            val openExplorer = { explorer = true }
            val start = if (hasSwitch) 0.dp else 12.dp
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 48.dp)
                    .focusOutline(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp, topStart = start, bottomStart = start))
                    .gamepadA(openExplorer)
                    .clickable(role = Role.DropdownList, onClick = openExplorer)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!hasSwitch) {
                    // Only layouts for this game: the control is just the way into the explorer.
                    Icon(Icons.Outlined.Splitscreen, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.duo_hub_title_short), style = MaterialTheme.typography.labelLarge)
                }
                Icon(Icons.Outlined.ExpandMore, contentDescription = stringResource(R.string.duo_hub_open), modifier = Modifier.size(24.dp))
            }
        }
    }
    if (explorer) {
        val title = state.game?.title?.takeIf { it.isNotBlank() } ?: state.bundled?.title.orEmpty()
        ModExplorerSheet(state, title, onDismiss = { explorer = false })
    }
}

/** The bundled mod's title is the game's own name; under the switch it reads as who made it. */
@Composable
private fun shortTitle(entry: HubEntry): String =
    if (entry is HubEntry.Bundled) stringResource(R.string.duo_hub_bundled_short) else entry.title

@Composable
private fun Modifier.focusOutline(shape: RoundedCornerShape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .border(BorderStroke(2.dp, if (focused) Color.White else Color.Transparent), shape)
}

/** Gamepad A acts like a tap. Must sit before `clickable`: key events reach modifiers above the focus target. */
private fun Modifier.gamepadA(onClick: () -> Unit) = onKeyEvent {
    if (it.key != Key.ButtonA) return@onKeyEvent false
    if (it.type == KeyEventType.KeyUp) onClick()
    true
}
