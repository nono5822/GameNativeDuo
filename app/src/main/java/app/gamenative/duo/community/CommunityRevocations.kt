package app.gamenative.duo.community

import android.content.Context
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.mods.DuoModInstaller
import app.gamenative.utils.Net
import java.io.File
import java.io.IOException
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Package versions a maintainer withdrew (`v1/revoked.json`, signed like the rest of the index; devkit-platform.md
 * "Revocation"). Fetched with an ETag (an unchanged list costs one 304), cached for offline use. An installed revoked
 * mod is removed through its undo record by [sweep] (after each refresh and when the game's page opens), and the
 * reason is shown once on that page ([takeNotice]). A session that starts before the sweep gives the mod no bridge
 * features ([revokedReason], read by DualScreenHost).
 */
object CommunityRevocations {
    data class Entry(val id: String, val versions: List<String>, val reason: String) {
        fun covers(packageId: String, version: String) = id == packageId && ("*" in versions || version in versions)
    }

    /** A mod [sweep] removed: shown once on its game's page. */
    data class Removed(val packageId: String, val version: String, val reason: String)

    const val URL = "${CommunityIndex.BASE_URL}/revoked.json"

    @Volatile private var entries: List<Entry>? = null

    @Volatile private var refreshed = false

    /** The verified list (memory, else the disk cache); empty before the first successful download. */
    fun known(context: Context): List<Entry> = entries ?: readCached(context)?.also { entries = it }.orEmpty()

    fun reason(entries: List<Entry>, packageId: String, version: String): String? =
        entries.firstOrNull { it.covers(packageId, version) }?.reason

    /** Parses a signed list; null when the signature does not match [publicKey] (base64). */
    fun parse(body: ByteArray, signature: String, publicKey: String = CommunityIndex.PUBLIC_KEY): List<Entry>? {
        // java.util.Base64 (not android.util): parse runs in local unit tests too.
        val sig = try {
            java.util.Base64.getMimeDecoder().decode(signature.trim())
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (!Ed25519.verify(java.util.Base64.getDecoder().decode(publicKey), body, sig)) return null
        val array = JSONObject(String(body, Charsets.UTF_8)).optJSONArray("revoked") ?: JSONArray()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val versions = o.optJSONArray("versions")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            Entry(o.optString("id"), versions, o.optString("reason")).takeIf { it.id.isNotEmpty() && versions.isNotEmpty() }
        }
    }

    /** Downloads the list at most once per app run, then [sweep]s. Never throws; blocking. */
    fun refreshOnce(context: Context) {
        if (refreshed) return
        refreshed = true
        try {
            fetch(context)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).d(t, "Community: revocation list not refreshed")
        }
        sweep(context)
    }

    private fun fetch(context: Context) {
        val file = cacheFile(context)
        val etag = File(file.path + ".etag")
        val request = Request.Builder().url(URL).apply {
            if (file.isFile && File(file.path + ".sig").isFile && etag.isFile) header("If-None-Match", etag.readText())
        }.build()
        Net.http.newCall(request).execute().use { response ->
            if (response.code == 304) return
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body.bytes()
            val signature = Net.http.newCall(Request.Builder().url("$URL.sig").build()).execute().use {
                if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                it.body.string()
            }
            val parsed = parse(body, signature) ?: throw IOException("the revocation list's signature is not valid")
            file.parentFile?.mkdirs()
            file.writeBytes(body)
            File(file.path + ".sig").writeText(signature)
            response.header("ETag")?.let { etag.writeText(it) } ?: etag.delete()
            entries = parsed
        }
    }

    /** Why the community mod installed for [containerId] was withdrawn, or null (also null when it is not one). */
    fun revokedReason(context: Context, containerId: String): String? {
        val origin = DuoModInstaller.origin(context, containerId) ?: return null
        return reason(known(context), origin.optString("package"), origin.optString("version"))
    }

    /** Removes every installed community mod whose version is withdrawn. Blocking; never throws. */
    fun sweep(context: Context): List<Removed> {
        val list = known(context)
        if (list.isEmpty()) return emptyList()
        val removed = mutableListOf<Removed>()
        for (containerId in DuoModInstaller.installedAppIds(context)) {
            try {
                val origin = DuoModInstaller.origin(context, containerId) ?: continue
                val id = origin.optString("package")
                val version = origin.optString("version")
                val why = reason(list, id, version) ?: continue
                if (DuoModInstaller.disable(context, containerId)) {
                    CommunityPackages.forget(context, containerId, CommunityKind.MOD)
                    val r = Removed(id, version, why)
                    writeNotice(context, containerId, r)
                    removed += r
                    Timber.tag(DualScreenHost.TAG).w("Community: removed withdrawn mod %s %s from %s: %s", id, version, containerId, why)
                } else {
                    Timber.tag(DualScreenHost.TAG).w("Community: could not remove withdrawn mod %s from %s", id, containerId)
                }
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "Community: revocation sweep failed for %s", containerId)
            }
        }
        return removed
    }

    /** The removal [sweep] recorded for [containerId], once: reading it clears it. */
    fun takeNotice(context: Context, containerId: String): Removed? = try {
        val file = noticeFile(context, containerId)
        file.takeIf { it.isFile }?.let { JSONObject(it.readText()) }?.let {
            file.delete()
            Removed(it.optString("package"), it.optString("version"), it.optString("reason"))
        }
    } catch (t: Throwable) {
        null
    }

    private fun writeNotice(context: Context, containerId: String, r: Removed) {
        noticeFile(context, containerId).apply { parentFile?.mkdirs() }
            .writeText(JSONObject().put("package", r.packageId).put("version", r.version).put("reason", r.reason).toString())
    }

    private fun readCached(context: Context): List<Entry>? = try {
        val file = cacheFile(context)
        val sig = File(file.path + ".sig")
        if (file.isFile && sig.isFile) parse(file.readBytes(), sig.readText()) else null
    } catch (t: Throwable) {
        null
    }

    private fun cacheFile(context: Context) = File(context.filesDir, "duo/community/index/revoked.json")

    private fun noticeFile(context: Context, containerId: String) =
        File(context.filesDir, "duo/community/revoked-notices/" + containerId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")
}
