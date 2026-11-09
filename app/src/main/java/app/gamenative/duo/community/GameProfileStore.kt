package app.gamenative.duo.community

import android.content.Context
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.mods.DuoModCatalog
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

/**
 * Community game profiles (kind "game-profile", duo/docs/game-profiles.md): the applied one is kept in
 * `files/duo/profiles/<containerId>.json` and copied next to layout.json at every session start, over the bundled
 * profile. Removing it puts the bundled profile back (or none).
 */
object GameProfileStore {
    fun file(context: Context, containerId: String) = File(context.filesDir, "duo/profiles/$containerId.json")

    fun save(context: Context, containerId: String, bytes: ByteArray) {
        val f = file(context, containerId)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) f.writeBytes(bytes)
    }

    /** Drops the applied profile; the next session start takes its copy out of the prefix (or puts the bundled one back). */
    fun remove(context: Context, containerId: String) {
        file(context, containerId).delete()
        removedMarker(context, containerId).apply { parentFile?.mkdirs() }.writeText("")
    }

    fun clearRemoved(context: Context, containerId: String) {
        removedMarker(context, containerId).delete()
    }

    private fun removedMarker(context: Context, containerId: String) = File(context.filesDir, "duo/profiles/$containerId.removed")

    /** Session start: the applied community profile, else the bundled one, else nothing, in the prefix. */
    fun writeToPrefix(context: Context, container: Container) {
        try {
            val target = File(container.rootDir, ".wine/drive_c/${DuoModCatalog.PROFILE_IN_PREFIX}")
            val user = file(context, container.id)
            val bundled = DuoModCatalog.forApp(container.id)?.prefixFiles?.entries
                ?.firstOrNull { it.value == DuoModCatalog.PROFILE_IN_PREFIX }?.key
            val bytes = when {
                user.isFile -> user.readBytes()
                bundled != null -> context.assets.open(bundled).use { it.readBytes() }
                else -> null
            }
            val removed = removedMarker(context, container.id)
            if (bytes == null) {
                // A community profile was removed and there is no bundled one: take its copy out of the prefix.
                if (removed.isFile) target.delete()
                removed.delete()
                return
            }
            removed.delete()
            if (target.isFile && target.readBytes().contentEquals(bytes)) return
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) target.writeBytes(bytes)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to write the game profile")
        }
    }
}
