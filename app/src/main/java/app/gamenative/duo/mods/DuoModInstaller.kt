package app.gamenative.duo.mods

import android.content.Context
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.display.DeviceProfile
import app.gamenative.duo.display.ScreenCalibrationStore
import app.gamenative.duo.settings.DuoSettingsStore
import app.gamenative.duo.split.LayoutFile
import app.gamenative.duo.split.ScreenSplit
import app.gamenative.duo.split.SplitLayout
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Turns a game's dual-screen mod on and off. Turning it on records everything it changes in
 * `files/duo/mods/<containerId>.json`, so turning it off puts the container back exactly as it was:
 * - a file that already existed with the same content is left alone (and never removed);
 * - a file that existed with different content is kept as `<name>.duo-backup` and restored;
 * - our files are only removed if still unchanged, and folders only if left empty.
 * Call from a background thread: it copies files.
 */
object DuoModInstaller {
    private const val ENV_DLL_OVERRIDES = "WINEDLLOVERRIDES"
    private const val BACKUP_SUFFIX = ".duo-backup"
    private const val SHARED_ASSETS = "duo/mods/shared"

    fun isEnabled(context: Context, appId: String): Boolean = stateFile(context, appId).isFile

    /** The id of the mod enabled for [appId] (a bundled mod's id or a community package id), or null. */
    fun enabledModId(context: Context, appId: String): String? = try {
        stateFile(context, appId).takeIf { it.isFile }?.let { JSONObject(it.readText()).optString("mod").ifEmpty { null } }
    } catch (t: Throwable) {
        null
    }

    /** Applies [mod] to its container; [gameDir] is the game's install folder. Returns false (and logs) on failure. */
    fun enable(context: Context, mod: DuoGameMod, gameDir: File): Boolean = enableWith(context, mod, gameDir, null) { driveC, files ->
        mod.gameFiles.forEach { (asset, rel) -> copyAssetTree(context, asset, File(gameDir, rel), files) }
        mod.prefixFiles.forEach { (asset, rel) -> copyAssetTree(context, asset, File(driveC, rel), files) }
    }

    /**
     * Applies a downloaded mod (a community package): [gameFiles] and [prefixFiles] map paths relative to the game
     * folder and to the prefix's drive_c to their content; [sharedAssets] maps APK asset folders shared by bundled
     * mods (e.g. BepInEx) to game-folder paths. Same rules and undo record as [enable]; [origin] is kept in the record.
     */
    fun enableDownloaded(
        context: Context,
        mod: DuoGameMod,
        gameDir: File,
        gameFiles: Map<String, ByteArray>,
        prefixFiles: Map<String, ByteArray>,
        sharedAssets: Map<String, String>,
        origin: JSONObject,
    ): Boolean = enableWith(context, mod, gameDir, origin.put("sharedAssets", JSONObject(sharedAssets))) { driveC, files ->
        sharedAssets.forEach { (asset, rel) -> copyAssetTree(context, "$SHARED_ASSETS/$asset", inside(gameDir, rel), files) }
        gameFiles.forEach { (rel, bytes) -> install(bytes.inputStream(), inside(gameDir, rel), files) }
        prefixFiles.forEach { (rel, bytes) -> install(bytes.inputStream(), inside(driveC, rel), files) }
    }

    /**
     * The split resolution changed: resizes the container's screen if a dual-screen mod set it, keeping the undo
     * record in step. Call from a background thread.
     */
    fun applySplitScale(context: Context, appId: String, scale: Float) {
        try {
            val file = stateFile(context, appId)
            if (!file.isFile) return
            val state = JSONObject(file.readText())
            val layout = SplitLayout.fromId(state.optString("splitLayout").ifEmpty { SplitLayout.NATIVE.id })
            val size = ScreenSplit.recommendedFor(context, layout, scale)?.let { "${it.first}x${it.second}" } ?: return
            val container = ContainerUtils.getContainer(context, appId)
            container.screenSize = size
            container.saveData()
            state.put("screenSize", size)
            file.writeText(state.toString(2))
            if (layout == SplitLayout.NATIVE) {
                ScreenSplit.nativeFor(context, scale)?.let {
                    LayoutFile.write(container, it, ScreenCalibrationStore.load(context, DeviceProfile.detect()))
                }
            }
            Timber.tag(DualScreenHost.TAG).i("Split resolution for %s: %s (%.0f%%)", appId, size, scale * 100)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to apply the split resolution for %s", appId)
        }
    }

    /** [rel] under [root], refusing anything that would land outside it ("..", absolute paths). */
    private fun inside(root: File, rel: String): File {
        val target = File(root, rel).canonicalFile
        val base = root.canonicalFile
        require(target == base || target.path.startsWith(base.path + File.separator)) { "$rel leaves ${base.name}" }
        return target
    }

