package app.gamenative.duo.mods

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.gamenative.R
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.community.CommunityCatalog
import app.gamenative.duo.community.CommunityGame
import app.gamenative.duo.community.CommunityIndex
import app.gamenative.duo.community.CommunityKind
import app.gamenative.duo.community.CommunityModInstaller
import app.gamenative.duo.community.CommunityPackage
import app.gamenative.duo.community.CommunityPackages
import app.gamenative.duo.display.DeviceProfile
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

    /** The game has something to show: a bundled mod, or community packages (cached list or catalog). */
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

    class Consent(val pkg: CommunityPackage, val plan: CommunityModInstaller.Plan, val replacesMod: Boolean, val then: () -> Unit)

    private class Snapshot(
        val game: CommunityGame?,
        val entries: List<HubEntry>,
        val active: HubEntry?,
        val on: Boolean,
        val selected: HubEntry?,
        val applied: Map<CommunityKind, CommunityPackages.Applied>,
        val checkedAt: Long?,
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
            val cached = CommunityPackages.cached(context, containerId)
            read(cached) to (CommunityCatalog.known(context)?.contains(gameKey) == true)
        }
        publish(snapshot)
        visible = bundled != null || inCatalog || snapshot.entries.isNotEmpty()
        loaded = true
        withContext(Dispatchers.IO) { CommunityCatalog.refreshOnce(context) }
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
                        Consent(pkg, CommunityModInstaller.plan(pkg), DuoModInstaller.isEnabled(context, containerId)) {
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
            reload()
            busy = null
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
        )
    }

    private fun publish(s: Snapshot) {
        game = s.game
        entries = s.entries
        active = s.active
        on = s.on
        selected = s.selected
        applied = s.applied
        checkedAt = s.checkedAt
    }

    companion object {
        const val SWITCH = "\u0000switch"
        private val MAX_AGE_MS = TimeUnit.HOURS.toMillis(6)

        /** A device profile id from compatibility reports, as a name when the app knows the device. */
        fun deviceName(id: String) = if (id == DeviceProfile.AYN_THOR.id) DeviceProfile.AYN_THOR.displayName else id
    }
}
