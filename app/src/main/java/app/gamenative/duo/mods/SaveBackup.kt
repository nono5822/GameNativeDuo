package app.gamenative.duo.mods

import android.content.Context
import app.gamenative.duo.DualScreenHost
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Copies of a game's saves, taken before a dual-screen mod (code that runs inside the game) is installed, and put back
 * with one tap from the mod explorer. Zips in `files/duo/saves/<appId>/`, newest [KEEP] kept; a backup identical to the
 * newest one is not stored twice. Restoring backs up the current saves first, so it can be undone the same way.
 * Blocking: call from a background thread.
 */
object SaveBackup {
    private const val KEEP = 5
    private const val MANIFEST = "duo-backup.json"
    private const val MAX_BYTES = 512L * 1024 * 1024
    private const val PREFIX_ROOT = "drive_c"
    private const val GAME_ROOT = "game"

    /** A save folder: [key] is how it is named inside the zip (`drive_c/...` or `game/...`). */
    data class Location(val key: String, val dir: File, val excludes: Set<String> = emptySet())

    data class Backup(val file: File, val createdAt: Long, val reason: String, val files: Int, val bytes: Long)

    /**
     * Where [appId] keeps its saves, from what we know per game, else Unity's own rule (`*_Data/app.info` names the
     * company and product: `AppData/LocalLow/<company>/<product>`). Only folders that exist.
     */
    fun locations(appId: String, gameDir: File, driveC: File): List<Location> {
        val user = File(driveC, "users/xuser")
        val roaming = "users/xuser/AppData/Roaming"
        fun prefix(rel: String, vararg excludes: String) = Location("$PREFIX_ROOT/$rel", File(driveC, rel), excludes.toSet())
        fun game(rel: String) = Location("$GAME_ROOT/$rel", File(gameDir, rel))
        val known = when (appId) {
            // Our Lua mod lives in Balatro's own folder: never part of its saves.
            "STEAM_2379780" -> listOf(prefix("$roaming/Balatro", "Mods"))
            "STEAM_413150" -> listOf(prefix("$roaming/StardewValley", "ErrorLogs"))
            "STEAM_264710" -> listOf(game("SNAppData/SavedGames"))
            else -> emptyList()
        }
        val unity = if (known.isEmpty() && user.isDirectory) unityLocation(gameDir)?.let { listOf(prefix(it)) }.orEmpty() else emptyList()
        return (known + unity).filter { it.dir.isDirectory }
    }

    private fun unityLocation(gameDir: File): String? {
        val info = gameDir.listFiles { f -> f.isDirectory && f.name.endsWith("_Data") }
            ?.map { File(it, "app.info") }?.firstOrNull { it.isFile } ?: return null
        val lines = info.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val company = lines.getOrNull(0) ?: return null
        val product = lines.getOrNull(1) ?: return null
        if (listOf(company, product).any { it.contains("..") || it.contains('/') || it.contains('\\') }) return null
        return "users/xuser/AppData/LocalLow/$company/$product"
    }

