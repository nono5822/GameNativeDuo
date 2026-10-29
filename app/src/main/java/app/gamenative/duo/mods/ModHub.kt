package app.gamenative.duo.mods

import android.content.Context
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.community.CommunityGame
import app.gamenative.duo.community.CommunityIndex
import app.gamenative.duo.community.CommunityKind
import app.gamenative.duo.community.CommunityModInstaller
import app.gamenative.duo.community.CommunityPackage
import app.gamenative.duo.community.CommunityPackages
import app.gamenative.duo.community.CommunityRating
import app.gamenative.duo.community.CompatReports
import app.gamenative.duo.community.CompatVerdict
import app.gamenative.duo.settings.DuoSettingsStore
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * One thing the game's mod explorer lists: a mod bundled in the app or a community package. "Switchable" entries are
 * what the Dual screen switch turns on and off (one at a time per game); the others (layouts, device profiles) are
 * add-ons applied on top.
 */
sealed class HubEntry {
    abstract val id: String
    abstract val title: String

    /** Bundled in the APK, or a community package pinned as ours in [CommunityIndex.OFFICIAL_PACKAGES]. */
    abstract val official: Boolean
    abstract val isCode: Boolean
    abstract val rating: CommunityRating?
    abstract val updated: String

    data class Bundled(val mod: DuoGameMod) : HubEntry() {
        override val id get() = mod.id
        override val title get() = mod.title
        override val official get() = true
        override val isCode get() = true
        override val rating: CommunityRating? get() = null
        override val updated get() = ""
    }

    data class Community(val pkg: CommunityPackage) : HubEntry() {
        override val id get() = pkg.id
        override val title get() = pkg.title
        override val official get() = pkg.official
        override val isCode get() = pkg.kind == CommunityKind.MOD
        override val rating get() = pkg.rating
        override val updated get() = pkg.updated
    }

    /** Device profile id -> compatibility reports, plus "tested by the author" for community packages. */
    fun compat(deviceId: String): CompatReports? = rating?.compat?.get(deviceId)

    fun testedOn(deviceId: String): Boolean = when (this) {
        is Bundled -> deviceId == "ayn-thor"
        is Community -> deviceId in pkg.testedOn
    }
}

enum class HubSort { RATING, RECENT, COMPAT }

/**
 * The game's dual-screen mods in one place: which one the Dual screen switch applies, switching between them through
 * [DuoModInstaller]'s undo record, and the order the explorer lists them in. Blocking calls: background thread.
 */
object ModHub {
    /** Every entry for the game: the bundled mod (if any) first, then the community packages. */
    fun entries(bundled: DuoGameMod?, game: CommunityGame?): List<HubEntry> =
        listOfNotNull(bundled?.let { HubEntry.Bundled(it) }) + game?.packages.orEmpty().map { HubEntry.Community(it) }

    /**
     * What the switch can turn on: code mods, and settings packages when the game has no code mod (e.g. a region view
     * for a game nobody modded). With a code mod, settings packages are add-ons like layouts.
     */
    fun switchable(entries: List<HubEntry>): List<HubEntry> {
        val code = entries.filter { it.isCode }
        if (code.isNotEmpty()) return code
        return entries.filter { it is HubEntry.Community && it.pkg.kind == CommunityKind.DUO_SETTINGS }
    }

    fun isSwitchable(entry: HubEntry, entries: List<HubEntry>) = switchable(entries).any { it.id == entry.id }

    /**
     * The explorer's order. The bundled mod stays first (tested with the app, works offline); the rest by [sort]:
     * RATING by the Wilson lower bound (so 1 👍 does not beat 40 👍 and 2 👎), RECENT by last update, COMPAT by what
     * reports from this device say (works > tested by the author > no reports > issues > broken). Ties fall back to
     * rating, then recency, then title.
     */
    fun sort(entries: List<HubEntry>, sort: HubSort, deviceId: String): List<HubEntry> {
        val byRating = compareByDescending<HubEntry> { it.rating?.score ?: 0.0 }.thenByDescending { it.rating?.votes ?: 0 }
        val byRecent = compareByDescending<HubEntry> { it.updated }
        val byCompat = compareBy<HubEntry> { compatRank(it, deviceId) }.thenByDescending { it.compat(deviceId)?.works ?: 0 }
        val main = when (sort) {
            HubSort.RATING -> byRating.then(byRecent)
            HubSort.RECENT -> byRecent.then(byRating)
            HubSort.COMPAT -> byCompat.then(byRating).then(byRecent)
        }
        return entries.sortedWith(compareBy<HubEntry> { if (it is HubEntry.Bundled) 0 else 1 }.then(main).thenBy { it.title.lowercase() })
    }

