package app.gamenative.duo.companion

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Where a companion's files come from: the APK's assets (bundled), or a folder (community, dev). Paths in a manifest
 * are relative to the package and can never leave it; sizes are capped so a package cannot exhaust memory.
 */
sealed class CompanionFiles {
    abstract fun read(path: String): ByteArray?

    /** Folder whose changes should reload the companion (dev packages), or null. */
    open val watchDir: File? get() = null

    fun text(path: String): String? = read(path)?.toString(Charsets.UTF_8)

    class Assets(private val assets: AssetManager, private val dir: String) : CompanionFiles() {
        override fun read(path: String): ByteArray? {
            val safe = safePath(path) ?: return null
            return try {
                assets.open("$dir/$safe").use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (out.size() <= MAX_FILE) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray().takeIf { it.size <= MAX_FILE }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    class Folder(val dir: File, private val dev: Boolean = false) : CompanionFiles() {
        override val watchDir: File? get() = if (dev) dir else null

        override fun read(path: String): ByteArray? {
            val safe = safePath(path) ?: return null
            val f = File(dir, safe)
            return if (f.isFile && f.length() <= MAX_FILE) f.readBytes() else null
        }
    }

    companion object {
        /** 8 MB: a large map picture, far more than any manifest or dataset. */
        const val MAX_FILE = 8 * 1024 * 1024

        /** Images are decoded to at most this many pixels on their longer side. */
        const val MAX_IMAGE_SIDE = 2048

        /** [path] made safe (relative, no `..`, no backslashes), or null. */
        fun safePath(path: String): String? {
            val p = path.trim()
            if (p.isEmpty() || p.startsWith("/") || '\\' in p || ':' in p || p.length > 200) return null
            val parts = p.split('/')
            if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
            return p
        }

        /** Decodes [bytes] scaled down so neither side exceeds [maxSide]; null when it is not an image. */
        fun decodeImage(bytes: ByteArray, maxSide: Int = MAX_IMAGE_SIDE): Bitmap? = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                var sample = 1
                while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (_: Throwable) {
            null
        }
    }
}
