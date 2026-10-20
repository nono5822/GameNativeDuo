package app.gamenative.duo.mods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.duo.community.CommunityPackagesSheet
import app.gamenative.ui.util.SnackbarManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Dual screen" switch shown next to a game's Play button when GameNative Duo has a mod for it. Turning it on
 * applies the mod (files, screen size, env vars, screen split); turning it off undoes it. Next to it, "Community"
 * opens the layouts and settings others shared for the game. Shows nothing for other games or when the game is not
 * installed.
 */
@Composable
fun DualScreenToggle(appId: String, installLocation: String?, isInstalled: Boolean) {
    val mod = remember(appId) { DuoModCatalog.forApp(appId) } ?: return
    if (!isInstalled || installLocation.isNullOrBlank()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember(appId) { mutableStateOf<Boolean?>(null) }
    var busy by remember(appId) { mutableStateOf(false) }
    var showCommunity by remember(appId) { mutableStateOf(false) }
    LaunchedEffect(appId) {
        enabled = withContext(Dispatchers.IO) { DuoModInstaller.isEnabled(context, appId) }
    }

    val toggle: (Boolean) -> Unit = { on ->
        if (!busy) {
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    if (on) DuoModInstaller.enable(context, mod, File(installLocation)) else DuoModInstaller.disable(context, mod)
                }
                if (ok) enabled = on
                busy = false
                val message = when {
                    !ok -> R.string.duo_mod_toggle_failed
                    on -> R.string.duo_mod_toggle_enabled
                    else -> R.string.duo_mod_toggle_disabled
                }
                SnackbarManager.show(context.getString(message, mod.title))
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = { enabled?.let { toggle(!it) } },
            enabled = enabled != null && !busy,
            shape = RoundedCornerShape(12.dp),
            color = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Splitscreen, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.duo_mod_toggle_label), style = MaterialTheme.typography.labelLarge)
                if (busy || enabled == null) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Switch(checked = enabled == true, onCheckedChange = toggle)
                }
            }
        }
        Surface(
            onClick = { showCommunity = true },
            shape = RoundedCornerShape(12.dp),
            color = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Groups, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.duo_community_button), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (showCommunity) CommunityPackagesSheet(containerId = appId, gameTitle = mod.title, onDismiss = { showCommunity = false })
}