    private fun compatRank(entry: HubEntry, deviceId: String): Int = when (entry.compat(deviceId)?.verdict) {
        CompatVerdict.WORKS -> 0
        CompatVerdict.ISSUES -> 3
        CompatVerdict.BROKEN -> 4
        else -> if (entry.testedOn(deviceId)) 1 else 2
    }

    /**
     * The compatibility report form for [pkg], pre-filled (GitHub issue forms take field values as query parameters).
     * Falls back to the package's discussion when the index has no form yet. Null without either.
     */
    fun reportUrl(pkg: CommunityPackage, deviceId: String, result: CompatVerdict, gameKey: String): String? {
        val rating = pkg.rating ?: return null
        val form = rating.reportUrl ?: return rating.url
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val sep = if ('?' in form) "&" else "?"
        return form + sep + listOf(
            "package" to pkg.id,
            "version" to pkg.version,
            "game" to gameKey,
            "device" to deviceId,
            "result" to result.name.lowercase(),
            "title" to "${result.name.lowercase()}: ${pkg.id} ${pkg.version} on $deviceId",
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    /** The package's folder in the community repo (its source, readme and history). */
    fun pageUrl(pkg: CommunityPackage, gameKey: String) = "${CommunityIndex.REPO_URL}/tree/main/games/$gameKey/${pkg.id}"

    // --- State -----------------------------------------------------------------------------------------------------

    /** The switchable entry that is on now, or null when Dual screen is off. */
    fun active(context: Context, containerId: String, entries: List<HubEntry>): HubEntry? {
        DuoModInstaller.enabledModId(context, containerId)?.let { id ->
            return entries.firstOrNull { it.id == id }
                ?: DuoModCatalog.forApp(containerId)?.takeIf { it.id == id }?.let { HubEntry.Bundled(it) }
        }
        if (DuoModInstaller.isEnabled(context, containerId)) return null
        val settings = CommunityPackages.applied(context, containerId)[CommunityKind.DUO_SETTINGS] ?: return null
        if (!DuoSettingsStore.load(context, containerId).screenSplit) return null
        return switchable(entries).firstOrNull { it.id == settings.id }
    }

    /** True when some dual-screen mod is on, even one this list does not know (e.g. removed from the index). */
    fun isOn(context: Context, containerId: String, entries: List<HubEntry>): Boolean =
        DuoModInstaller.isEnabled(context, containerId) || active(context, containerId, entries) != null

    /** The entry the switch applies: the one that is on, else the user's choice, else the bundled or best-rated one. */
    fun selected(context: Context, containerId: String, entries: List<HubEntry>, deviceId: String): HubEntry? {
        val candidates = switchable(entries)
        active(context, containerId, entries)?.let { return it }
        val chosen = readSelection(context, containerId).optString("id")
        return candidates.firstOrNull { it.id == chosen } ?: sort(candidates, HubSort.COMPAT, deviceId).firstOrNull()
    }

    fun select(context: Context, containerId: String, entry: HubEntry) {
        val o = readSelection(context, containerId)
        writeSelection(context, containerId, o.put("id", entry.id))
    }

    /** True when the user agreed to install this version of a code mod (the consent dialog); bundled mods need none. */
    fun hasConsent(context: Context, containerId: String, entry: HubEntry): Boolean {
        if (entry !is HubEntry.Community || entry.pkg.kind != CommunityKind.MOD) return true
        val list = readSelection(context, containerId).optJSONArray("consent") ?: return false
        return (0 until list.length()).any { list.optString(it) == consentKey(entry.pkg) }
    }

    fun recordConsent(context: Context, containerId: String, pkg: CommunityPackage) {
        val o = readSelection(context, containerId)
        val list = o.optJSONArray("consent") ?: JSONArray()
        if ((0 until list.length()).none { list.optString(it) == consentKey(pkg) }) list.put(consentKey(pkg))
        writeSelection(context, containerId, o.put("consent", list))
    }

    private fun consentKey(pkg: CommunityPackage) = "${pkg.id}@${pkg.version}"

    // --- Switching -------------------------------------------------------------------------------------------------

    /** Turns [entry] on. Throws [IOException] with a user-facing reason. */
    fun turnOn(context: Context, containerId: String, gameDir: File, entry: HubEntry) {
        when (entry) {
            is HubEntry.Bundled -> if (!DuoModInstaller.enable(context, entry.mod, gameDir)) {
                throw IOException("installing failed (see logs)")
            }
            is HubEntry.Community -> when (entry.pkg.kind) {
                CommunityKind.MOD -> CommunityModInstaller.install(context, containerId, gameDir, entry.pkg)
                CommunityKind.DUO_SETTINGS -> CommunityPackages.apply(context, containerId, entry.pkg)
                else -> throw IOException("${entry.pkg.kind.id} packages are not dual-screen mods")
            }
        }
        select(context, containerId, entry)
    }

    /** Turns off whatever dual-screen mod is on, restoring what it changed. Throws [IOException] on failure. */
    fun turnOff(context: Context, containerId: String, entries: List<HubEntry>) {
        // Read before anything changes: a settings package counts only while it is what the switch turned on.
        val settingsOn = (active(context, containerId, entries) as? HubEntry.Community)?.pkg?.kind == CommunityKind.DUO_SETTINGS
        if (DuoModInstaller.isEnabled(context, containerId)) {
            if (!DuoModInstaller.disable(context, containerId)) throw IOException("removing failed (see logs)")
            CommunityPackages.forget(context, containerId, CommunityKind.MOD)
        }
        // With a code mod, settings packages are add-ons (see [switchable]) and stay.
        if (settingsOn) CommunityPackages.removeSettings(context, containerId)
    }

    /**
     * Replaces the mod that is on with [next]: the previous one is undone first (its record restores files, screen
     * size and settings), then [next] installed. If [next] fails, the previous one is put back so the game is never
     * left without the mod it had. Throws [IOException] with what happened.
     */
    fun switchTo(context: Context, containerId: String, gameDir: File, next: HubEntry, entries: List<HubEntry>) {
        val previous = active(context, containerId, entries)
        if (previous?.id == next.id) return
        turnOff(context, containerId, entries)
        try {
            turnOn(context, containerId, gameDir, next)
            Timber.tag(DualScreenHost.TAG).i("Mod hub: %s -> %s for %s", previous?.id, next.id, containerId)
        } catch (e: IOException) {
            Timber.tag(DualScreenHost.TAG).w(e, "Mod hub: %s failed for %s, restoring %s", next.id, containerId, previous?.id)
            val restored = previous != null &&
                try {
                    turnOn(context, containerId, gameDir, previous)
                    true
                } catch (t: Throwable) {
                    Timber.tag(DualScreenHost.TAG).e(t, "Mod hub: could not restore %s", previous.id)
                    false
                }
            throw IOException(e.message + if (restored) "; ${previous?.title} is back on" else "", e)
        }
    }

    private fun readSelection(context: Context, containerId: String): JSONObject = try {
        selectionFile(context, containerId).takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: JSONObject()
    } catch (t: Throwable) {
        JSONObject()
    }

    private fun writeSelection(context: Context, containerId: String, o: JSONObject) {
        try {
            val file = selectionFile(context, containerId)
            file.parentFile?.mkdirs()
            file.writeText(o.toString(2))
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Mod hub: could not save the selection for %s", containerId)
        }
    }

    private fun selectionFile(context: Context, containerId: String) =
        File(context.filesDir, "duo/mods/selected/" + containerId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")
}
