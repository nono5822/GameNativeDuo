package app.gamenative.duo.ui

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

/**
 * A part of the game's frame the user chose to show on the bottom screen, for games without a dual-screen mod: the
 * UI layout editor's elements in region view (`uniform-editing.md`, "regions as elements"). Same verbs as a mod's
 * elements: [screen] [UiScreen.BOTTOM] shows it below, [UiScreen.TOP] leaves it on the game only, [UiScreen.HIDDEN]
 * shows it below and blacks it out on the top screen (a HUD moved off the game view).
 *
 * [source] is `{x, y, w, h}` as fractions of the X screen (it survives resolution changes). Its place on the bottom
 * screen is its centre ([cx], [cy]) and width ([width]) as fractions of the bottom screen; the height follows the
 * source's shape, so nothing is stretched.
 */
data class UiRegion(
    val id: String,
    val name: String,
    val source: List<Float>,
    val screen: UiScreen = UiScreen.BOTTOM,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val width: Float = 0.5f,
    /** Taps on it click at the matching point of the game. */
    val touch: Boolean = true,
) {
    val shown: Boolean get() = screen == UiScreen.BOTTOM || screen == UiScreen.HIDDEN

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("source", JSONArray().apply { source.forEach { put(r(it)) } })
        put("screen", screen.id)
        put("cx", r(cx))
        put("cy", r(cy))
        put("width", r(width))
        if (!touch) put("touch", false)
    }

    companion object {
        const val MIN_SIZE = 0.02f
        const val MAX = 8

        fun fromJson(o: JSONObject): UiRegion? {
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val a = o.optJSONArray("source") ?: return null
            if (a.length() != 4) return null
            val src = (0 until 4).map { a.optDouble(it, Double.NaN).toFloat() }
            if (src.any { !it.isFinite() }) return null
            return UiRegion(
                id = id,
                name = o.optString("name").ifEmpty { id },
                source = clampSource(src),
                screen = UiScreen.fromId(o.optString("screen")).takeIf { it != UiScreen.DEFAULT } ?: UiScreen.BOTTOM,
                cx = o.optDouble("cx", 0.5).toFloat().fin(0.5f).coerceIn(0f, 1f),
                cy = o.optDouble("cy", 0.5).toFloat().fin(0.5f).coerceIn(0f, 1f),
                width = o.optDouble("width", 0.5).toFloat().fin(0.5f).coerceIn(0.05f, 1f),
                touch = o.optBoolean("touch", true),
            )
        }

        /** A new region: the middle of the game's frame, half the bottom screen wide. */
        fun create(existing: List<UiRegion>): UiRegion {
            var n = existing.size + 1
            while (existing.any { it.id == "region-$n" }) n++
            return UiRegion("region-$n", "Region $n", listOf(0.375f, 0.375f, 0.25f, 0.25f))
        }

        /** The single region of older region views (`"x,y,w,h"`, the bottom screen's shape) as a region filling it. */
        fun fromLegacy(fractions: FloatArray?): UiRegion? {
            val f = fractions ?: return null
            if (f.size != 4) return null
            return UiRegion("region-1", "Region 1", clampSource(f.toList()), cx = 0.5f, cy = 0.5f, width = 1f)
        }

        /** Inside the screen, at least [MIN_SIZE] wide and high. */
        fun clampSource(s: List<Float>): List<Float> {
            val w = s[2].coerceIn(MIN_SIZE, 1f)
            val h = s[3].coerceIn(MIN_SIZE, 1f)
            return listOf(s[0].coerceIn(0f, 1f - w), s[1].coerceIn(0f, 1f - h), w, h)
        }

        private fun Float.fin(default: Float) = if (isFinite()) this else default

        private fun r(v: Float) = String.format(Locale.ROOT, "%.4f", v).toDouble()
    }
}

