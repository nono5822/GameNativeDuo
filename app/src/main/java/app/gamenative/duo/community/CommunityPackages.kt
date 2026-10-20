package app.gamenative.duo.community

import android.content.Context
import android.util.Base64
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.settings.DuoSettingsStore
import app.gamenative.duo.ui.UiLayout
import app.gamenative.duo.ui.UiLayoutStore
import app.gamenative.utils.Net
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * Community packages for one game: fetch (ETag, so an unchanged index costs one 304), verify (ed25519 signature on the
 * index, sha256 on every file), cache on disk (`files/duo/community/`, works offline), apply.
 *
 * Blocking: call from a background thread. Nothing here throws to the caller except [IOException] from [refresh]
 * and [apply] (shown to the user as "could not load / apply").
 */
object CommunityPackages {
    /** Package ids applied to a container, by kind, with the version applied (to offer updates). */
    data class Applied(val id: String, val version: String)

    private val publicKey: ByteArray by lazy { Base64.decode(CommunityIndex.PUBLIC_KEY, Base64.DEFAULT) }

    /** The last verified index for [containerId] on disk, or null (never fetched, or the game has no packages). */
    fun cached(context: Context, containerId: String): CommunityGame? = try {
        val (json, sig) = indexFiles(context, CommunityIndex.gameKey(containerId))
        if (json.isFile && sig.isFile) parseVerified(json.readBytes(), sig.readText()) else null
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).w(t, "Ignoring the cached community index for %s", containerId)
        null
    }

    /**
     * Fetches the game's index if it changed since the cached copy. Returns the verified index, or an empty game when
     * nobody published a package for it yet. Throws [IOException] when offline or when the download is not trusted.
     */
    fun refresh(context: Context, containerId: String): CommunityGame = try {
        fetch(context, containerId)
    } catch (e: UntrustedException) {
        // The index and its signature are two requests; a publish between them gives a mismatched pair. Once more.
        fetch(context, containerId, revalidate = false)
    }

    private class UntrustedException : IOException("the package list's signature is not valid")

    private fun fetch(context: Context, containerId: String, revalidate: Boolean = true): CommunityGame {
        val key = CommunityIndex.gameKey(containerId)
        val (json, sig) = indexFiles(context, key)
        val etagFile = File(json.path + ".etag")
        val request = Request.Builder().url(CommunityIndex.gameUrl(key)).apply {
            if (revalidate && json.isFile && sig.isFile && etagFile.isFile) header("If-None-Match", etagFile.readText())
        }.build()
        Net.http.newCall(request).execute().use { response ->
            when {
                response.code == 304 -> return cached(context, containerId) ?: throw IOException("cache unreadable")
                response.code == 404 -> {
                    json.delete()
                    sig.delete()
                    etagFile.delete()
                    return CommunityGame(key, "", emptyList())
                }
                !response.isSuccessful -> throw IOException("HTTP ${response.code}")
            }
            val body = response.body.bytes()
            val signature = fetchText("${CommunityIndex.gameUrl(key)}.sig")
            val game = parseVerified(body, signature) ?: throw UntrustedException()
            json.parentFile?.mkdirs()
            writeAtomic(json, body)
            writeAtomic(sig, signature.toByteArray())
            response.header("ETag")?.let { etagFile.writeText(it) } ?: etagFile.delete()
            Timber.tag(DualScreenHost.TAG).i("Community: %d package(s) for %s", game.packages.size, key)
            return game
        }
    }

    /** The bytes of [file], from the local cache or downloaded, checked against its sha256. */
    fun download(context: Context, file: CommunityFile): ByteArray {
        val cache = File(dir(context), "blobs/${file.sha256}")
        if (cache.isFile) {
            val bytes = cache.readBytes()
            if (sha256(bytes) == file.sha256) return bytes
            cache.delete()
        }
        if (!file.url.startsWith("https://")) throw IOException("${file.path} is not served over https")
        val request = Request.Builder().url(file.url).build()
        val bytes = Net.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${file.path}")
            response.body.bytes()
        }
        if (sha256(bytes) != file.sha256) throw IOException("${file.path} does not match its checksum")
        cache.parentFile?.mkdirs()
        writeAtomic(cache, bytes)
        return bytes
    }

    /**
     * Applies a data package to the container: a layout becomes the game's UI layout (picked up at the next game
     * start), settings are merged into its GameNative Duo settings. Code mods are not installable yet.
     */
    fun apply(context: Context, containerId: String, pkg: CommunityPackage) {
        when (pkg.kind) {
            CommunityKind.UI_LAYOUT -> {
                val file = pkg.file("ui-layout.json") ?: throw IOException("the package has no ui-layout.json")
                val json = JSONObject(String(download(context, file), Charsets.UTF_8))
                val layout = UiLayout.fromJson(json, containerId).copy(game = containerId)
                UiLayoutStore.save(context, layout)
            }
            CommunityKind.DUO_SETTINGS -> {
                val file = pkg.file("duo-settings.json") ?: throw IOException("the package has no duo-settings.json")
                val json = JSONObject(String(download(context, file), Charsets.UTF_8))
                val current = DuoSettingsStore.load(context, containerId)
                // Only the settings the package names change.
                DuoSettingsStore.save(
                    context,
                    containerId,
                    current.copy(
                        screenSplit = json.optBoolean("screenSplit", current.screenSplit),
                        splitLayout = json.optString("splitLayout", current.splitLayout),
                        nativeTouch = json.optBoolean("nativeTouch", current.nativeTouch),
                        bottomPanelEnabled = json.optBoolean("bottomPanelEnabled", current.bottomPanelEnabled),
                    ),
                )
            }
            else -> throw IOException("${pkg.kind.id} packages cannot be installed by this version of the app")
        }
        setApplied(context, containerId, pkg)
        Timber.tag(DualScreenHost.TAG).i("Community: applied %s %s to %s", pkg.id, pkg.version, containerId)
    }

    /** Undoes an applied layout (back to the bundled preset or nothing). Settings stay as they are. */
    fun removeLayout(context: Context, containerId: String) {
        UiLayoutStore.reset(context, containerId)
        val record = readApplied(context, containerId)
        record.remove(CommunityKind.UI_LAYOUT.id)
        writeApplied(context, containerId, record)
    }

    /** What was applied to [containerId], by kind. */
    fun applied(context: Context, containerId: String): Map<CommunityKind, Applied> {
        val o = readApplied(context, containerId)
        return o.keys().asSequence().mapNotNull { kind ->
            val entry = o.optJSONObject(kind) ?: return@mapNotNull null
            CommunityKind.fromId(kind) to Applied(entry.optString("id"), entry.optString("version"))
        }.toMap()
    }

    /** True when [newer] is a higher semantic version than [older]. */
    fun isNewer(newer: String, older: String): Boolean {
        fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
        val a = parts(newer)
        val b = parts(older)
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    private fun setApplied(context: Context, containerId: String, pkg: CommunityPackage) {
        val record = readApplied(context, containerId)
        record.put(pkg.kind.id, JSONObject().put("id", pkg.id).put("version", pkg.version))
        writeApplied(context, containerId, record)
    }

    private fun readApplied(context: Context, containerId: String): JSONObject = try {
        appliedFile(context, containerId).takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: JSONObject()
    } catch (t: Throwable) {
        JSONObject()
    }

    private fun writeApplied(context: Context, containerId: String, record: JSONObject) {
        val file = appliedFile(context, containerId)
        file.parentFile?.mkdirs()
        writeAtomic(file, record.toString(2).toByteArray())
    }

    private fun parseVerified(bytes: ByteArray, signature: String): CommunityGame? {
        val sig = try {
            Base64.decode(signature.trim(), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (!Ed25519.verify(publicKey, bytes, sig)) return null
        return CommunityGame.fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
    }

    private fun fetchText(url: String): String = Net.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        response.body.string()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
    }

    private fun dir(context: Context) = File(context.filesDir, "duo/community")

    private fun indexFiles(context: Context, key: String) =
        File(dir(context), "index/$key.json") to File(dir(context), "index/$key.json.sig")

    private fun appliedFile(context: Context, containerId: String) =
        File(dir(context), "applied/" + containerId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")
}
