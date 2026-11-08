package app.gamenative.duo.mods

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.duo.bridge.BridgePackets
import kotlinx.coroutines.launch

/** Amber for "needs a fix", green for "worked last time" (readable on the dark library background). */
internal val StatusWarn = Color(0xFFFFB74D)
internal val StatusOk = Color(0xFF66BB6A)

/** The icon and colour of the Dual screen control's status segment. */
internal fun statusIcon(report: ModStatusReport?): Pair<ImageVector, Color> = when {
    report == null || !report.on -> Icons.Outlined.Info to Color.White.copy(alpha = 0.6f)
    report.problems.isNotEmpty() -> Icons.Outlined.WarningAmber to StatusWarn
    report.worked -> Icons.Outlined.CheckCircle to StatusOk
    else -> Icons.Outlined.Info to Color.White.copy(alpha = 0.75f)
}

/** Short text for the first problem, shown under "Dual screen" instead of the mod's name. */
@Composable
internal fun problemShort(problem: ModProblem): String = stringResource(
    when (problem) {
        ModProblem.GAMESCOPE -> R.string.duo_status_short_gamescope
        ModProblem.WRONG_SIZE -> R.string.duo_status_short_size
        ModProblem.FILES_MISSING -> R.string.duo_status_short_missing
        ModProblem.MOD_NOT_LOADED -> R.string.duo_status_short_not_loaded
    },
)

/**
 * "Dual screen status": why Dual screen will or will not work for this game, each problem with its fix, what the last
 * session saw, the mod's files (check, reset) and the save backups (restore). Every button also takes gamepad A.
 */
@Composable
fun ModStatusDialog(state: ModHubState, onDismiss: () -> Unit) {
    val report = state.status
    var confirmRestore by remember { mutableStateOf<SaveBackup.Backup?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val busy = state.busy != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.duo_status_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (report == null || !report.on) {
                    Line(Icons.Outlined.Info, null, stringResource(R.string.duo_status_off))
                } else {
                    report.problems.forEach { problem -> Problem(state, report, problem, busy) }
                    LastSession(report)
                }
                if (report?.on == true) {
                    HorizontalDivider()
                    Files(state, busy, onReset = { confirmReset = true })
                }
                HorizontalDivider()
                Saves(state, busy, onRestore = { confirmRestore = it })
                if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp).align(Alignment.CenterHorizontally))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.gamepadA(onDismiss)) { Text(stringResource(R.string.duo_status_close)) }
        },
    )
    if (confirmReset) {
        Confirm(
            title = stringResource(R.string.duo_status_reset),
            text = stringResource(R.string.duo_status_reset_confirm),
            confirm = stringResource(R.string.duo_status_reset),
            onConfirm = {
                confirmReset = false
                state.resetFiles()
            },
            onDismiss = { confirmReset = false },
        )
    }
    confirmRestore?.let { backup ->
        Confirm(
            title = stringResource(R.string.duo_saves_restore_title),
            text = stringResource(R.string.duo_saves_restore_confirm, ago(backup.createdAt)),
            confirm = stringResource(R.string.duo_saves_restore),
            onConfirm = {
                confirmRestore = null
                state.restoreSave(backup)
            },
            onDismiss = { confirmRestore = null },
        )
    }
}

@Composable
private fun Problem(state: ModHubState, report: ModStatusReport, problem: ModProblem, busy: Boolean) {
    val text = when (problem) {
        ModProblem.GAMESCOPE -> stringResource(R.string.duo_status_gamescope)
        ModProblem.WRONG_SIZE -> stringResource(R.string.duo_status_size, report.screen, report.expectedScreen)
        ModProblem.FILES_MISSING -> pluralStringResource(R.plurals.duo_status_missing, report.missingFiles, report.missingFiles)
        ModProblem.MOD_NOT_LOADED -> stringResource(R.string.duo_status_not_loaded)
    } + if (report.gameUpdated && problem != ModProblem.GAMESCOPE && problem != ModProblem.WRONG_SIZE) {
        " " + stringResource(R.string.duo_status_game_updated)
    } else {
        ""
    }
    val action = when (problem) {
        ModProblem.GAMESCOPE -> stringResource(R.string.duo_status_fix_x11)
        ModProblem.WRONG_SIZE -> stringResource(R.string.duo_status_fix_size, report.expectedScreen)
        ModProblem.FILES_MISSING, ModProblem.MOD_NOT_LOADED -> stringResource(R.string.duo_status_reset)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Line(Icons.Outlined.WarningAmber, StatusWarn, text)
        val fix = { state.fix(problem) }
        Button(onClick = fix, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).gamepadA { if (!busy) fix() }) { Text(action) }
    }
}

