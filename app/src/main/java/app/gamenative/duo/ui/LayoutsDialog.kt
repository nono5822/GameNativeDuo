package app.gamenative.duo.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.duo.DualScreenHost
import app.gamenative.ui.util.SnackbarManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * "My layouts" for one game ([UiLayoutLibrary]): choose the active one, duplicate, rename, delete, export to a file,
 * import from one. Opened from the mod explorer, between game sessions. The file work runs on [Dispatchers.IO].
 */
@Composable
fun LayoutsDialog(containerId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<UiLayoutLibrary.Entry>>(emptyList()) }
    var activeId by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<UiLayoutLibrary.Entry?>(null) }
    var deleting by remember { mutableStateOf<UiLayoutLibrary.Entry?>(null) }
    var exporting by remember { mutableStateOf<UiLayoutLibrary.Entry?>(null) }

    /** Runs [change] on the library off the main thread, then shows the new list. Problems become a message. */
    fun act(change: (UiLayoutLibrary) -> Unit) {
        scope.launch {
            val (library, problem) = withContext(Dispatchers.IO) {
                try {
                    val library = UiLayoutLibrary.of(context, containerId)
                    change(library)
                    library to null
                } catch (t: Throwable) {
                    Timber.tag(DualScreenHost.TAG).w(t, "Layouts of %s: a change failed", containerId)
                    runCatching { UiLayoutLibrary.of(context, containerId) }.getOrNull() to (t.message ?: t.javaClass.simpleName)
                }
            }
            if (library != null) {
                entries = library.entries
                activeId = library.activeId
            }
            if (problem != null) SnackbarManager.show(problem)
        }
    }
    LaunchedEffect(containerId) { act {} }

    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val entry = exporting
        exporting = null
        if (uri != null && entry != null) {
            act { library ->
                val text = library.text(entry.id) ?: UiLayout(containerId).toJson().toString(2)
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    ?: error(context.getString(R.string.duo_layouts_export_failed))
            }
        }
    }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            act { library ->
                val text = readText(context, uri)
                // The file says which game it was made for; here it becomes this game's.
                val layout = UiLayout.fromJson(JSONObject(text).also { library.checkFormat(it) }, containerId).copy(game = containerId)
                library.add(displayName(context, uri), layout.toJson().toString(2))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.duo_layouts_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.duo_layouts_text), style = MaterialTheme.typography.bodySmall)
                entries.forEach { e ->
                    val use = { act { it.activate(e.id) } }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRing().clickable(onClick = use).gamepadA(use),
                    ) {
                        RadioButton(selected = e.id == activeId, onClick = use)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.titleSmall)
                            if (e.source.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.duo_layouts_from_community),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Row {
                        Action(R.string.duo_layouts_duplicate) { act { it.duplicate(e.id) } }
                        Action(R.string.duo_layouts_rename) { renaming = e }
                        Action(R.string.duo_layouts_export) {
                            exporting = e
                            exportTo.launch(e.name.replace(Regex("[^A-Za-z0-9 _.-]"), "_") + ".json")
                        }
                        Action(R.string.duo_layouts_delete) { deleting = e }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.duo_layouts_done)) } },
        dismissButton = {
            TextButton(onClick = { importFrom.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) {
                Text(stringResource(R.string.duo_layouts_import))
            }
        },
    )

    renaming?.let { e ->
        var name by remember(e.id) { mutableStateOf(e.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.duo_layouts_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(UiLayoutLibrary.MAX_NAME) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        renaming = null
                        act { it.rename(e.id, name) }
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.duo_layouts_delete_title, e.name)) },
            text = {
                Text(stringResource(if (entries.size == 1) R.string.duo_layouts_delete_last else R.string.duo_layouts_delete_text))
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    act { it.delete(e.id) }
                }) { Text(stringResource(R.string.duo_layouts_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun Action(label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).focusRing().gamepadA(onClick)) {
        Text(stringResource(label), maxLines = 1)
    }
}

/** Outlines the element while it has focus, so D-pad users see where they are. */
@Composable
private fun Modifier.focusRing(): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.hasFocus }
        .border(BorderStroke(3.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent), RoundedCornerShape(12.dp))
}

/** Gamepad A acts like a tap (Compose only maps Enter and D-pad centre). */
private fun Modifier.gamepadA(onClick: () -> Unit) = onKeyEvent {
    if (it.key != Key.ButtonA) return@onKeyEvent false
    if (it.type == KeyEventType.KeyUp) onClick()
    true
}

/** The file's text, refused above [MAX_IMPORT] (a layout is a few kilobytes). */
private fun readText(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_IMPORT) error(context.getString(R.string.duo_layouts_import_too_big))
        }
        out.toByteArray()
    } ?: error(context.getString(R.string.duo_layouts_import_failed))
    return String(bytes, Charsets.UTF_8)
}

/** The picked file's name without its extension, as the new layout's name. */
private fun displayName(context: Context, uri: Uri): String {
    val name = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (t: Throwable) {
        null
    }
    return name?.substringBeforeLast('.')?.trim()?.ifEmpty { null } ?: UiLayoutLibrary.DEFAULT_NAME
}

private const val MAX_IMPORT = 1024 * 1024
