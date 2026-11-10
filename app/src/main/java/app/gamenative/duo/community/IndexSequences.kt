package app.gamenative.duo.community

import android.content.Context
import app.gamenative.duo.DualScreenHost
import java.io.File
import org.json.JSONObject
import timber.log.Timber

/**
 * The highest `sequence` accepted for each index file (`files/duo/community/index/sequences.json`; names: a game key,
 * [CATALOG], [REVOKED]). Kept apart from the cached files, which are deleted when a game has no packages any more:
 * the mark must outlive them, or an old signed list could come back after an empty answer. See [IndexFreshness].
 */
object IndexSequences {
    const val CATALOG = "catalog"
    const val REVOKED = "revoked"

    @Synchronized
    fun highest(context: Context, name: String): Long = read(context).optLong(name, 0L)

    /** Remembers [sequence] for [name] when it is higher than the mark. */
    @Synchronized
    fun accept(context: Context, name: String, sequence: Long) {
        try {
            val o = read(context)
            if (sequence <= o.optLong(name, 0L)) return
            val file = file(context).apply { parentFile?.mkdirs() }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.put(name, sequence).toString())
            if (!tmp.renameTo(file)) file.writeText(o.toString())
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Community: sequence of %s not saved", name)
        }
    }

    private fun read(context: Context): JSONObject = try {
        file(context).takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: JSONObject()
    } catch (t: Throwable) {
        JSONObject()
    }

    private fun file(context: Context) = File(context.filesDir, "duo/community/index/sequences.json")
}