@Composable
private fun LastSession(report: ModStatusReport) {
    val last = report.last
    when {
        last == null || last.modId == null -> Line(Icons.Outlined.Info, null, stringResource(R.string.duo_status_never))
        last.connected -> {
            val engine = last.engine.ifEmpty { "?" }
            Line(
                Icons.Outlined.CheckCircle,
                StatusOk,
                stringResource(R.string.duo_status_worked, engine, ((last.connectedAfterMs + 500) / 1000).toInt(), ago(last.startedAt)),
            )
            if (BridgePackets.sdkIsOlder(last.sdk)) {
                Line(Icons.Outlined.Info, null, stringResource(R.string.duo_status_old_sdk, last.sdk, BridgePackets.SDK_VERSION))
            }
        }
        else -> Unit
    }
    if (last != null && last.devFiles > 0) {
        Line(Icons.Outlined.Info, null, stringResource(R.string.duo_status_dev_files, last.devFiles))
    }
    when {
        last == null || last.modId == null || last.connected -> Unit
        last.gamescope -> Line(Icons.Outlined.Info, null, stringResource(R.string.duo_status_last_gamescope, ago(last.startedAt)))
        !last.splitOn -> Line(Icons.Outlined.Info, null, stringResource(R.string.duo_status_last_no_split, ago(last.startedAt)))
        !last.modExpected -> Line(Icons.Outlined.Info, null, stringResource(R.string.duo_status_no_bridge, ago(last.startedAt)))
        last.durationMs < ModStatusReport.MIN_SESSION_MS -> Line(Icons.Outlined.Info, null, stringResource(R.string.duo_status_too_short))
        else -> Unit // MOD_NOT_LOADED says it
    }
}

@Composable
private fun Files(state: ModHubState, busy: Boolean, onReset: () -> Unit) {
    val scope = rememberCoroutineScope()
    // A fix or reset reloads the status: an older check no longer holds.
    var files by remember(state.status) { mutableStateOf<List<DuoModInstaller.ModFile>?>(null) }
    var checking by remember { mutableStateOf(false) }
    Text(stringResource(R.string.duo_status_files), style = MaterialTheme.typography.titleSmall)
    val list = files
    if (list == null) {
        val check: () -> Unit = {
            if (!checking) {
                checking = true
                scope.launch {
                    files = state.fileReport()
                    checking = false
                }
            }
        }
        OutlinedButton(onClick = check, enabled = !checking, modifier = Modifier.heightIn(min = 48.dp).gamepadA(check)) {
            Text(stringResource(if (checking) R.string.duo_status_files_checking else R.string.duo_status_files_check))
        }
    } else {
        val ours = list.count { it.state == DuoModInstaller.FileState.OURS }
        val changed = list.filter { it.state == DuoModInstaller.FileState.CHANGED }
        val missing = list.filter { it.state == DuoModInstaller.FileState.MISSING }
        Text(
            stringResource(R.string.duo_status_files_summary, list.size, ours, changed.size, missing.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (changed.isNotEmpty()) Note(stringResource(R.string.duo_status_files_changed, names(changed)))
        if (missing.isNotEmpty()) Note(stringResource(R.string.duo_status_files_missing, names(missing)))
    }
    Note(stringResource(R.string.duo_status_reset_note))
    val reset = { if (!busy) onReset() }
    OutlinedButton(onClick = reset, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).gamepadA(reset)) {
        Text(stringResource(R.string.duo_status_reset))
    }
}

private fun names(files: List<DuoModInstaller.ModFile>): String {
    val shown = files.take(6).joinToString(", ") { it.path.substringAfterLast('/') }
    return if (files.size > 6) "$shown, +${files.size - 6}" else shown
}

@Composable
private fun Saves(state: ModHubState, busy: Boolean, onRestore: (SaveBackup.Backup) -> Unit) {
    val context = LocalContext.current
    Text(stringResource(R.string.duo_saves_title), style = MaterialTheme.typography.titleSmall)
    if (state.saves.isEmpty()) {
        Note(stringResource(R.string.duo_saves_none))
        return
    }
    state.saves.take(3).forEach { backup ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(ago(backup.createdAt), style = MaterialTheme.typography.bodyMedium)
                Text(
                    backup.reason + " · " + Formatter.formatShortFileSize(context, backup.bytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val restore = { if (!busy) onRestore(backup) }
            TextButton(onClick = restore, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).gamepadA(restore)) {
                Text(stringResource(R.string.duo_saves_restore))
            }
        }
    }
    Note(stringResource(R.string.duo_saves_cloud_note))
}

@Composable
private fun Confirm(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.gamepadA(onConfirm)) { Text(confirm) } },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.gamepadA(onDismiss)) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun Line(icon: ImageVector, tint: Color?, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "5 minutes ago", "yesterday", ... */
private fun ago(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** Gamepad A acts like a tap (Compose only maps Enter and D-pad centre). */
private fun Modifier.gamepadA(onClick: () -> Unit) = onKeyEvent {
    if (it.key != Key.ButtonA) return@onKeyEvent false
    if (it.type == KeyEventType.KeyUp) onClick()
    true
}
