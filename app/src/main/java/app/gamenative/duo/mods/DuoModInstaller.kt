package app.gamenative.duo.mods

import android.content.Context
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.settings.DuoSettingsStore
import app.gamenative.duo.split.ScreenSplit
import app.gamenative.utils.ContainerUtils
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Turns a game's dual-screen mod on and off. Turning it on records everything it changes in
 * `files/duo/mods/<containerId>.json`, so turning it off puts the container back exactly as it was. Files are
 * only removed when they are still the ones we copied. Call from a background thread: it copies files.
 */
object DuoModInstaller {
    private const val ENV_DLL_OVERRIDES = "WINEDLLOVERRIDES"

    fun isEnabled(context: Context, appId: String): Boolean = stateFile(context, appId).isFile

    /** Applies [mod] to its container; [gameDir] is the game's install folder. Returns false (and logs) on failure. */
    fun enable(context: Context, mod: DuoGameMod, gameDir: File): Boolean = try {
        val container = ContainerUtils.getOrCreateContainer(context, mod.appId)
        val driveC = File(container.rootDir, ".wine/drive_c")
        val state = JSONObject()

        val copied = JSONArray()
        mod.gameFiles.forEach { (asset, rel) -> copied.put(copyAsset(context, asset, File(gameDir, rel))) }
        mod.prefixFiles.forEach { (asset, rel) -> copied.put(copyAsset(context, asset, File(driveC, rel))) }
        state.put("files", copied)

        state.put("previousScreenSize", container.screenSize)
        val size = ScreenSplit.recommendedFor(context)
        if (size != null) container.screenSize = "${size.first}x${size.second}"

        val envVars = EnvVars(container.envVars)
        state.put("previousDllOverrides", if (envVars.has(ENV_DLL_OVERRIDES)) envVars.get(ENV_DLL_OVERRIDES) else JSONObject.NULL)
        envVars.put(ENV_DLL_OVERRIDES, addOverrides(envVars.get(ENV_DLL_OVERRIDES), mod.dllOverrides))
        container.envVars = envVars.toString()
        container.saveData()

        val settings = DuoSettingsStore.load(context, container.id)
        state.put("previousScreenSplit", settings.screenSplit)
        DuoSettingsStore.save(context, container.id, settings.copy(screenSplit = true))

        state.put("mod", mod.id)
        stateFile(context, mod.appId).apply { parentFile?.mkdirs() }.writeText(state.toString(2))
        Timber.tag(DualScreenHost.TAG).i("Enabled dual-screen mod %s for %s (screen %s)", mod.id, container.id, container.screenSize)
        true
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).e(t, "Failed to enable dual-screen mod %s", mod.id)
        false
    }

    /** Undoes [enable]. Returns false (and logs) on failure. */
    fun disable(context: Context, mod: DuoGameMod): Boolean = try {
        val stateFile = stateFile(context, mod.appId)
        val state = JSONObject(stateFile.readText())
        val container = ContainerUtils.getContainer(context, mod.appId)

        val files = state.optJSONArray("files") ?: JSONArray()
        for (i in 0 until files.length()) {
            val entry = files.getJSONObject(i)
            val file = File(entry.getString("path"))
            if (file.isFile && sha256(file) == entry.getString("sha256")) file.delete()
            // Remove the mod's own folder once it is empty; never parents beyond it.
            file.parentFile?.takeIf { it.name.startsWith("GameNativeDuo") && it.list()?.isEmpty() == true }?.delete()
        }

        val ourSize = ScreenSplit.recommendedFor(context)?.let { "${it.first}x${it.second}" }
        // Restore the screen size unless the user changed it since.
        if (ourSize == null || container.screenSize == ourSize) {
            container.screenSize = state.optString("previousScreenSize", container.screenSize)
        }

        val envVars = EnvVars(container.envVars)
        val previous = state.opt("previousDllOverrides")
        if (previous == null || previous == JSONObject.NULL) {
            envVars.remove(ENV_DLL_OVERRIDES)
        } else {
            envVars.put(ENV_DLL_OVERRIDES, previous.toString())
        }
        container.envVars = envVars.toString()
        container.saveData()

        val settings = DuoSettingsStore.load(context, container.id)
        DuoSettingsStore.save(context, container.id, settings.copy(screenSplit = state.optBoolean("previousScreenSplit", false)))

        stateFile.delete()
        Timber.tag(DualScreenHost.TAG).i("Disabled dual-screen mod %s for %s", mod.id, container.id)
        true
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).e(t, "Failed to disable dual-screen mod %s", mod.id)
        false
    }

    private fun copyAsset(context: Context, asset: String, target: File): JSONObject {
        target.parentFile?.mkdirs()
        context.assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
        return JSONObject().put("path", target.absolutePath).put("sha256", sha256(target))
    }

    /** Adds Wine DLL overrides (`name=mode`, `;`-separated) to an existing value, replacing same-name entries. */
    private fun addOverrides(existing: String?, overrides: List<String>): String {
        val names = overrides.map { it.substringBefore('=') }.toSet()
        val kept = existing.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() && it.substringBefore('=') !in names }
        return (kept + overrides).joinToString(";")
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun stateFile(context: Context, appId: String) =
        File(context.filesDir, "duo/mods/" + appId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")
}
