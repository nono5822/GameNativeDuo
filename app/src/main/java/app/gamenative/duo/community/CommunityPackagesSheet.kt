package app.gamenative.duo.community

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.display.DeviceProfile
import app.gamenative.duo.ui.UiLayoutStore
import app.gamenative.ui.util.SnackbarManager
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val CONTRIBUTING_URL = "https://github.com/nono5822/GameNativeDuo-community/blob/main/CONTRIBUTING.md"

/**
 * A game's community packages: the saved list shows at once (also offline), then a refresh replaces it if the index
 * changed. Packages tested on this device come first, then the most recently updated. Layouts and settings apply in
 * one tap; code mods are listed but not installable yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityPackagesSheet(containerId: String, gameTitle: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceId = remember { DeviceProfile.detect().id }
    var game by remember { mutableStateOf<CommunityGame?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var applied by remember { mutableStateOf<Map<CommunityKind, CommunityPackages.Applied>>(emptyMap()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<CommunityPackage?>(null) }
    var sharing by remember { mutableStateOf(false) }

    LaunchedEffect(containerId) {
        withContext(Dispatchers.IO) {
            applied = CommunityPackages.applied(context, containerId)
            CommunityPackages.cached(context, containerId)?.let { game = it }
            try {
                game = CommunityPackages.refresh(context, containerId)
                error = null
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "Community: could not refresh %s", containerId)
                error = t.message ?: t.javaClass.simpleName
            }
        }
        loading = false
    }

    fun apply(pkg: CommunityPackage) {
        busy = pkg.id
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                try {
                    CommunityPackages.apply(context, containerId, pkg)
                    applied = CommunityPackages.applied(context, containerId)
                    null
                } catch (t: Throwable) {
                    Timber.tag(DualScreenHost.TAG).w(t, "Community: could not apply %s", pkg.id)
                    t.message ?: t.javaClass.simpleName
                }
            }
            busy = null
            SnackbarManager.show(
                if (failure == null) {
                    context.getString(R.string.duo_community_applied, pkg.title)
                } else {
                    context.getString(R.string.duo_community_apply_failed, pkg.title, failure)
                },
            )
        }
    }

    fun requestApply(pkg: CommunityPackage) {
        // A layout the user made themselves is replaced only after asking.
        val ownLayout = pkg.kind == CommunityKind.UI_LAYOUT &&
            applied[CommunityKind.UI_LAYOUT] == null &&
            UiLayoutStore.hasUserLayout(context, containerId)
        if (ownLayout) confirm = pkg else apply(pkg)
    }

    fun removeLayout() {
        scope.launch {
            withContext(Dispatchers.IO) {
                CommunityPackages.removeLayout(context, containerId)
                applied = CommunityPackages.applied(context, containerId)
            }
            SnackbarManager.show(context.getString(R.string.duo_community_layout_removed))
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.duo_community_title), style = MaterialTheme.typography.titleLarge)
            Text(gameTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error != null && game != null) {
                Text(
                    stringResource(R.string.duo_community_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val packages = game?.packages.orEmpty()
            .sortedWith(compareByDescending<CommunityPackage> { deviceId in it.testedOn }.thenByDescending { it.updated })
        when {
            loading && game == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            game == null -> Message(stringResource(R.string.duo_community_load_failed, error.orEmpty()))
            packages.isEmpty() -> Message(stringResource(R.string.duo_community_empty))
            else -> LazyColumn(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                items(packages, key = { it.id }) { pkg ->
                    PackageCard(
                        pkg = pkg,
                        testedHere = deviceId in pkg.testedOn,
                        applied = applied[pkg.kind]?.takeIf { it.id == pkg.id },
                        busy = busy == pkg.id,
                        onApply = { requestApply(pkg) },
                        onRemove = ::removeLayout,
                    )
                }
            }
        }
        Row(modifier = Modifier.padding(start = 8.dp, bottom = 16.dp)) {
            TextButton(
                onClick = {
                    if (ShareLayout.canShare(context, containerId)) {
                        sharing = true
                    } else {
                        SnackbarManager.show(context.getString(R.string.duo_share_layout_none))
                    }
                },
            ) { Text(stringResource(R.string.duo_community_share)) }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CONTRIBUTING_URL))) }) {
                Text(stringResource(R.string.duo_community_contribute))
            }
        }
    }

    if (sharing) ShareLayoutDialog(containerId, gameTitle, onDismiss = { sharing = false })

    confirm?.let { pkg ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.duo_community_replace_title)) },
            text = { Text(stringResource(R.string.duo_community_replace_text, pkg.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    apply(pkg)
                }) { Text(stringResource(R.string.duo_community_replace_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun PackageCard(
    pkg: CommunityPackage,
    testedHere: Boolean,
    applied: CommunityPackages.Applied?,
    busy: Boolean,
    onApply: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pkg.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                KindBadge(pkg.kind)
            }
            Text(
                listOf("v${pkg.version}", pkg.authors.joinToString(", "), pkg.license, pkg.updated.take(10))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(pkg.description, style = MaterialTheme.typography.bodyMedium)
            val notes = buildList {
                if (testedHere) {
                    add(stringResource(R.string.duo_community_tested_here))
                } else if (pkg.testedOn.isNotEmpty()) {
                    add(stringResource(R.string.duo_community_tested_on, pkg.testedOn.joinToString(", ")))
                }
                if (pkg.gameBuilds.isNotEmpty()) add(stringResource(R.string.duo_community_game_version, pkg.gameBuilds.joinToString(", ")))
            }
            if (notes.isNotEmpty()) {
                Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (pkg.screenshots.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pkg.screenshots) { url ->
                        CoilImage(
                            imageModel = { url },
                            imageOptions = ImageOptions(contentScale = ContentScale.Fit),
                            modifier = Modifier.height(110.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val updatable = applied != null && CommunityPackages.isNewer(pkg.version, applied.version)
                when {
                    busy -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    pkg.kind !in CommunityIndex.APPLICABLE_KINDS -> Text(
                        stringResource(R.string.duo_community_not_installable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    updatable -> Button(onClick = onApply) { Text(stringResource(R.string.duo_community_update, pkg.version)) }
                    applied != null -> {
                        OutlinedButton(onClick = onApply) { Text(stringResource(R.string.duo_community_reapply)) }
                        if (pkg.kind == CommunityKind.UI_LAYOUT) {
                            TextButton(onClick = onRemove) { Text(stringResource(R.string.duo_community_remove)) }
                        }
                        Text(
                            stringResource(R.string.duo_community_in_use),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    else -> Button(onClick = onApply) { Text(stringResource(R.string.duo_community_apply)) }
                }
            }
        }
    }
}

@Composable
private fun KindBadge(kind: CommunityKind) {
    val label = when (kind) {
        CommunityKind.UI_LAYOUT -> R.string.duo_community_kind_layout
        CommunityKind.DUO_SETTINGS -> R.string.duo_community_kind_settings
        CommunityKind.DEVICE_PROFILE -> R.string.duo_community_kind_device
        CommunityKind.MOD -> R.string.duo_community_kind_mod
        CommunityKind.UNKNOWN -> R.string.duo_community_kind_other
    }
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
    )
}