    /**
     * Backs up [appId]'s saves; [reason] is shown in the list ("Before turning on Stardew Valley"). Returns the backup
     * (the newest one when nothing changed since), or null when the game has no saves we know of. Throws on failure.
     */
    fun backup(context: Context, appId: String, gameDir: File, driveC: File, reason: String): Backup? {
        val locations = locations(appId, gameDir, driveC)
        val files = locations.flatMap { loc -> walk(loc).map { loc to it } }
        if (files.isEmpty()) return null
        val total = files.sumOf { it.second.length() }
        if (total > MAX_BYTES) {
            Timber.tag(DualScreenHost.TAG).w("Saves of %s are %d MB: too big to back up", appId, total shr 20)
            return null
        }
        val dir = dir(context, appId).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(dir, "$stamp.zip")
        val temp = File(dir, "$stamp.zip.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = ZipOutputStream(temp.outputStream().buffered()).use { zip ->
            val buffer = ByteArray(64 * 1024)
            files.sortedBy { (loc, f) -> loc.key + "/" + f.relativeTo(loc.dir).invariantSeparatorsPath }.forEach { (loc, f) ->
                val name = loc.key + "/" + f.relativeTo(loc.dir).invariantSeparatorsPath
                digest.update(name.toByteArray())
                zip.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                f.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        zip.write(buffer, 0, n)
                    }
                }
                zip.closeEntry()
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val manifest = JSONObject()
                .put("createdAt", System.currentTimeMillis())
                .put("reason", reason)
                .put("hash", hash)
                .put("files", files.size)
                .put("bytes", total)
                .put(
                    "locations",
                    JSONArray(
                        locations.map { loc ->
                            JSONObject().put("key", loc.key).put("excludes", JSONArray(loc.excludes.toList()))
                        },
                    ),
                )
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString(2).toByteArray())
            zip.closeEntry()
            hash
        }
        val newest = list(context, appId).firstOrNull()
        if (newest != null && readManifest(newest.file)?.optString("hash") == hash) {
            temp.delete()
            Timber.tag(DualScreenHost.TAG).i("Saves of %s unchanged since %s: no new backup", appId, newest.file.name)
            return newest
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            error("Could not write $target")
        }
        list(context, appId).drop(KEEP).forEach { it.file.delete() }
        Timber.tag(DualScreenHost.TAG).i("Saves of %s backed up: %d files, %d KB (%s)", appId, files.size, total shr 10, target.name)
        return list(context, appId).firstOrNull { it.file == target }
    }

    /** Backups of [appId], newest first. */
    fun list(context: Context, appId: String): List<Backup> = dir(context, appId).listFiles { f -> f.name.endsWith(".zip") }
        .orEmpty()
        .mapNotNull { f ->
            val m = readManifest(f) ?: return@mapNotNull null
            Backup(f, m.optLong("createdAt", f.lastModified()), m.optString("reason"), m.optInt("files"), m.optLong("bytes"))
        }
        .sortedByDescending { it.createdAt }

    /**
     * Puts [backup] back: the current saves are backed up first ([undoReason]), then every save folder in the backup
     * is emptied (except its excluded entries) and filled from the zip. Throws on failure.
     */
    fun restore(context: Context, appId: String, gameDir: File, driveC: File, backup: Backup, undoReason: String) {
        val manifest = readManifest(backup.file) ?: error("${backup.file.name} is not a save backup")
        backup(context, appId, gameDir, driveC, undoReason)
        val roots = manifest.optJSONArray("locations") ?: JSONArray()
        fun rootOf(key: String): File? = when {
            key.startsWith("$PREFIX_ROOT/") -> inside(driveC, key.removePrefix("$PREFIX_ROOT/"))
            key.startsWith("$GAME_ROOT/") -> inside(gameDir, key.removePrefix("$GAME_ROOT/"))
            else -> null
        }
        for (i in 0 until roots.length()) {
            val o = roots.getJSONObject(i)
            val dir = rootOf(o.getString("key")) ?: continue
            val excludes = o.optJSONArray("excludes")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            dir.listFiles()?.filter { it.name !in excludes && !(it.isFile && it.name.endsWith(".log", ignoreCase = true)) }
                ?.forEach { it.deleteRecursively() }
        }
        ZipInputStream(backup.file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || entry.name == MANIFEST) continue
                val target = rootOf(entry.name) ?: continue
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
                if (entry.time > 0) target.setLastModified(entry.time)
            }
        }
        Timber.tag(DualScreenHost.TAG).i("Saves of %s restored from %s", appId, backup.file.name)
    }

    // Logs are not saves, and Unity rewrites Player.log on every launch (no backup would ever match the last one).
    private fun walk(loc: Location): List<File> = loc.dir.listFiles().orEmpty()
        .filter { it.name !in loc.excludes }
        .flatMap { top -> if (top.isDirectory) top.walkTopDown().filter { it.isFile }.toList() else listOf(top) }
        .filter { it.isFile && !it.name.endsWith(".log", ignoreCase = true) }

    /** [rel] under [root], refusing anything that would land outside it. */
    private fun inside(root: File, rel: String): File? {
        val target = File(root, rel).canonicalFile
        val base = root.canonicalFile
        return target.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    private fun readManifest(zipFile: File): JSONObject? = try {
        ZipFile(zipFile).use { zip ->
            zip.getEntry(MANIFEST)?.let { entry -> zip.getInputStream(entry).use { JSONObject(it.readBytes().decodeToString()) } }
        }
    } catch (t: Throwable) {
        null
    }

    private fun dir(context: Context, appId: String) =
        File(context.filesDir, "duo/saves/" + appId.replace(Regex("[^A-Za-z0-9_.-]"), "_"))
}
