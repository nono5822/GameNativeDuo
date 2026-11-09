package app.gamenative.duo.community

import android.content.Context
import app.gamenative.duo.DualScreenHost
import java.io.File
import org.json.JSONObject
import timber.log.Timber

/**
 * The author key each community package was first installed with (devkit-platform.md, "Author keys"), per device:
 * `files/duo/community/author-keys.json`. The index CI verified every signature and handover; the app only compares
 * keys, so an update from someone who does not hold the author's key ("this mod changed owner") is shown as such.
 */
object AuthorKeyPins {
    enum class Check {
        /** Never installed here (or not signed then): nothing to compare. */
        FIRST,

        /** Signed by the key it was installed with. */
        SAME,

        /** A newer key the remembered one handed over to (the chain is in the index, checked by CI). */
        HANDED_OVER,

        /** Another key, or no key any more: whoever publishes it now does not hold the author's key. */
        CHANGED_OWNER,
    }

    /** Pure: [pinned] is the remembered key, null when none. */
    fun check(pinned: String?, pkg: CommunityPackage): Check = when {
        pinned == null -> Check.FIRST
        pkg.authorKey == null -> Check.CHANGED_OWNER
        pkg.authorKey == pinned -> Check.SAME
        pinned in pkg.authorKeys.dropLast(1) -> Check.HANDED_OVER
        else -> Check.CHANGED_OWNER
    }

    fun check(context: Context, pkg: CommunityPackage): Check = check(pinned(context, pkg.id), pkg)

    fun pinned(context: Context, packageId: String): String? = read(context).optString(packageId).ifEmpty { null }

    /** Remembers [pkg]'s key after an install the user agreed to (also moves the pin along a handover). */
    fun pin(context: Context, pkg: CommunityPackage) {
        val key = pkg.authorKey ?: return
        try {
            val o = read(context)
            if (o.optString(pkg.id) == key) return
            file(context).apply { parentFile?.mkdirs() }.writeText(o.put(pkg.id, key).toString(2))
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Community: author key of %s not saved", pkg.id)
        }
    }

    /** A short, readable form of a key for the consent screen: the last 12 characters of its base64 part. */
    fun fingerprint(key: String): String = key.substringAfter(' ').takeLast(12)

    private fun read(context: Context): JSONObject = try {
        file(context).takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: JSONObject()
    } catch (t: Throwable) {
        JSONObject()
    }

    private fun file(context: Context) = File(context.filesDir, "duo/community/author-keys.json")
}
