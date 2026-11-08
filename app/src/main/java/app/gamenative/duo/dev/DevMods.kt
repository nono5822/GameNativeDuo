package app.gamenative.duo.dev

import android.content.Context
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.mods.DuoModInstaller
import java.io.File
import timber.log.Timber

/**
 * Developer mode's dev-folder mods: `duo dev --device <mod>` pushes a mod to
 * `Android/data/app.gamenative.duo/files/duo/mods/dev/<mod>/`, laid out like the bundled mod's assets
 * (`assets/duo/mods/<mod>/...`; shared Unity files under `shared/<name>/`). At session start, before the game runs,
 * every file there replaces the installed copy. "Reset mod files" in Dual screen status puts the bundled ones back.
 */
object DevMods {
    fun dir(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, "duo/mods/dev") }

    /** Copies the dev folder of the installed mod over its files; the relative paths copied (empty when none). */
    fun apply(context: Context, appId: String): List<String> = try {
        val (mod, sources) = DuoModInstaller.installedSources(context, appId) ?: return emptyList()
        val dev = dir(context)?.let { File(it, mod) }?.takeIf { it.isDirectory } ?: return emptyList()
        val copied = mutableListOf<String>()
        for ((asset, target) in sources) {
            val rel = relative(asset, mod) ?: continue
            val from = File(dev, rel)
            if (from.isFile) {
                copy(from, target)
                copied += rel
            } else if (from.isDirectory) {
                from.walkTopDown().filter { it.isFile }.forEach { f ->
                    val sub = f.relativeTo(from).path
                    copy(f, File(target, sub))
                    copied += "$rel/$sub"
                }
            }
        }
        if (copied.isNotEmpty()) {
            Timber.tag(DualScreenHost.TAG).i("Developer mode: %d dev file(s) of %s loaded: %s", copied.size, mod, copied.take(10))
        }
        copied
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).e(t, "Developer mode: dev-folder mod not loaded")
        emptyList()
    }

    /** `duo/mods/<mod>/x/y` -> `x/y`; `duo/mods/shared/<name>` -> `shared/<name>`; else null. */
    internal fun relative(asset: String, mod: String): String? = when {
        asset.startsWith("duo/mods/$mod/") -> asset.removePrefix("duo/mods/$mod/")
        asset.startsWith("duo/mods/shared/") -> "shared/" + asset.removePrefix("duo/mods/shared/")
        else -> null
    }

    private fun copy(from: File, to: File) {
        to.parentFile?.mkdirs()
        from.copyTo(to, overwrite = true)
    }
}