/**
 * Turns regions into what the renderer and touch need. Pure maths (unit-tested): [screenW] x [screenH] is the X screen,
 * [surfaceW] x [surfaceH] the bottom screen's surface.
 */
object RegionOutput {
    /** The region's source in X screen pixels, `{x, y, w, h}`. */
    fun sourcePx(r: UiRegion, screenW: Int, screenH: Int): FloatArray =
        floatArrayOf(r.source[0] * screenW, r.source[1] * screenH, r.source[2] * screenW, r.source[3] * screenH)

    /**
     * Where the region goes on the bottom surface, `{x, y, w, h}` in surface pixels: its width, the source's shape (no
     * stretching), never taller than the surface, kept on it.
     */
    fun placePx(r: UiRegion, screenW: Int, screenH: Int, surfaceW: Int, surfaceH: Int): FloatArray {
        val src = sourcePx(r, screenW, screenH)
        val aspect = if (src[3] > 0f) src[2] / src[3] else 1f
        var w = r.width * surfaceW
        var h = w / aspect
        if (h > surfaceH) {
            h = surfaceH.toFloat()
            w = h * aspect
        }
        val x = (r.cx * surfaceW - w / 2f).coerceIn(min(0f, surfaceW - w), max(0f, surfaceW - w))
        val y = (r.cy * surfaceH - h / 2f).coerceIn(min(0f, surfaceH - h), max(0f, surfaceH - h))
        return floatArrayOf(x, y, w, h)
    }

    /**
     * `duoSetRegions` data: per shown region `{ox, oy, sx, sy, x, y, w, h}`, the renderer's transform (see
     * [app.gamenative.duo.split.DuoViewport.transform]) that maps its source onto its place, then the place (scissor).
     */
    fun nativeData(regions: List<UiRegion>, screenW: Int, screenH: Int, surfaceW: Int, surfaceH: Int): FloatArray {
        if (screenW <= 0 || screenH <= 0 || surfaceW <= 0 || surfaceH <= 0) return FloatArray(0)
        val out = ArrayList<Float>()
        for (r in regions.filter { it.shown }.take(UiRegion.MAX)) {
            val src = sourcePx(r, screenW, screenH)
            val dst = placePx(r, screenW, screenH, surfaceW, surfaceH)
            if (src[2] <= 0f) continue
            val scale = dst[2] / src[2]
            out += screenW * (dst[0] - src[0] * scale) / surfaceW
            out += screenH * (dst[1] - src[1] * scale) / surfaceH
            out += screenW * scale / surfaceW
            out += screenH * scale / surfaceH
            out += dst.toList()
        }
        return out.toFloatArray()
    }

    /** `duoSetMasks` data: the sources of hidden regions, `{x, y, w, h}` each in X screen pixels. */
    fun masks(regions: List<UiRegion>, screenW: Int, screenH: Int): FloatArray =
        regions.filter { it.screen == UiScreen.HIDDEN }.take(UiRegion.MAX).flatMap { sourcePx(it, screenW, screenH).toList() }.toFloatArray()

    /**
     * The X screen point under bottom-surface point ([x], [y]): the topmost shown region with touch on that contains
     * it (later regions are drawn over earlier ones), or null.
     */
    fun hit(regions: List<UiRegion>, x: Float, y: Float, screenW: Int, screenH: Int, surfaceW: Int, surfaceH: Int): Pair<Int, Int>? {
        for (r in regions.filter { it.shown }.take(UiRegion.MAX).asReversed()) {
            val dst = placePx(r, screenW, screenH, surfaceW, surfaceH)
            if (x < dst[0] || y < dst[1] || x >= dst[0] + dst[2] || y >= dst[1] + dst[3]) continue
            if (!r.touch) return null
            val src = sourcePx(r, screenW, screenH)
            val px = src[0] + (x - dst[0]) / dst[2] * src[2]
            val py = src[1] + (y - dst[1]) / dst[3] * src[3]
            return px.toInt() to py.toInt()
        }
        return null
    }
}
