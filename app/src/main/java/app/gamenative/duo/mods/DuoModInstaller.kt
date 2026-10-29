package app.gamenative.duo.mods

import android.content.Context
import app.gamenative.R
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
    private const val USER_SUFFIX = ".duo-user"
    private const val SHARED_ASSETS = "duo/mods/shared"

    fun isEnabled(context: Context, appId: String): Boolean = stateFile(context, appId).isFile

    /** The id of the mod enabled for [appId] (a bundled mod's id or a community package id), or null. */
    fun enabledModId(context: Context, appId: String): String? = try {
        stateFile(context, appId).takeIf { it.isFile }?.let { JSONObject(it.readText()).optString("mod").ifEmpty { null } }
    } catch (t: Throwable) {
        null
    }

    /** The screen size the installed mod set for [appId] (empty when none is installed or none was set). */
    fun recordedScreenSize(context: Context, appId: String): String = try {
        stateFile(context, appId).takeIf { it.isFile }?.let { JSONObject(it.readText()).optString("screenSize") }.orEmpty()
    } catch (t: Throwable) {
        ""
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
            if (state.optBoolean("unityArgs")) setUnityArgs(container, state)
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
        // The mod is code that runs inside the game: its saves are copied first (restored from the mod explorer).
        val saves = SaveBackup.backup(context, mod.appId, gameDir, driveC, context.getString(R.string.duo_saves_reason_install, mod.title))
        val state = JSONObject()
            .put("gameDir", gameDir.absolutePath)
            .put("driveC", driveC.absolutePath)
            .put("splitLayout", mod.splitLayout.id)
        saves?.let { state.put("saveBackup", it.file.name) }
        origin?.let { state.put("origin", it) }
        mod.launchExecutable?.let { state.put("launchExecutable", it) }

        val files = JSONArray()
        // Recorded as files and settings change, so a failure anywhere is undone at once from what was recorded.
        try {
            enableSteps(context, mod, gameDir, container, driveC, state, files, copyFiles)
        } catch (t: Throwable) {
            state.put("files", files).put("mod", mod.id)
            stateFile(context, mod.appId).apply { parentFile?.mkdirs() }.writeText(state.toString(2))
            if (!disable(context, mod.appId)) Timber.tag(DualScreenHost.TAG).w("Could not undo the partial install of %s", mod.id)
            throw t
        }
        Timber.tag(DualScreenHost.TAG).i(
            "Enabled dual-screen mod %s for %s (%d files, screen %s)",
            mod.id, container.id, files.length(), container.screenSize,
        )
        true
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).e(t, "Failed to enable dual-screen mod %s", mod.id)
        false
    }

    private fun enableSteps(
        context: Context,
        mod: DuoGameMod,
        gameDir: File,
        container: Container,
        driveC: File,
        state: JSONObject,
        files: JSONArray,
        copyFiles: (driveC: File, files: JSONArray) -> Unit,
    ) {
        copyFiles(driveC, files)
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

        // Unity remembers its last resolution: start it at the split window instead of asking the user to pick it once.
        if (mod.unity) setUnityArgs(container, state)
        state.put("gameFingerprint", gameFingerprint(gameDir, files))

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
            // Installed before Unity games got their launch size: add it now (undone like a new install's).
            val unity = DuoModCatalog.mods.firstOrNull { it.id == state.optString("mod") }?.unity
                ?: (state.optJSONObject("origin")?.optJSONObject("sharedAssets")?.has("bepinex") == true)
            if (unity && !state.has("unityArgs")) {
                val container = ContainerUtils.getContainer(context, appId)
                if (state.optString("screenSize").let { it.isEmpty() || it == container.screenSize }) {
                    setUnityArgs(container, state)
                    container.saveData()
                }
            }
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

    /**
     * Brings one APK asset (or folder) up to date at [target]. A file of ours the user changed is left alone, unless
     * [force] (Reset mod files): then the user's copy is kept as `<name>.duo-user` and ours written. A file that already
     * holds the APK's current bytes (pushed by hand, or updated elsewhere) is adopted: the record takes its hash.
     */
    private fun refreshAssetTree(
        context: Context,
        asset: String,
        target: File,
        ours: Map<String, JSONObject>,
        records: JSONArray,
        force: Boolean = false,
    ): Int {
        val children = context.assets.list(asset).orEmpty()
        if (children.isNotEmpty()) return children.sumOf { refreshAssetTree(context, "$asset/$it", File(target, it), ours, records, force) }
        val entry = ours[target.absolutePath]
        if (entry == null) {
            val before = records.length()
            context.assets.open(asset).use { install(it, target, records) }
            return records.length() - before
        }
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".duo-tmp")
        context.assets.open(asset).use { input -> temp.outputStream().use { input.copyTo(it) } }
        val hash = sha256(temp)
        val current = if (target.isFile) sha256(target) else null
        if (current == hash) {
            temp.delete()
            entry.put("sha256", hash)
            return 0
        }
        // Ours: replace it only if it is still what we installed (or gone), never a file the user changed.
        if (current != null && current != entry.getString("sha256")) {
            if (!force) {
                temp.delete()
                return 0
            }
            File(target.path + USER_SUFFIX).let { kept ->
                kept.delete()
                if (!target.renameTo(kept)) {
                    temp.delete()
                    error("Could not keep the changed $target")
                }
            }
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
        if (state.optBoolean("unityArgs")) {
            container.execArgs = if (container.execArgs == state.optString("execArgs")) {
                state.optString("previousExecArgs")
            } else {
                // Changed since: take out only our size pair.
                UnityScreenArgs.remove(container.execArgs)
            }
        }
        // Back to Gamescope unless the user picked another display server since.
        if (state.has("previousDisplayServer") && container.displayServer == Container.DISPLAY_SERVER_X11) {
            container.displayServer = state.optString("previousDisplayServer", Container.DISPLAY_SERVER_X11)
        }
        container.saveData()

        // A record of an install that failed before the settings step has none: the user's settings were not touched.
        if (state.has("previousScreenSplit")) {
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
        }

        stateFile.delete()
        Timber.tag(DualScreenHost.TAG).i("Disabled dual-screen mod %s for %s", modId, container.id)
        true
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).e(t, "Failed to disable the dual-screen mod for %s", appId)
        false
    }

    // ---- status and repairs (the Dual screen control's status dialog) ----------------------------------------------

    /** Quick status for the Dual screen control: settings, missing files, the last session. No hashing. */
    fun status(context: Context, appId: String): ModStatusReport {
        val last = SessionStatusStore.load(context, appId)
        val file = stateFile(context, appId)
        if (!file.isFile) return ModStatusReport(false, emptyList(), last)
        val state = JSONObject(file.readText())
        val container = ContainerUtils.getContainer(context, appId)
        val files = state.optJSONArray("files") ?: JSONArray()
        val missing = (0 until files.length()).count { !File(files.getJSONObject(it).getString("path")).isFile }
        val fingerprint = state.optString("gameFingerprint")
        val gameDir = File(state.optString("gameDir"))
        return ModStatusReport.evaluate(
            on = true,
            gamescope = !Container.isX11Display(container.containerVariant, container.displayServer),
            expectedScreen = state.optString("screenSize"),
            screen = container.screenSize.orEmpty(),
            missingFiles = missing,
            modId = state.optString("mod").ifEmpty { null },
            last = last,
            gameUpdated = fingerprint.isNotEmpty() && gameDir.isDirectory && fingerprint != gameFingerprint(gameDir, files),
        )
    }

    enum class FileState { OURS, CHANGED, MISSING }

    /** One file the mod installed; [path] is relative to the game folder or `drive_c`. */
    data class ModFile(val path: String, val state: FileState)

    /** Every file the mod installed and whether it is still ours (hashes every file: background thread). */
    fun fileReport(context: Context, appId: String): List<ModFile> {
        val file = stateFile(context, appId)
        if (!file.isFile) return emptyList()
        val state = JSONObject(file.readText())
        val roots = listOf(state.optString("gameDir"), state.optString("driveC")).filter { it.isNotEmpty() }
        val files = state.optJSONArray("files") ?: JSONArray()
        return (0 until files.length()).map { i ->
            val entry = files.getJSONObject(i)
            val f = File(entry.getString("path"))
            val s = when {
                !f.isFile -> FileState.MISSING
                sha256(f) == entry.getString("sha256") -> FileState.OURS
                else -> FileState.CHANGED
            }
            val root = roots.firstOrNull { f.path.startsWith(it + File.separator) }
            ModFile(root?.let { f.path.removePrefix(it + File.separator) } ?: f.path, s)
        }
    }

    /**
     * Reset mod files: every file the mod installed is put back as the app (or the package) ships it, including missing
     * ones; a file the user changed is kept next to it as `<name>.duo-user`. Settings are untouched. [download] fetches
     * a community package's own file by its sha256 (null for bundled mods). Returns how many files were written.
     */
    fun resetFiles(context: Context, appId: String, download: ((String) -> ByteArray)?): Int {
        val file = stateFile(context, appId)
        val state = JSONObject(file.readText())
        val gameDir = File(state.getString("gameDir"))
        val driveC = File(state.getString("driveC"))
        val files = state.optJSONArray("files") ?: JSONArray()
        val ours = (0 until files.length()).map { files.getJSONObject(it) }.associateBy { it.getString("path") }
        val bundled = DuoModCatalog.mods.firstOrNull { it.id == state.optString("mod") }
        val sources = mutableListOf<Pair<String, File>>()
        bundled?.let { mod ->
            mod.gameFiles.forEach { (asset, rel) -> sources += asset to File(gameDir, rel) }
            mod.prefixFiles.forEach { (asset, rel) -> sources += asset to File(driveC, rel) }
        }
        state.optJSONObject("origin")?.optJSONObject("sharedAssets")?.let { shared ->
            shared.keys().forEach { asset -> sources += "$SHARED_ASSETS/$asset" to inside(gameDir, shared.getString(asset)) }
        }
        var written = 0
        val fromApk = HashSet<String>()
        sources.forEach { (asset, target) ->
            assetLeaves(context, asset, target).forEach { fromApk += it.absolutePath }
            written += refreshAssetTree(context, asset, target, ours, files, force = true)
        }
        // The rest: copies of game files (SMAPI's deps.json) and a community package's own files.
        val copies = bundled?.gameFileCopies.orEmpty().entries.associate { (src, dst) ->
            File(gameDir, dst).absolutePath to File(gameDir, src)
        }
        ours.values.filter { it.getString("path") !in fromApk }.forEach { entry ->
            val target = File(entry.getString("path"))
            val expected = entry.getString("sha256")
            val copyOf = copies[target.absolutePath]
            if (copyOf == null && target.isFile && sha256(target) == expected) return@forEach
            val bytes = when {
                copyOf != null -> copyOf.readBytes()
                download != null -> download(expected)
                else -> error("No source for ${target.name}")
            }
            if (target.isFile && sha256(target) == sha256(bytes)) {
                entry.put("sha256", sha256(bytes))
                return@forEach
            }
            if (target.isFile && sha256(target) != expected) {
                File(target.path + USER_SUFFIX).also { it.delete() }.let { target.renameTo(it) }
            }
            target.parentFile?.mkdirs()
            val temp = File(target.path + ".duo-tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                temp.delete()
                error("Could not write $target")
            }
            entry.put("sha256", sha256(bytes))
            written++
        }
        state.put("files", files).put("gameFingerprint", gameFingerprint(gameDir, files))
        file.writeText(state.toString(2))
        SessionStatusStore.clear(context, appId)
        Timber.tag(DualScreenHost.TAG).i("Mod files for %s reset: %d written", appId, written)
        return written
    }

    /** Fix for [ModProblem.GAMESCOPE]: X11, restored to Gamescope when Dual screen turns off. */
    fun useX11(context: Context, appId: String) {
        val file = stateFile(context, appId)
        val state = JSONObject(file.readText())
        val container = ContainerUtils.getContainer(context, appId)
        if (Container.isX11Display(container.containerVariant, container.displayServer)) return
        if (!state.has("previousDisplayServer")) state.put("previousDisplayServer", container.displayServer)
        container.displayServer = Container.DISPLAY_SERVER_X11
        container.saveData()
        file.writeText(state.toString(2))
        SessionStatusStore.clear(context, appId)
    }

    /** Fix for [ModProblem.WRONG_SIZE]: the screen size the split needs (and Unity's launch size with it). */
    fun useSplitSize(context: Context, appId: String) {
        val file = stateFile(context, appId)
        val state = JSONObject(file.readText())
        val size = state.optString("screenSize").ifEmpty { return }
        val container = ContainerUtils.getContainer(context, appId)
        container.screenSize = size
        if (state.optBoolean("unityArgs")) setUnityArgs(container, state)
        container.saveData()
        file.writeText(state.toString(2))
        SessionStatusStore.clear(context, appId)
    }

    /** Sets Unity's launch size to the container's screen size; the first call records the arguments to restore. */
    private fun setUnityArgs(container: Container, state: JSONObject) {
        val (w, h) = UnityScreenArgs.size(container.screenSize) ?: return
        if (!state.has("previousExecArgs")) state.put("previousExecArgs", container.execArgs.orEmpty())
        container.execArgs = UnityScreenArgs.add(container.execArgs.orEmpty(), w, h)
        state.put("unityArgs", true).put("execArgs", container.execArgs)
    }

    /**
     * Names and sizes of the game's own top-level files (and a Unity game's Assembly-CSharp.dll): changes when the
     * game is updated. Files the mod installed and our backups are left out.
     */
    private fun gameFingerprint(gameDir: File, records: JSONArray): String {
        val ours = (0 until records.length()).map { records.getJSONObject(it).getString("path") }.toSet()
        val files = gameDir.listFiles().orEmpty().filter {
            it.isFile && it.absolutePath !in ours && !it.name.endsWith(BACKUP_SUFFIX) && !it.name.endsWith(USER_SUFFIX)
        }
        val managed = gameDir.listFiles { f -> f.isDirectory && f.name.endsWith("_Data") }.orEmpty()
            .map { File(it, "Managed/Assembly-CSharp.dll") }.filter { it.isFile }
        val text = (files + managed).map { "${it.relativeTo(gameDir).path}:${it.length()}" }.sorted().joinToString("\n")
        return sha256(text.toByteArray()).take(16)
    }

    private fun assetLeaves(context: Context, asset: String, target: File): List<File> {
        val children = context.assets.list(asset).orEmpty()
        return if (children.isEmpty()) listOf(target) else children.flatMap { assetLeaves(context, "$asset/$it", File(target, it)) }
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun stateFile(context: Context, appId: String) =
        File(context.filesDir, "duo/mods/" + appId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")
}
