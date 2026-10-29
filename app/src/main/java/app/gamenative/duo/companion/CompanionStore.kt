package app.gamenative.duo.companion

import android.content.Context
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.community.CommunityIndex
import java.io.File
import org.json.JSONObject
import timber.log.Timber

/** A companion and where it came from. */
data class CompanionEntry(val manifest: CompanionManifest, val files: CompanionFiles, val origin: Origin) {
    enum class Origin { BUNDLED, COMMUNITY, DEV }
}

/**
 * Finds companions and keeps their user state (ticks, notes, timers) apart from the packages:
 * - bundled: `assets/duo/companions/<id>/companion.json`;
 * - community: `files/duo/companions/<id>/`;
 * - being written: `Android/data/<package>/files/duo/companions/dev/<id>/` (adb pushes there without run-as; reloaded
 *   live, and preferred by "Automatic" so a modder sees their work at once);
 * - user state: `files/duo/companions/state/<containerId>/<id>.json`.
 */
object CompanionStore {
    const val AUTO = "auto"
    const val OFF = "off"
    const val GENERAL_ID = "general"
    private const val ASSET_DIR = "duo/companions"
    private const val MANIFEST = "companion.json"

    /** Every companion that can run for the game of [containerId] (its own first, then general ones). */
    fun availableFor(context: Context, containerId: String): List<CompanionEntry> {
        val key = CommunityIndex.gameKey(containerId)
        return all(context).filter { it.manifest.forGame(key) }.sortedBy { if (it.manifest.general) 1 else 0 }
    }

    /** The companion [choice] (a setting: [AUTO], [OFF] or an id) picks for [containerId], or null. */
    fun resolve(context: Context, containerId: String, choice: String): CompanionEntry? = try {
        val list = availableFor(context, containerId)
        when (choice) {
            OFF -> null
            AUTO ->
                list.firstOrNull { it.origin == CompanionEntry.Origin.DEV } ?: list.firstOrNull { !it.manifest.general }
                    ?: list.firstOrNull { it.manifest.id == GENERAL_ID } ?: list.firstOrNull()
            else -> list.firstOrNull { it.manifest.id == choice }
        }
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).e(t, "Failed to find a companion for %s", containerId)
        null
    }

    fun all(context: Context): List<CompanionEntry> {
        val out = linkedMapOf<String, CompanionEntry>()
        bundled(context).forEach { out[it.manifest.id] = it }
        folders(File(context.filesDir, "duo/companions"), CompanionEntry.Origin.COMMUNITY).forEach { out.putIfAbsent(it.manifest.id, it) }
        // A dev copy replaces the published one with the same id: that is what the modder is changing.
        devDir(context)?.let { dir -> folders(dir, CompanionEntry.Origin.DEV).forEach { out[it.manifest.id] = it } }
        return out.values.toList()
    }

    fun devDir(context: Context): File? = context.getExternalFilesDir("duo")?.let { File(it, "companions/dev") }

    /** Re-reads one companion's manifest (live reload of a dev package). */
    fun reload(entry: CompanionEntry): CompanionEntry? = load(entry.files, entry.manifest.id, entry.origin)

    private fun bundled(context: Context): List<CompanionEntry> = try {
        context.assets.list(ASSET_DIR).orEmpty().mapNotNull { id ->
            load(CompanionFiles.Assets(context.assets, "$ASSET_DIR/$id"), id, CompanionEntry.Origin.BUNDLED)
        }
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).w(t, "Failed to list bundled companions")
        emptyList()
    }

    private fun folders(root: File, origin: CompanionEntry.Origin): List<CompanionEntry> =
        root.listFiles()?.filter { it.isDirectory && it.name != "state" && File(it, MANIFEST).isFile }?.sortedBy { it.name }
            ?.mapNotNull { load(CompanionFiles.Folder(it, dev = origin == CompanionEntry.Origin.DEV), it.name, origin) }
            .orEmpty()

    private fun load(files: CompanionFiles, fallbackId: String, origin: CompanionEntry.Origin): CompanionEntry? = try {
        files.text(MANIFEST)?.let { CompanionEntry(CompanionManifest.parse(JSONObject(it), fallbackId), files, origin) }
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).w(t, "Ignoring unreadable companion %s", fallbackId)
        // A dev companion with a broken manifest still shows, with the error, so its author sees why.
        if (origin == CompanionEntry.Origin.DEV) {
            CompanionEntry(CompanionManifest(fallbackId, fallbackId, problems = listOf("companion.json: ${t.message}")), files, origin)
        } else {
            null
        }
    }

    // ---- user state ------------------------------------------------------------------------------------------------

    fun loadState(context: Context, containerId: String, companionId: String): Map<String, Any?> = try {
        val f = stateFile(context, containerId, companionId)
        if (f.isFile) (toKotlin(JSONObject(f.readText())) as? Map<String, Any?>).orEmpty() else emptyMap()
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).w(t, "Ignoring unreadable companion state for %s", companionId)
        emptyMap()
    }

    /** When the state was last written (epoch ms), 0 when never. */
    fun stateSavedAt(context: Context, containerId: String, companionId: String): Long =
        stateFile(context, containerId, companionId).lastModified()

    /** Writes the state atomically (temp file + rename): a crash mid-write never loses the user's notes. */
    fun saveState(context: Context, containerId: String, companionId: String, state: Map<String, Any?>) {
        try {
            val f = stateFile(context, containerId, companionId)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject(state).toString())
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to save companion state for %s", companionId)
        }
    }

    /** A file the user picked for a card (image, guide), kept with the state. */
    fun userFile(context: Context, containerId: String, companionId: String, name: String): File =
        File(stateFile(context, containerId, companionId).parentFile, "${safe(companionId)}-${safe(name)}")

    private fun stateFile(context: Context, containerId: String, companionId: String) =
        File(File(context.filesDir, "duo/companions/state/${safe(containerId)}"), "${safe(companionId)}.json")

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
