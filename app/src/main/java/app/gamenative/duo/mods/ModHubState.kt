package app.gamenative.duo.mods

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.gamenative.R
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.community.AuthorKeyPins
import app.gamenative.duo.community.CommunityCatalog
import app.gamenative.duo.community.CommunityGame
import app.gamenative.duo.community.CommunityIndex
import app.gamenative.duo.community.CommunityKind
import app.gamenative.duo.community.CommunityModInstaller
import app.gamenative.duo.community.CommunityPackage
import app.gamenative.duo.community.CommunityPackages
import app.gamenative.duo.community.CommunityRevocations
import app.gamenative.duo.display.DeviceProfile
import app.gamenative.duo.settings.DuoSettingsStore
import app.gamenative.duo.split.SplitLayout
import app.gamenative.duo.ui.UiLayoutStore
import app.gamenative.ui.util.SnackbarManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * What a game's Dual screen control and its mod explorer show, and the actions both offer. Everything that touches
 * disk or network runs on [Dispatchers.IO]; state is only written on the main thread. One action at a time ([busy]).
 */
@Stable
class ModHubState(
    private val context: Context,
    val containerId: String,
    private val installLocation: String,
    private val scope: CoroutineScope,
) {
    val bundled: DuoGameMod? = DuoModCatalog.forApp(containerId)
    val device: DeviceProfile = DeviceProfile.detect()
    val gameKey = CommunityIndex.gameKey(containerId)

    /** Short name for labels: the generic profile's own name is a description. */
    val deviceName: String =
        if (device == DeviceProfile.GENERIC) context.getString(R.string.duo_hub_this_device) else device.displayName

    /** False until the first read from disk; the control shows a spinner until then. */
    var loaded by mutableStateOf(false)
        private set

    /** Region view is on for this game (no mod: the user's regions on the bottom screen). */
    var regionView by mutableStateOf(false)
        private set

    /** The game has something to show: a bundled mod, community packages (cached list or catalog), or region view. */
    var visible by mutableStateOf(bundled != null)
        private set
    var game by mutableStateOf<CommunityGame?>(null)
        private set
    var entries by mutableStateOf(ModHub.entries(bundled, null))
        private set
    var active by mutableStateOf<HubEntry?>(null)
        private set
    var on by mutableStateOf(false)
        private set
    var selected by mutableStateOf<HubEntry?>(null)
        private set
    var applied by mutableStateOf<Map<CommunityKind, CommunityPackages.Applied>>(emptyMap())
        private set

    /** When the list was last confirmed current with the server, or null if never. */
    var checkedAt by mutableStateOf<Long?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set

    /** Why the last refresh failed (offline, untrusted); null after a good one. */
    var refreshError by mutableStateOf<String?>(null)
        private set

    /** Id of the entry being changed, or [SWITCH] for the switch itself. */
    var busy by mutableStateOf<String?>(null)
        private set

    /** A code mod waiting for the user's consent, and what to do once given. */
    var consent by mutableStateOf<Consent?>(null)
    var confirmLayout by mutableStateOf<CommunityPackage?>(null)

    class Consent(
        val pkg: CommunityPackage,
        val plan: CommunityModInstaller.Plan,
        val replacesMod: Boolean,
        /** How the package's author key compares with the one it was installed with ([AuthorKeyPins]). */
        val owner: AuthorKeyPins.Check,
        val then: () -> Unit,
    )

    /** Whether Dual screen will work for this game, and what to fix (see [ModStatusReport]). */
    var status by mutableStateOf<ModStatusReport?>(null)
        private set

    /** Copies of the game's saves, newest first (taken before every mod install). */
    var saves by mutableStateOf<List<SaveBackup.Backup>>(emptyList())
        private set

    private class Snapshot(
        val game: CommunityGame?,
        val entries: List<HubEntry>,
        val active: HubEntry?,
        val on: Boolean,
        val selected: HubEntry?,
        val applied: Map<CommunityKind, CommunityPackages.Applied>,
        val checkedAt: Long?,
        val status: ModStatusReport?,
        val saves: List<SaveBackup.Backup>,
    )

    val switchable: List<HubEntry> get() = ModHub.switchable(entries)
    val addOns: List<HubEntry> get() = entries.filter { !ModHub.isSwitchable(it, entries) }

    fun isSwitchable(entry: HubEntry) = ModHub.isSwitchable(entry, entries)

    /** The applied version of an add-on (layout, settings), or of the community mod that is on. */
    fun appliedVersion(entry: HubEntry): String? = when (entry) {
        is HubEntry.Bundled -> null
        is HubEntry.Community -> applied[entry.pkg.kind]?.takeIf { it.id == entry.id }?.version
    }

    fun updateAvailable(entry: HubEntry): Boolean {
        val c = entry as? HubEntry.Community ?: return false
        val version = appliedVersion(entry) ?: return false
        return CommunityPackages.isNewer(c.pkg.version, version)
    }

    /** First read: the cached list (never the network), then a background refresh if the list is old. */
    suspend fun load() {
        val (snapshot, inCatalog) = withContext(Dispatchers.IO) {
            // A withdrawn mod comes off before anything else reads the install state (cached list, no network).
            CommunityRevocations.sweep(context)
            // After an app update: bring the mod's files up to date now, not at the next launch's session start.
            DuoModInstaller.refreshIfUpdated(context, containerId)
            val cached = CommunityPackages.cached(context, containerId)
            val settings = DuoSettingsStore.load(context, containerId)
            regionView = settings.screenSplit && SplitLayout.fromId(settings.splitLayout) == SplitLayout.REGION
            read(cached) to (CommunityCatalog.known(context)?.contains(gameKey) == true)
        }
        publish(snapshot)
        visible = bundled != null || inCatalog || snapshot.entries.isNotEmpty() || regionView
        loaded = true
        withContext(Dispatchers.IO) {
            CommunityCatalog.refreshOnce(context)
            CommunityRevocations.refreshOnce(context)
        }
        withContext(Dispatchers.IO) { CommunityRevocations.takeNotice(context, containerId) }?.let { removed ->
            val title = entries.firstOrNull { it.id == removed.packageId }?.title ?: removed.packageId
            SnackbarManager.show(context.getString(R.string.duo_community_withdrawn, title, removed.reason))
            reload(game)
        }
        if (!visible && CommunityCatalog.known(context)?.contains(gameKey) == true) visible = true
        if (visible) refresh(force = false)
    }

    /** Asks the server whether the game's list changed ([force]: always; else only when older than [MAX_AGE_MS]). */
    suspend fun refresh(force: Boolean) {
        if (refreshing) return
        if (!force && checkedAt?.let { System.currentTimeMillis() - it < MAX_AGE_MS } == true) return
        refreshing = true
        val (fresh, error) = withContext(Dispatchers.IO) {
            try {
                CommunityPackages.refresh(context, containerId) to null
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "Mod hub: could not refresh %s", containerId)
                null to (t.message ?: t.javaClass.simpleName)
            }
        }
        reload(fresh ?: game)
        refreshError = error
        refreshing = false
        if (entries.isNotEmpty()) visible = true
    }

    /** The switch. Turning on with nothing to apply opens the explorer instead ([openExplorer]). */
    fun setOn(value: Boolean, openExplorer: () -> Unit) {
        if (busy != null) return
        if (!value) {
            val title = active?.title ?: bundled?.title.orEmpty()
            run(SWITCH, { ModHub.turnOff(context, containerId, entries) }) { context.getString(R.string.duo_mod_toggle_disabled, title) }
            return
        }
        val target = selected ?: return openExplorer()
        withConsent(target) {
            run(SWITCH, { ModHub.turnOn(context, containerId, File(installLocation), target) }) {
                context.getString(R.string.duo_mod_toggle_enabled, target.title)
            }
        }
    }

    /** Makes [entry] the switch's mod; while Dual screen is on, the old one is undone and [entry] installed. */
    fun choose(entry: HubEntry) {
        if (busy != null) return
        if (!on) {
            busy = entry.id
            scope.launch {
                withContext(Dispatchers.IO) { ModHub.select(context, containerId, entry) }
                reload()
                busy = null
                SnackbarManager.show(context.getString(R.string.duo_hub_selected, entry.title))
            }
            return
        }
        val entriesNow = entries
        withConsent(entry) {
            run(entry.id, { ModHub.switchTo(context, containerId, File(installLocation), entry, entriesNow) }) {
                context.getString(R.string.duo_hub_switched, entry.title)
            }
        }
    }

    /** Installs the newer version of the community mod that is on, or re-applies an add-on. */
    fun update(entry: HubEntry.Community) {
        if (busy != null) return
        if (isSwitchable(entry)) {
            withConsent(entry) {
                run(entry.id, { ModHub.turnOn(context, containerId, File(installLocation), entry) }) {
                    context.getString(R.string.duo_community_applied, entry.title)
                }
            }
        } else {
            applyAddOn(entry.pkg)
        }
    }

    /** Layouts and settings that sit on top of the mod. A layout the user made is replaced only after asking. */
    fun requestAddOn(pkg: CommunityPackage) {
        if (busy != null) return
        scope.launch {
            val ownLayout = pkg.kind == CommunityKind.UI_LAYOUT &&
                applied[CommunityKind.UI_LAYOUT] == null &&
                withContext(Dispatchers.IO) { UiLayoutStore.hasUserLayout(context, containerId) }
            if (ownLayout) confirmLayout = pkg else applyAddOn(pkg)
        }
    }

    fun applyAddOn(pkg: CommunityPackage) {
        run(pkg.id, { CommunityPackages.apply(context, containerId, pkg) }) { context.getString(R.string.duo_community_applied, pkg.title) }
    }

    fun removeAddOn(pkg: CommunityPackage) {
        run(pkg.id, {
            when (pkg.kind) {
                CommunityKind.UI_LAYOUT -> CommunityPackages.removeLayout(context, containerId)
                CommunityKind.DUO_SETTINGS -> CommunityPackages.removeSettings(context, containerId)
                CommunityKind.GAME_PROFILE -> CommunityPackages.removeProfile(context, containerId)
                else -> Unit
            }
        }) { context.getString(R.string.duo_community_removed, pkg.title) }
    }

    /** Code mods run [block] only after the consent dialog, once per package version. */
    private fun withConsent(entry: HubEntry, block: () -> Unit) {
        val pkg = (entry as? HubEntry.Community)?.pkg?.takeIf { it.kind == CommunityKind.MOD } ?: return block()
        scope.launch {
            val ask = withContext(Dispatchers.IO) {
                try {
                    if (ModHub.hasConsent(context, containerId, entry)) {
                        null
                    } else {
                        val owner = AuthorKeyPins.check(context, pkg)
                        Consent(pkg, CommunityModInstaller.plan(pkg), DuoModInstaller.isEnabled(context, containerId), owner) {
                            scope.launch(Dispatchers.IO) { ModHub.recordConsent(context, containerId, pkg) }
                            block()
                        }
                    }
                } catch (t: Throwable) {
                    Timber.tag(DualScreenHost.TAG).w(t, "Mod hub: cannot install %s", pkg.id)
                    SnackbarManager.show(context.getString(R.string.duo_community_apply_failed, pkg.title, t.message.orEmpty()))
                    return@withContext Unit
                }
            }
            when (ask) {
                null -> block()
                is Consent -> consent = ask
            }
        }
    }

    private fun run(id: String, block: () -> Unit, done: () -> String) {
        busy = id
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                try {
                    block()
                    null
                } catch (t: Throwable) {
                    Timber.tag(DualScreenHost.TAG).w(t, "Mod hub: %s failed for %s", id, containerId)
                    t.message ?: t.javaClass.simpleName
                }
            }
            // A failed reload must not leave every button disabled (busy) or crash the app from this coroutine.
            try {
                reload()
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "Mod hub: reload failed for %s", containerId)
            } finally {
                busy = null
            }
            SnackbarManager.show(failure?.let { context.getString(R.string.duo_hub_failed, it) } ?: done())
        }
    }

    private suspend fun reload(g: CommunityGame? = game) = publish(withContext(Dispatchers.IO) { read(g) })

    private fun read(g: CommunityGame?): Snapshot {
        val list = ModHub.entries(bundled, g)
        val applied = CommunityPackages.applied(context, containerId).filter { (kind, a) ->
            // A mod counts only while it is still the one installed (another may have replaced it).
            kind != CommunityKind.MOD || CommunityModInstaller.isInstalled(context, containerId, a.id)
        }
        return Snapshot(
            game = g,
            entries = list,
            active = ModHub.active(context, containerId, list),
            on = ModHub.isOn(context, containerId, list),
            selected = ModHub.selected(context, containerId, list, device.id),
            applied = applied,
            checkedAt = CommunityPackages.checkedAt(context, containerId),
            status = try {
                DuoModInstaller.status(context, containerId)
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "Mod hub: no status for %s", containerId)
                null
            },
            saves = try {
                SaveBackup.list(context, containerId)
            } catch (t: Throwable) {
                emptyList()
            },
        )
    }

    /** Re-reads the status and save backups (back from a game session). */
    suspend fun reloadStatus() {
        if (busy != null) return
        val (s, b) = withContext(Dispatchers.IO) {
            val report = try {
                DuoModInstaller.status(context, containerId)
            } catch (t: Throwable) {
                null
            }
            report to runCatching { SaveBackup.list(context, containerId) }.getOrDefault(emptyList())
        }
        status = s
        saves = b
    }

    /** One tap fix for [problem] from the status dialog. */
    fun fix(problem: ModProblem) {
        if (busy != null || gameRunning()) return
        run(FIX, {
            when (problem) {
                ModProblem.GAMESCOPE -> DuoModInstaller.useX11(context, containerId)
                ModProblem.WRONG_SIZE -> DuoModInstaller.useSplitSize(context, containerId)
                ModProblem.FILES_MISSING, ModProblem.MOD_NOT_LOADED -> resetFilesNow()
            }
        }) { context.getString(R.string.duo_status_fixed) }
    }

    /** Puts every file the mod installed back as shipped (the user's changed copies are kept as `.duo-user`). */
    fun resetFiles() {
        if (busy != null || gameRunning()) return
        run(FIX, { resetFilesNow() }) { context.getString(R.string.duo_status_reset_done) }
    }

    private fun resetFilesNow() {
        val pkg = (active as? HubEntry.Community)?.pkg
        DuoModInstaller.resetFiles(
            context,
            containerId,
            download = pkg?.let { p ->
                { sha ->
                    val f = p.files.firstOrNull { it.sha256 == sha }
                        ?: throw java.io.IOException(context.getString(R.string.duo_status_reset_no_source))
                    CommunityPackages.download(context, f)
                }
            },
        )
    }

    /** Every file the mod installed and whether it is still ours (hashes them all). */
    suspend fun fileReport(): List<DuoModInstaller.ModFile> = withContext(Dispatchers.IO) {
        try {
            DuoModInstaller.fileReport(context, containerId)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Mod hub: no file report for %s", containerId)
            emptyList()
        }
    }

    /** Puts [backup] back; the current saves are backed up first, so this is undone by restoring that one. */
    fun restoreSave(backup: SaveBackup.Backup) {
        if (busy != null || gameRunning()) return
        run(FIX, {
            val container = app.gamenative.utils.ContainerUtils.getContainer(context, containerId)
            SaveBackup.restore(
                context,
                containerId,
                File(installLocation),
                File(container.rootDir, ".wine/drive_c"),
                backup,
                context.getString(R.string.duo_saves_undo_reason),
            )
        }) { context.getString(R.string.duo_saves_restored) }
    }

    /** Files and saves are not touched while the game runs. */
    private fun gameRunning(): Boolean {
        val running = DualScreenHost.current?.container?.id == containerId
        if (running) SnackbarManager.show(context.getString(R.string.duo_status_game_running))
        return running
    }

    private fun publish(s: Snapshot) {
        game = s.game
        entries = s.entries
        active = s.active
        on = s.on
        selected = s.selected
        applied = s.applied
        checkedAt = s.checkedAt
        status = s.status
        saves = s.saves
    }

    companion object {
        const val SWITCH = "\u0000switch"
        const val FIX = "\u0000fix"
        private val MAX_AGE_MS = TimeUnit.HOURS.toMillis(6)

        /** A device profile id from compatibility reports, as a name when the app knows the device. */
        fun deviceName(id: String) = if (id == DeviceProfile.AYN_THOR.id) DeviceProfile.AYN_THOR.displayName else id
    }
}
