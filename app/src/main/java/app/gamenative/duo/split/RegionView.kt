package app.gamenative.duo.split

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Region view: the game runs unchanged at its normal size on the top screen, and the bottom screen shows a live,
 * magnified part of the same frame (a minimap, an inventory corner…). The renderer draws it from the same GPU frame
 * as the top screen (no copy, no extra game work). Taps on the bottom screen click at the matching point of the game.
 * Works for any game, whatever its engine: the fallback when a game has no dual-screen mod.
 *
 * A region is stored as fractions of the game screen, `"x,y,w,h"` (so it survives resolution changes), and always has
 * the bottom display's shape, so nothing is stretched.
 */
object RegionView {
    /** Top-right corner, where many games keep their minimap. */
    private val DEFAULT = floatArrayOf(0.72f, 0f, 0.28f, 0.4f)

    /** Smallest region, as a fraction of the screen width (beyond that, zooming in shows blurry pixels). */
    private const val MIN_WIDTH = 0.05f

    fun parse(text: String?): FloatArray? {
        val parts = text?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: return null
        if (parts.size != 4 || parts.any { !it.isFinite() } || parts[2] <= 0f || parts[3] <= 0f) return null
        return parts.toFloatArray()
    }

    fun format(fractions: FloatArray): String = fractions.joinToString(",") { String.format(Locale.ROOT, "%.4f", it) }

    /**
     * The region in screen pixels (`{x, y, w, h}`) for a [screenW] x [screenH] game screen, reshaped around its centre to
     * [aspect] (bottom display width / height) and kept inside the screen.
     */
    fun pixels(fractions: FloatArray?, screenW: Int, screenH: Int, aspect: Float): FloatArray {
        val f = fractions ?: DEFAULT
        val r = floatArrayOf(f[0] * screenW, f[1] * screenH, f[2] * screenW, f[3] * screenH)
        return fit(r, screenW, screenH, aspect)
    }

    /** Back to fractions of the screen. */
    fun fractions(pixels: FloatArray, screenW: Int, screenH: Int): FloatArray =
        floatArrayOf(pixels[0] / screenW, pixels[1] / screenH, pixels[2] / screenW, pixels[3] / screenH)

    /** [r] moved by ([dx], [dy]) screen pixels, kept inside the screen. */
    fun pan(r: FloatArray, dx: Float, dy: Float, screenW: Int, screenH: Int): FloatArray =
        clamp(floatArrayOf(r[0] + dx, r[1] + dy, r[2], r[3]), screenW, screenH)

    /**
     * [r] zoomed by [factor] (> 1 zooms in: a smaller region) around the point ([fx], [fy]) of the region given as fractions
     * of its size (so the spot under the fingers stays put), kept to the bottom display's shape and inside the screen.
     */
    fun zoom(r: FloatArray, factor: Float, fx: Float, fy: Float, screenW: Int, screenH: Int, aspect: Float): FloatArray {
        val w = (r[2] / factor).coerceIn(MIN_WIDTH * screenW, screenW.toFloat())
        val h = w / aspect
        val px = r[0] + fx * r[2]
        val py = r[1] + fy * r[3]
        return fit(floatArrayOf(px - fx * w, py - fy * h, w, h), screenW, screenH, aspect)
    }

    private fun fit(r: FloatArray, screenW: Int, screenH: Int, aspect: Float): FloatArray {
        val cx = r[0] + r[2] / 2f
        val cy = r[1] + r[3] / 2f
        // Same area, display's shape; never larger than the screen.
        var w = kotlin.math.sqrt(r[2] * r[3] * aspect)
        var h = w / aspect
        val shrink = max(w / screenW, h / screenH)
        if (shrink > 1f) {
            w /= shrink
            h /= shrink
        }
        w = max(w, min(MIN_WIDTH * screenW, screenW.toFloat()))
        h = w / aspect
        return clamp(floatArrayOf(cx - w / 2f, cy - h / 2f, w, h), screenW, screenH)
    }

    private fun clamp(r: FloatArray, screenW: Int, screenH: Int): FloatArray = floatArrayOf(
        r[0].coerceIn(0f, max(0f, screenW - r[2])),
        r[1].coerceIn(0f, max(0f, screenH - r[3])),
        r[2],
        r[3],
    )
}