    private fun enableWith(
        context: Context,
        mod: DuoGameMod,
        gameDir: File,
        origin: JSONObject?,
        copyFiles: (driveC: File, files: JSONArray) -> Unit,
    ): Boolean = try {
        val container = ContainerUtils.getOrCreateContainer(context, mod.appId)
        val driveC = File(container.rootDir, ".wine/drive_c")
        val state = JSONObject()
            .put("gameDir", gameDir.absolutePath)
            .put("driveC", driveC.absolutePath)
            .put("splitLayout", mod.splitLayout.id)
        origin?.let { state.put("origin", it) }
        mod.launchExecutable?.let { state.put("launchExecutable", it) }

        val files = JSONArray()
        // Recorded as files land, so a failure halfway still leaves an undo record (written in the catch below).
        try {
            copyFiles(driveC, files)
        } catch (t: Throwable) {
            state.put("files", files).put("mod", mod.id)
            stateFile(context, mod.appId).apply { parentFile?.mkdirs() }.writeText(state.toString(2))
            throw t
        }
        mod.gameFileCopies.forEach { (src, dst) ->
            File(gameDir, src).inputStream().use { install(it, File(gameDir, dst), files) }
        }
        state.put("files", files)

        state.put("previousScreenSize", container.screenSize)
        val ownScale = DuoSettingsStore.load(context, container.id).splitScale
        val scale = mod.splitScale ?: ownScale
        ScreenSplit.recommendedFor(context, mod.splitLayout, scale)?.let {
            container.screenSize = "${it.first}x${it.second}"
            state.put("screenSize", container.screenSize)
        }

        if (mod.dllOverrides.isNotEmpty()) {
            val envVars = EnvVars(container.envVars)
            state.put("previousDllOverrides", if (envVars.has(ENV_DLL_OVERRIDES)) envVars.get(ENV_DLL_OVERRIDES) else JSONObject.NULL)
            envVars.put(ENV_DLL_OVERRIDES, addOverrides(envVars.get(ENV_DLL_OVERRIDES), mod.dllOverrides))
            container.envVars = envVars.toString()
        }

        mod.launchExecutable?.let {
            state.put("previousExecutablePath", container.executablePath)
            container.executablePath = it
        }

        // Under Gamescope the X server's renderer never presents, so neither screen split nor the bottom screen works.
        if (!Container.isX11Display(container.containerVariant, container.displayServer)) {
            state.put("previousDisplayServer", container.displayServer)
            container.displayServer = Container.DISPLAY_SERVER_X11
        }
        container.saveData()

        val settings = DuoSettingsStore.load(context, container.id)
        state.put("previousScreenSplit", settings.screenSplit)
        state.put("previousSplitLayout", settings.splitLayout)
        state.put("previousSplitScale", ownScale.toDouble())
        DuoSettingsStore.save(
            context,
            container.id,
            settings.copy(screenSplit = true, splitLayout = mod.splitLayout.id, splitScale = scale),
        )
        if (mod.splitLayout == SplitLayout.NATIVE) {
            ScreenSplit.nativeFor(context, scale)?.let {
                LayoutFile.write(container, it, ScreenCalibrationStore.load(context, DeviceProfile.detect()))
            }
        }

        state.put("mod", mod.id).put("apkStamp", apkStamp(context))
        stateFile(context, mod.appId).apply { parentFile?.mkdirs() }.writeText(state.toString(2))
        Timber.tag(DualScreenHost.TAG).i(
            "Enabled dual-screen mod %s for %s (%d files, screen %s)",
            mod.id, container.id, files.length(), container.screenSize,
        )
        true
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).e(t, "Failed to enable dual-screen mod %s", mod.id)
        false
    }

    /**
     * After an app update, brings the files a mod installed from the APK (a bundled mod's files, a community mod's
     * shared assets such as BepInEx and our plugin) up to date, so a game never keeps an old plugin. A file the record
     * says is ours is replaced only while the user has not changed it, without a backup (a backup, if any, is the
     * game's original); new files go through the usual rules. Costs one small file read when the app has not changed.
     * Runs at session start, well before the game loads its mods.
     */
    fun refreshIfUpdated(context: Context, appId: String) {
        try {
            val file = stateFile(context, appId)
            if (!file.isFile) return
            val stamp = apkStamp(context)
            val state = JSONObject(file.readText())
            if (state.optLong("apkStamp") == stamp) return
            val gameDir = File(state.getString("gameDir"))
            val driveC = File(state.getString("driveC"))
            val files = state.optJSONArray("files") ?: JSONArray()
            val ours = (0 until files.length()).map { files.getJSONObject(it) }.associateBy { it.getString("path") }
            val sources = mutableListOf<Pair<String, File>>()
            DuoModCatalog.mods.firstOrNull { it.id == state.optString("mod") }?.let { mod ->
                mod.gameFiles.forEach { (asset, rel) -> sources += asset to File(gameDir, rel) }
                mod.prefixFiles.forEach { (asset, rel) -> sources += asset to File(driveC, rel) }
            }
            state.optJSONObject("origin")?.optJSONObject("sharedAssets")?.let { shared ->
                shared.keys().forEach { asset -> sources += "$SHARED_ASSETS/$asset" to inside(gameDir, shared.getString(asset)) }
            }
            var updated = 0
            sources.forEach { (asset, target) -> updated += refreshAssetTree(context, asset, target, ours, files) }
            state.put("files", files).put("apkStamp", stamp)
            DuoModCatalog.mods.firstOrNull { it.id == state.optString("mod") }?.let { migrateSplitLayout(context, appId, state, it) }
            file.writeText(state.toString(2))
            Timber.tag(DualScreenHost.TAG).i("Mod files for %s checked after an app update: %d updated", appId, updated)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to refresh the mod files for %s", appId)
        }
    }

    /**
     * A bundled mod that moved to another split layout (Balatro: fill -> native) brings the container along, unless
     * the user changed the screen size since. The running session keeps its X screen until the next launch (cut like
     * "fill" meanwhile, see SplitScreenController).
     */
    private fun migrateSplitLayout(context: Context, appId: String, state: JSONObject, mod: DuoGameMod) {
        val recorded = SplitLayout.fromId(state.optString("splitLayout").ifEmpty { mod.splitLayout.id })
        if (recorded == mod.splitLayout) return
        val container = ContainerUtils.getContainer(context, appId)
        val ourSize = state.optString("screenSize")
        if (ourSize.isNotEmpty() && container.screenSize != ourSize) return
        val settings = DuoSettingsStore.load(context, container.id)
        val scale = mod.splitScale ?: settings.splitScale
        val size = ScreenSplit.recommendedFor(context, mod.splitLayout, scale) ?: return
        container.screenSize = "${size.first}x${size.second}"
        container.saveData()
        if (!state.has("previousSplitScale")) state.put("previousSplitScale", settings.splitScale.toDouble())
        DuoSettingsStore.save(context, container.id, settings.copy(splitLayout = mod.splitLayout.id, splitScale = scale))
        state.put("splitLayout", mod.splitLayout.id).put("screenSize", container.screenSize)
        Timber.tag(DualScreenHost.TAG).i(
            "%s now uses the %s split (screen %s, from the next launch)",
            appId,
            mod.splitLayout.id,
            container.screenSize,
        )
    }

    private fun refreshAssetTree(context: Context, asset: String, target: File, ours: Map<String, JSONObject>, records: JSONArray): Int {
        val children = context.assets.list(asset).orEmpty()
        if (children.isNotEmpty()) return children.sumOf { refreshAssetTree(context, "$asset/$it", File(target, it), ours, records) }
        val entry = ours[target.absolutePath]
        if (entry == null) {
            val before = records.length()
            context.assets.open(asset).use { install(it, target, records) }
            return records.length() - before
        }
        // Ours: replace it only if it is still what we installed (or gone), never a file the user changed.
        if (target.isFile && sha256(target) != entry.getString("sha256")) return 0
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".duo-tmp")
        context.assets.open(asset).use { input -> temp.outputStream().use { input.copyTo(it) } }
        val hash = sha256(temp)
        if (hash == entry.getString("sha256") && target.isFile) {
            temp.delete()
            return 0
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            error("Could not write $target")
        }
        entry.put("sha256", hash)
        return 1
    }

    private fun apkStamp(context: Context): Long = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

    /** Undoes [enable]. Returns false (and logs) on failure. */
    fun disable(context: Context, mod: DuoGameMod): Boolean = disable(context, mod.appId)

    /**
     * Undoes [enable] or [enableDownloaded] for [appId], whichever mod it was: everything needed is in the record.
     * Returns false (and logs) on failure.
     */
    fun disable(context: Context, appId: String): Boolean = try {
        val stateFile = stateFile(context, appId)
        val state = JSONObject(stateFile.readText())
        val container = ContainerUtils.getContainer(context, appId)
        val modId = state.optString("mod")
        // Records written before these fields existed: the bundled mod they came from knows.
        val bundled = DuoModCatalog.mods.firstOrNull { it.id == modId }
        val splitLayout = SplitLayout.fromId(state.optString("splitLayout").ifEmpty { bundled?.splitLayout?.id })
        val launchExecutable = state.optString("launchExecutable").ifEmpty { bundled?.launchExecutable }
        val roots = listOfNotNull(state.optString("gameDir"), state.optString("driveC")).filter { it.isNotEmpty() }.map { File(it) }

        val files = state.optJSONArray("files") ?: JSONArray()
        for (i in files.length() - 1 downTo 0) {
            val entry = files.getJSONObject(i)
            val file = File(entry.getString("path"))
            if (file.isFile && sha256(file) == entry.getString("sha256")) file.delete()
            val backup = File(file.path + BACKUP_SUFFIX)
            if (entry.optBoolean("backup") && backup.isFile && !file.exists()) backup.renameTo(file)
            pruneEmptyDirs(file.parentFile, roots)
        }

        // The size we set (recorded; older records: what we would set now).
        val ourSize = state.optString("screenSize").ifEmpty { null }
            ?: ScreenSplit.recommendedFor(context, splitLayout)?.let { "${it.first}x${it.second}" }
        // Restore the screen size unless the user changed it since.
        if (ourSize == null || container.screenSize == ourSize) {
            container.screenSize = state.optString("previousScreenSize", container.screenSize)
        }

        if (state.has("previousDllOverrides")) {
            val envVars = EnvVars(container.envVars)
            val previous = state.opt("previousDllOverrides")
            if (previous == null || previous == JSONObject.NULL) {
                envVars.remove(ENV_DLL_OVERRIDES)
            } else {
                envVars.put(ENV_DLL_OVERRIDES, previous.toString())
            }
            container.envVars = envVars.toString()
        }

        if (state.has("previousExecutablePath") && container.executablePath == launchExecutable) {
            container.executablePath = state.optString("previousExecutablePath", "")
        }
        // Back to Gamescope unless the user picked another display server since.
        if (state.has("previousDisplayServer") && container.displayServer == Container.DISPLAY_SERVER_X11) {
            container.displayServer = state.optString("previousDisplayServer", Container.DISPLAY_SERVER_X11)
        }
        container.saveData()

        val settings = DuoSettingsStore.load(context, container.id)
        DuoSettingsStore.save(
            context,
            container.id,
            settings.copy(
                screenSplit = state.optBoolean("previousScreenSplit", false),
                splitLayout = state.optString("previousSplitLayout", settings.splitLayout),
                splitScale = state.optDouble("previousSplitScale", settings.splitScale.toDouble()).toFloat(),
            ),
        )
        LayoutFile.delete(container)

        stateFile.delete()
        Timber.tag(DualScreenHost.TAG).i("Disabled dual-screen mod %s for %s", modId, container.id)
        true
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).e(t, "Failed to disable the dual-screen mod for %s", appId)
        false
    }

    /** Copies an asset file, or an asset folder with everything in it, to [target]. */
    private fun copyAssetTree(context: Context, asset: String, target: File, records: JSONArray) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            context.assets.open(asset).use { install(it, target, records) }
        } else {
            children.forEach { copyAssetTree(context, "$asset/$it", File(target, it), records) }
        }
    }

    /** Writes [source] to [target] following the rules in the class comment, and records what it did. */
    private fun install(source: InputStream, target: File, records: JSONArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".duo-tmp")
        temp.outputStream().use { source.copyTo(it) }
        val hash = sha256(temp)
        if (target.isFile && sha256(target) == hash) {
            temp.delete()
            return
        }
        val backedUp = target.isFile && target.renameTo(File(target.path + BACKUP_SUFFIX))
        if (!temp.renameTo(target)) {
            temp.delete()
            error("Could not write $target")
        }
        records.put(JSONObject().put("path", target.absolutePath).put("sha256", hash).put("backup", backedUp))
    }

    private fun pruneEmptyDirs(start: File?, roots: List<File>) {
        var dir = start
        while (dir != null && roots.none { it == dir } && roots.any { dir!!.path.startsWith(it.path + File.separator) }) {
            if (dir.list()?.isEmpty() != true || !dir.delete()) return
            dir = dir.parentFile
        }
    }

    /** Adds Wine DLL overrides (`name=mode`, `;`-separated) to an existing value, replacing same-name entries. */
    private fun addOverrides(existing: String?, overrides: List<String>): String {
        val names = overrides.map { it.substringBefore('=') }.toSet()
        val kept = existing.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() && it.substringBefore('=') !in names }
        return (kept + overrides).joinToString(";")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun stateFile(context: Context, appId: String) =
        File(context.filesDir, "duo/mods/" + appId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")
}
