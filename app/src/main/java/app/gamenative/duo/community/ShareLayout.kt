package app.gamenative.duo.community

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.display.DeviceProfile
import app.gamenative.duo.ui.UiLayoutStore
import app.gamenative.ui.util.SnackbarManager
import java.io.File
import timber.log.Timber

/**
 * Sharing a layout made with the in-game editor: opens the community repo's "Share a UI layout" issue form in the
 * browser, filled in. A bot there turns it into a package pull request. No GitHub knowledge needed beyond signing in.
 */
object ShareLayout {
    private const val NEW_ISSUE = "https://github.com/nono5822/GameNativeDuo-community/issues/new"

    /** Browsers and GitHub cope with ~8k characters; a longer layout goes through the clipboard. */
    private const val MAX_URL = 7500

    fun canShare(context: Context, containerId: String): Boolean = UiLayoutStore.hasUserLayout(context, containerId)

    fun lastAuthor(context: Context): String = try {
        authorFile(context).takeIf { it.isFile }?.readText()?.trim().orEmpty()
    } catch (t: Throwable) {
        ""
    }

    /** Opens the filled-in form. Returns false (and logs) when no browser could open it. */
    fun open(context: Context, containerId: String, gameTitle: String, name: String, description: String, author: String): Boolean {
        try {
            authorFile(context).apply { parentFile?.mkdirs() }.writeText(author)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Could not remember the author name")
        }
        val layout = UiLayoutStore.load(context, containerId).toJson().toString()
        fun url(withLayout: Boolean) = Uri.parse(NEW_ISSUE).buildUpon()
            .appendQueryParameter("template", "share-layout.yml")
            .appendQueryParameter("title", "Layout: $name ($gameTitle)")
            .appendQueryParameter("game", containerId)
            .appendQueryParameter("game-title", gameTitle)
            .appendQueryParameter("name", name)
            .appendQueryParameter("description", description)
            .appendQueryParameter("author", author)
            .appendQueryParameter("device", DeviceProfile.detect().id)
            .apply { if (withLayout) appendQueryParameter("layout", layout) }
            .build()
        var uri = url(withLayout = true)
        if (uri.toString().length > MAX_URL) {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("GameNative Duo layout", layout))
            SnackbarManager.show(context.getString(R.string.duo_share_layout_clipboard))
            uri = url(withLayout = false)
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Could not open the share form")
            false
        }
    }

    private fun authorFile(context: Context) = File(context.filesDir, "duo/community/author.txt")
}

/** Asks for the layout's name, a description and the author name, then opens the form. */
@Composable
fun ShareLayoutDialog(containerId: String, gameTitle: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var author by remember { mutableStateOf(ShareLayout.lastAuthor(context)) }
    val ready = name.isNotBlank() && description.isNotBlank() && author.any { it.isLetterOrDigit() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.duo_share_layout_title)) },
        text = {
            Column {
                Text(stringResource(R.string.duo_share_layout_text), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text(stringResource(R.string.duo_share_layout_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(600) },
                    label = { Text(stringResource(R.string.duo_share_layout_description)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it.take(60) },
                    label = { Text(stringResource(R.string.duo_share_layout_author)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    if (!ShareLayout.open(context, containerId, gameTitle, name.trim(), description.trim(), author.trim())) {
                        SnackbarManager.show(context.getString(R.string.duo_share_layout_failed))
                    }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.duo_share_layout_open)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
