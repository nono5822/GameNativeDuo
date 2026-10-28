package app.gamenative.duo.mods

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.community.CommunityIndex
import app.gamenative.duo.community.CommunityKind
import app.gamenative.duo.community.CommunityModInstaller
import app.gamenative.duo.community.CommunityPackage
import app.gamenative.duo.community.CommunityRating
import app.gamenative.duo.community.CompatVerdict
import app.gamenative.duo.community.ShareLayout
import app.gamenative.duo.community.ShareLayoutDialog
import app.gamenative.ui.util.SnackbarManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import timber.log.Timber

/** GameNative Duo's logo blue (`duo/branding/README.md`): the official badge. */
private val DuoBlue = Color(0xFF5B8DF6)

private const val CONTRIBUTING_URL = "https://github.com/nono5822/GameNativeDuo-community/blob/main/CONTRIBUTING.md"

/**
 * Every dual-screen mod for one game, bundled and community, opened from the chevron of the Dual screen control. The
 * list shows at once from the cache (also offline) while a refresh runs; a card opens its detail page. The top section
 * is what the switch applies (one at a time); add-ons (layouts, settings) apply on top. D-pad: focus starts on the
 * sort chips, then the cards; the focused element is outlined; Back leaves the detail page, then the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModExplorerSheet(state: ModHubState, gameTitle: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var sort by rememberSaveable { mutableStateOf(HubSort.COMPAT) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var rating by remember { mutableStateOf<CommunityPackage?>(null) }
    LaunchedEffect(Unit) { state.refresh(force = true) }

    val detail = detailId?.let { id -> state.entries.firstOrNull { it.id == id } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Inside the sheet: it is its own window with its own back dispatcher, and this handler is newer than the sheet's.
        BackHandler(enabled = detail != null) { detailId = null }
        if (detail != null) {
            EntryDetail(state, detail, gameTitle, onBack = { detailId = null }, onRate = { rating = it })
        } else {
            ExplorerList(
                state = state,
                gameTitle = gameTitle,
                sort = sort,
                onSort = { sort = it },
                onOpen = { detailId = it.id },
                onShare = {
                    if (ShareLayout.canShare(context, state.containerId)) {
                        sharing = true
                    } else {
                        SnackbarManager.show(context.getString(R.string.duo_share_layout_none))
                    }
                },
            )
        }
    }

    if (sharing) ShareLayoutDialog(state.containerId, gameTitle, onDismiss = { sharing = false })
    rating?.let { pkg -> RateDialog(pkg, state, onDismiss = { rating = null }) }
    state.consent?.let { c ->
        ModConsentDialog(
            pkg = c.pkg,
            plan = c.plan,
            gameTitle = gameTitle,
            replacesMod = c.replacesMod,
            onConfirm = {
                state.consent = null
                c.then()
            },
            onDismiss = { state.consent = null },
        )
    }
    state.confirmLayout?.let { pkg ->
        AlertDialog(
            onDismissRequest = { state.confirmLayout = null },
            title = { Text(stringResource(R.string.duo_community_replace_title)) },
            text = { Text(stringResource(R.string.duo_community_replace_text, pkg.title)) },
            confirmButton = {
                TextButton(onClick = {
                    state.confirmLayout = null
                    state.applyAddOn(pkg)
                }) { Text(stringResource(R.string.duo_community_replace_confirm)) }
            },
            dismissButton = { TextButton(onClick = { state.confirmLayout = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun ExplorerList(
    state: ModHubState,
    gameTitle: String,
    sort: HubSort,
    onSort: (HubSort) -> Unit,
    onOpen: (HubEntry) -> Unit,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(stringResource(R.string.duo_hub_title), style = MaterialTheme.typography.titleLarge)
        Text(gameTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ListStatus(state)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            HubSort.entries.forEach { s ->
                FilterChip(
                    selected = sort == s,
                    onClick = { onSort(s) },
                    label = {
                        Text(
                            when (s) {
                                HubSort.COMPAT -> stringResource(R.string.duo_hub_sort_compat, state.deviceName)
                                HubSort.RATING -> stringResource(R.string.duo_hub_sort_rating)
                                HubSort.RECENT -> stringResource(R.string.duo_hub_sort_recent)
                            },
                            maxLines = 1,
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp).focusRing(RoundedCornerShape(8.dp)),
                )
            }
        }
    }
    val mods = ModHub.sort(state.switchable, sort, state.device.id)
    val addOns = ModHub.sort(state.addOns, sort, state.device.id)
    if (mods.isEmpty() && addOns.isEmpty()) {
        if (state.refreshing) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Message(stringResource(R.string.duo_hub_empty))
        }
    } else {
        LazyColumn(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            if (mods.isNotEmpty()) {
                item(key = "h-mods") { Section(stringResource(R.string.duo_hub_section_mods)) }
                items(mods, key = { "m-" + it.id }) { entry ->
                    EntryCard(state, entry, onOpen)
                }
            }
            if (addOns.isNotEmpty()) {
                item(key = "h-addons") { Section(stringResource(R.string.duo_hub_section_addons)) }
                items(addOns, key = { "a-" + it.id }) { entry ->
                    EntryCard(state, entry, onOpen)
                }
            }
        }
    }
    Row(modifier = Modifier.padding(start = 8.dp, bottom = 16.dp)) {
        val ring = Modifier.heightIn(min = 48.dp).focusRing(RoundedCornerShape(24.dp))
        TextButton(onClick = onShare, modifier = ring) { Text(stringResource(R.string.duo_community_share)) }
        TextButton(onClick = { open(context, CONTRIBUTING_URL) }, modifier = ring) {
            Text(stringResource(R.string.duo_community_contribute))
        }
    }
}

/** "List updated 2 h ago", "Checking for updates…" or "Offline · list from 3 days ago". */
@Composable
private fun ListStatus(state: ModHubState) {
    val now = System.currentTimeMillis()
    val age = state.checkedAt?.let {
        if (now - it < DateUtils.MINUTE_IN_MILLIS) {
            stringResource(R.string.duo_hub_just_now)
        } else {
            DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString()
        }
    }
    val text = when {
        state.refreshing -> stringResource(R.string.duo_hub_checking)
        state.refreshError != null && age != null -> stringResource(R.string.duo_hub_offline_ago, age)
        state.refreshError != null -> stringResource(R.string.duo_hub_offline_never, state.refreshError.orEmpty())
        age != null -> stringResource(R.string.duo_hub_updated_ago, age)
        else -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (state.refreshError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

/** Outlines the element while it or a child has focus, so D-pad users always see where they are. */
@Composable
private fun Modifier.focusRing(shape: RoundedCornerShape = RoundedCornerShape(12.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.hasFocus }
        .border(BorderStroke(3.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent), shape)
}

/** Gamepad A acts like a tap (Compose only maps Enter and D-pad centre). */
private fun Modifier.gamepadClick(onClick: () -> Unit) = onKeyEvent {
    if (it.key != Key.ButtonA) return@onKeyEvent false
    if (it.type == KeyEventType.KeyUp) onClick()
    true
}

@Composable
private fun EntryCard(state: ModHubState, entry: HubEntry, onOpen: (HubEntry) -> Unit) {
    val open = { onOpen(entry) }
    Card(
        onClick = open,
        modifier = Modifier
            .fillMaxWidth()
            .focusRing()
            .gamepadClick(open),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    hubTitle(entry),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StateTag(state, entry)
            }
            Badges(entry)
            MetaLine(entry)
            RatingLine(entry.rating)
            CompatLine(state, entry)
            ChangesLine(entry)
            EntryActions(state, entry, compact = true)
        }
    }
}

/** A bundled mod's title is the game's name, which the explorer already shows in its header. */
@Composable
private fun hubTitle(entry: HubEntry): String =
    if (entry is HubEntry.Bundled) stringResource(R.string.duo_hub_bundled_title) else entry.title

/** "On", "Selected", "Applied" or "Update 1.2": what this entry is to the game right now. */
@Composable
private fun StateTag(state: ModHubState, entry: HubEntry) {
    val (text, strong) = when {
        state.updateAvailable(entry) -> stringResource(R.string.duo_hub_update_available, (entry as HubEntry.Community).pkg.version) to true
        state.active?.id == entry.id -> stringResource(R.string.duo_hub_state_on) to true
        state.isSwitchable(entry) && state.selected?.id == entry.id -> stringResource(R.string.duo_hub_state_selected) to false
        state.appliedVersion(entry) != null -> stringResource(R.string.duo_hub_state_applied) to false
        else -> return
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (strong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (strong) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Badges(entry: HubEntry) {
    val pkg = (entry as? HubEntry.Community)?.pkg
    val colors = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (entry.official) {
            Badge(stringResource(R.string.duo_hub_badge_official), DuoBlue, Color.White, bold = true)
        } else {
            Badge(stringResource(R.string.duo_hub_badge_community), colors.secondaryContainer, colors.onSecondaryContainer)
        }
        if (entry.isCode) {
            Badge(stringResource(R.string.duo_hub_badge_code), colors.tertiaryContainer, colors.onTertiaryContainer)
        } else if (pkg != null) {
            Badge(stringResource(kindLabel(pkg.kind)), colors.surfaceVariant, colors.onSurfaceVariant)
        }
        if (pkg?.experimental == true) {
            Badge(stringResource(R.string.duo_community_experimental), colors.errorContainer, colors.onErrorContainer)
        }
    }
}

@Composable
private fun Badge(text: String, background: Color, content: Color, bold: Boolean = false) {
    Surface(shape = RoundedCornerShape(6.dp), color = background, contentColor = content) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (bold) FontWeight.Bold else null,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun kindLabel(kind: CommunityKind) = when (kind) {
    CommunityKind.UI_LAYOUT -> R.string.duo_community_kind_layout
    CommunityKind.DUO_SETTINGS -> R.string.duo_community_kind_settings
    CommunityKind.DEVICE_PROFILE -> R.string.duo_community_kind_device
    CommunityKind.MOD -> R.string.duo_community_kind_mod
    CommunityKind.UNKNOWN -> R.string.duo_community_kind_other
}

@Composable
private fun MetaLine(entry: HubEntry) {
    val text = metaLine(LocalContext.current, entry)
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "by Ann · v1.2 · 340 KB", or "by GameNative Duo · included with the app". */
private fun metaLine(context: Context, entry: HubEntry): String = when (entry) {
    is HubEntry.Bundled -> listOf(context.getString(R.string.duo_hub_by, "GameNative Duo"), context.getString(R.string.duo_hub_included))
    is HubEntry.Community -> listOf(
        entry.pkg.authors.joinToString(", ").takeIf { it.isNotBlank() }?.let { context.getString(R.string.duo_hub_by, it) }.orEmpty(),
        "v${entry.pkg.version}",
        entry.pkg.size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }.orEmpty(),
        entry.pkg.updated.take(10),
    )
}.filter { it.isNotBlank() }.joinToString(" · ")

@Composable
private fun RatingLine(rating: CommunityRating?) {
    val percent = rating?.percentUp
    Text(
        if (rating == null || percent == null) {
            stringResource(R.string.duo_hub_no_votes)
        } else {
            stringResource(R.string.duo_hub_rating, percent, rating.votes, rating.comments)
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun CompatLine(state: ModHubState, entry: HubEntry) {
    val name = state.deviceName
    val reports = entry.compat(state.device.id)
    val (text, color) = when (reports?.verdict) {
        CompatVerdict.WORKS -> stringResource(R.string.duo_hub_compat_works, name, reports.works, reports.total) to
            MaterialTheme.colorScheme.primary
        CompatVerdict.ISSUES -> stringResource(R.string.duo_hub_compat_issues, name, reports.works, reports.total) to
            MaterialTheme.colorScheme.tertiary
        CompatVerdict.BROKEN -> stringResource(R.string.duo_hub_compat_broken, name, reports.broken, reports.total) to
            MaterialTheme.colorScheme.error
        else -> when {
            entry is HubEntry.Bundled && entry.testedOn(state.device.id) -> stringResource(R.string.duo_hub_compat_official, name)
            entry.testedOn(state.device.id) -> stringResource(R.string.duo_hub_compat_tested, name)
            else -> stringResource(R.string.duo_hub_compat_none, name)
        } to if (entry.testedOn(state.device.id)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** What applying the entry changes, in a few words. */
@Composable
private fun ChangesLine(entry: HubEntry) {
    val parts = changes(entry).map { stringResource(it) }
    if (parts.isNotEmpty()) {
        Text(stringResource(R.string.duo_hub_changes, parts.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
    }
}

private fun changes(entry: HubEntry): List<Int> = when (entry) {
    // Every bundled mod talks to the bridge: native touch and an editable UI layout.
    is HubEntry.Bundled -> listOf(R.string.duo_hub_change_screens, R.string.duo_hub_change_layout, R.string.duo_hub_change_touch)
    is HubEntry.Community -> when (entry.pkg.kind) {
        CommunityKind.MOD -> listOfNotNull(
            R.string.duo_hub_change_screens,
            R.string.duo_hub_change_layout.takeIf { entry.pkg.adapter != null },
            R.string.duo_hub_change_touch.takeIf { entry.pkg.adapter != null },
        )
        CommunityKind.DUO_SETTINGS -> listOf(R.string.duo_hub_change_settings)
        CommunityKind.UI_LAYOUT -> listOf(R.string.duo_hub_change_layout)
        else -> emptyList()
    }
}

/** The main action for an entry: use/switch to it, update, apply or remove. [compact]: one button on the card. */
@Composable
private fun EntryActions(state: ModHubState, entry: HubEntry, compact: Boolean) {
    val pkg = (entry as? HubEntry.Community)?.pkg
    val busy = state.busy == entry.id || (state.busy == ModHubState.SWITCH && state.active?.id == entry.id)
    val enabled = state.busy == null
    val buttonModifier = Modifier.heightIn(min = 48.dp).focusRing(RoundedCornerShape(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            busy -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            pkg != null && pkg.kind !in CommunityIndex.APPLICABLE_KINDS -> Note(stringResource(R.string.duo_community_not_installable))
            state.updateAvailable(entry) -> Button(onClick = {
                state.update(entry as HubEntry.Community)
            }, enabled = enabled, modifier = buttonModifier) {
                Text(stringResource(R.string.duo_community_update, pkg!!.version))
            }
            state.isSwitchable(entry) -> when {
                state.active?.id == entry.id && !compact -> OutlinedButton(
                    onClick = { state.setOn(false) {} },
                    enabled = enabled,
                    modifier = buttonModifier,
                ) { Text(stringResource(R.string.duo_hub_turn_off)) }
                state.active?.id == entry.id -> Note(stringResource(R.string.duo_hub_in_use_on))
                state.selected?.id == entry.id && !state.on && !compact -> Button(
                    onClick = { state.setOn(true) {} },
                    enabled = enabled,
                    modifier = buttonModifier,
                ) { Text(stringResource(R.string.duo_hub_turn_on)) }
                state.selected?.id == entry.id -> Note(stringResource(R.string.duo_hub_in_use_selected))
                else -> Button(onClick = { state.choose(entry) }, enabled = enabled, modifier = buttonModifier) {
                    Text(stringResource(if (state.on) R.string.duo_hub_switch_to else R.string.duo_hub_use))
                }
            }
            pkg != null && state.appliedVersion(entry) != null -> {
                if (!compact) {
                    OutlinedButton(onClick = { state.requestAddOn(pkg) }, enabled = enabled, modifier = buttonModifier) {
                        Text(stringResource(R.string.duo_community_reapply))
                    }
                }
                if (pkg.kind == CommunityKind.UI_LAYOUT || pkg.kind == CommunityKind.DUO_SETTINGS) {
                    OutlinedButton(onClick = { state.removeAddOn(pkg) }, enabled = enabled, modifier = buttonModifier) {
                        Text(stringResource(R.string.duo_community_remove))
                    }
                }
            }
            pkg != null -> Button(onClick = { state.requestAddOn(pkg) }, enabled = enabled, modifier = buttonModifier) {
                Text(stringResource(R.string.duo_community_apply))
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EntryDetail(state: ModHubState, entry: HubEntry, gameTitle: String, onBack: () -> Unit, onRate: (CommunityPackage) -> Unit) {
    val context = LocalContext.current
    val pkg = (entry as? HubEntry.Community)?.pkg
    val back = remember { FocusRequester() }
    LazyColumn(
        // Full height like the list, so the sheet does not jump when a card opens.
        modifier = Modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        item(key = "head") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.focusRequester(back).focusRing(RoundedCornerShape(24.dp))) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.duo_hub_back))
                }
                Text(hubTitle(entry), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                StateTag(state, entry)
            }
        }
        item(key = "badges") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Badges(entry)
                MetaLine(entry)
                pkg?.license?.takeIf { it.isNotBlank() }?.let { Note(stringResource(R.string.duo_hub_licence, it)) }
                pkg?.gameBuilds?.takeIf {
                    it.isNotEmpty()
                }?.let { Note(stringResource(R.string.duo_community_game_version, it.joinToString(", "))) }
            }
        }
        item(key = "actions") { EntryActions(state, entry, compact = false) }
        item(key = "desc") {
            Text(
                pkg?.description ?: stringResource(R.string.duo_hub_bundled_description, gameTitle),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item(key = "changes") { ChangesLine(entry) }
        if (pkg != null && pkg.screenshots.isNotEmpty()) {
            item(key = "shots") {
                // Coil loads each screenshot when it scrolls into view, off the main thread.
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pkg.screenshots) { url ->
                        CoilImage(
                            imageModel = { url },
                            imageOptions = ImageOptions(contentScale = ContentScale.Fit),
                            modifier = Modifier.height(140.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
        }
        item(key = "rating") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HorizontalDivider()
                Text(stringResource(R.string.duo_hub_detail_rating), style = MaterialTheme.typography.titleSmall)
                RatingLine(entry.rating)
                CompatLine(state, entry)
                // Reports from other devices, for people choosing what to buy or how a mod fares elsewhere.
                entry.rating?.compat?.filterKeys { it != state.device.id }?.forEach { (device, r) ->
                    Note(stringResource(R.string.duo_hub_compat_other, ModHubState.deviceName(device), r.works, r.total))
                }
                entry.rating?.takeIf { !it.verified && it.votes > 0 }?.let { Note(stringResource(R.string.duo_hub_rate_unverified)) }
                if (pkg?.rating != null) {
                    OutlinedButton(onClick = {
                        onRate(pkg)
                    }, modifier = Modifier.heightIn(min = 48.dp).focusRing(RoundedCornerShape(24.dp))) {
                        Text(stringResource(R.string.duo_hub_rate))
                    }
                } else if (pkg != null) {
                    Note(stringResource(R.string.duo_hub_rate_soon))
                }
            }
        }
        if (pkg != null && pkg.changelog.isNotEmpty()) {
            item(key = "changelog") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider()
                    Text(stringResource(R.string.duo_hub_detail_changelog), style = MaterialTheme.typography.titleSmall)
                    pkg.changelog.take(5).forEach { c ->
                        Text(
                            listOf(c.version, c.date).filter {
                                it.isNotBlank()
                            }.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(c.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (pkg != null) {
            item(key = "links") {
                val page = ModHub.pageUrl(pkg, state.gameKey)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        open(context, page)
                    }, modifier = Modifier.heightIn(min = 48.dp).focusRing(RoundedCornerShape(24.dp))) {
                        Text(stringResource(R.string.duo_hub_open_browser))
                    }
                    TextButton(
                        onClick = { share(context, context.getString(R.string.duo_hub_share_text, pkg.title, gameTitle, page)) },
                        modifier = Modifier.heightIn(min = 48.dp).focusRing(RoundedCornerShape(24.dp)),
                    ) { Text(stringResource(R.string.duo_hub_share)) }
                }
            }
        }
    }
    LaunchedEffect(entry.id) { runCatching { back.requestFocus() } }
}

/**
 * Voting and compatibility reports both live on GitHub (sign-in there): the vote is a 👍/👎 reaction on the package's
 * discussion, a report is the repo's pre-filled report form. Each opens on this device or as a QR code for another.
 */
@Composable
private fun RateDialog(pkg: CommunityPackage, state: ModHubState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val rating = pkg.rating ?: return
    var qr by remember { mutableStateOf<String?>(null) }
    val device = state.deviceName
    val actions = buildList {
        add(stringResource(R.string.duo_hub_rate_vote) to rating.url)
        if (rating.reportUrl != null) {
            add(
                stringResource(R.string.duo_hub_rate_works, device) to
                    ModHub.reportUrl(pkg, state.device.id, CompatVerdict.WORKS, state.gameKey),
            )
            add(stringResource(R.string.duo_hub_rate_issues) to ModHub.reportUrl(pkg, state.device.id, CompatVerdict.ISSUES, state.gameKey))
            add(stringResource(R.string.duo_hub_rate_broken) to ModHub.reportUrl(pkg, state.device.id, CompatVerdict.BROKEN, state.gameKey))
        }
    }.mapNotNull { (label, url) -> url?.let { label to it } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.duo_hub_rate_title, pkg.title)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val shown = qr
                if (shown != null) {
                    // In place of the explanation, so it fits the dialog without scrolling.
                    item(key = shown) {
                        val bitmap = remember(shown) { qrBitmap(shown) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            bitmap?.let { Image(it.asImageBitmap(), contentDescription = shown, modifier = Modifier.size(180.dp)) }
                            Note(stringResource(R.string.duo_hub_rate_qr_hint))
                        }
                    }
                } else {
                    item {
                        // The one-vote rules are only claimed once the index says the bot applies them.
                        val rules = if (rating.verified) " " + stringResource(R.string.duo_hub_rate_rules) else ""
                        Text(stringResource(R.string.duo_hub_rate_text) + rules, style = MaterialTheme.typography.bodySmall)
                    }
                    if (rating.reportUrl == null) {
                        item { Note(stringResource(R.string.duo_hub_rate_no_form)) }
                    }
                }
                items(actions) { (label, url) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { open(context, url) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).focusRing(RoundedCornerShape(24.dp)),
                        ) { Text(label, modifier = Modifier.fillMaxWidth()) }
                        IconButton(
                            onClick = { qr = if (qr == url) null else url },
                            modifier = Modifier.focusRing(RoundedCornerShape(24.dp)),
                        ) { Icon(Icons.Outlined.QrCode2, contentDescription = stringResource(R.string.duo_hub_rate_qr)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.duo_hub_close)) } },
    )
}

private fun qrBitmap(text: String): Bitmap? = try {
    val size = 400
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels =
        IntArray(size * size) { i -> if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
} catch (t: Throwable) {
    Timber.tag(DualScreenHost.TAG).w(t, "Mod hub: no QR code")
    null
}

private fun open(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).w(t, "Mod hub: no browser for %s", url)
        SnackbarManager.show(context.getString(R.string.duo_share_layout_failed))
    }
}

private fun share(context: Context, text: String) {
    try {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).w(t, "Mod hub: cannot share")
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

/** What a code mod will do, in plain words, before anything is downloaded or written. */
@Composable
private fun ModConsentDialog(
    pkg: CommunityPackage,
    plan: CommunityModInstaller.Plan,
    gameTitle: String,
    replacesMod: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.duo_mod_consent_title, pkg.title)) },
        text = {
            // Scrolls: on a landscape 1080p screen the full list does not fit.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.duo_mod_consent_warning, gameTitle), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.duo_mod_consent_author, pkg.authors.joinToString(", "), pkg.license))
                val lines = buildList {
                    val game = plan.gameFiles.size + plan.sharedAssets.size
                    if (game > 0) add(stringResource(R.string.duo_mod_consent_game_files, game))
                    if (plan.prefixFiles.isNotEmpty()) add(stringResource(R.string.duo_mod_consent_prefix_files, plan.prefixFiles.size))
                    if (plan.dllOverrides.isNotEmpty()) {
                        add(stringResource(R.string.duo_mod_consent_dlls, plan.dllOverrides.joinToString(", ")))
                    }
                    plan.launchExecutable?.let { add(stringResource(R.string.duo_mod_consent_launch, it)) }
                    add(stringResource(R.string.duo_mod_consent_split))
                    if (plan.downloadBytes > 0) add(stringResource(R.string.duo_mod_consent_download, (plan.downloadBytes + 1023) / 1024))
                    if (replacesMod) add(stringResource(R.string.duo_mod_consent_replaces))
                    if (pkg.experimental) add(stringResource(R.string.duo_mod_consent_experimental))
                }
                lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.duo_mod_consent_undo), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.duo_mod_consent_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
