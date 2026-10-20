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
    ): Boolean = enableWith(context, mod, gameDir, origin) { driveC, files ->
        sharedAssets.forEach { (asset, rel) -> copyAssetTree(context, "$SHARED_ASSETS/$asset", inside(gameDir, rel), files) }
        gameFiles.forEach { (rel, bytes) -> install(bytes.inputStream(), inside(gameDir, rel), files) }
        prefixFiles.forEach { (rel, bytes) -> install(bytes.inputStream(), inside(driveC, rel), files) }
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
        ScreenSplit.recommendedFor(context, mod.splitLayout)?.let { container.screenSize = "${it.first}x${it.second}" }

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
        container.saveData()

        val settings = DuoSettingsStore.load(context, container.id)
        state.put("previousScreenSplit", settings.screenSplit)
        state.put("previousSplitLayout", settings.splitLayout)
        DuoSettingsStore.save(context, container.id, settings.copy(screenSplit = true, splitLayout = mod.splitLayout.id))
        if (mod.splitLayout == SplitLayout.NATIVE) {
            ScreenSplit.nativeFor(context)?.let {
                LayoutFile.write(container, it, ScreenCalibrationStore.load(context, DeviceProfile.detect()))
            }
        }

        state.put("mod", mod.id)
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

        val ourSize = ScreenSplit.recommendedFor(context, splitLayout)?.let { "${it.first}x${it.second}" }
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
        container.saveData()

        val settings = DuoSettingsStore.load(context, container.id)
        DuoSettingsStore.save(
            context,
            container.id,
            settings.copy(
                screenSplit = state.optBoolean("previousScreenSplit", false),
                splitLayout = state.optString("previousSplitLayout", settings.splitLayout),
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
