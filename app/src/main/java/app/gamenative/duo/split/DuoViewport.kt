package app.gamenative.duo.split

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Maths for showing a rectangle of the X screen on an Android surface, shared by the renderer hook
 * (`VulkanRenderer.updateTransform`) and touch mapping. Pure functions: safe to call from render threads.
 */
object DuoViewport {
    /**
     * Upstream's Vulkan renderer places each window at `ndc = (ox + X * sx) / screenW * 2 - 1` (same for y).
     * Returns `{ox, oy, sx, sy}` that shows [rect] (`{x, y, w, h}` in X screen pixels) as large as fits on the
     * surface, centred and undistorted; null leaves upstream's own transform in charge.
     */
    @JvmStatic
    fun transform(rect: FloatArray?, surfaceW: Int, surfaceH: Int, screenW: Int, screenH: Int): FloatArray? {
        val fit = fit(rect, surfaceW, surfaceH) ?: return null
        if (screenW <= 0 || screenH <= 0) return null
        return floatArrayOf(
            screenW * (fit.offsetX - rect!![0] * fit.scale) / surfaceW,
            screenH * (fit.offsetY - rect[1] * fit.scale) / surfaceH,
            screenW * fit.scale / surfaceW,
            screenH * fit.scale / surfaceH,
        )
    }

    /** X screen coordinates under surface point ([sx], [sy]), or null when the point is outside the shown rectangle. */
    fun surfaceToScreen(rect: FloatArray, surfaceW: Int, surfaceH: Int, sx: Float, sy: Float): Pair<Int, Int>? {
        val fit = fit(rect, surfaceW, surfaceH) ?: return null
        val x = rect[0] + (sx - fit.offsetX) / fit.scale
        val y = rect[1] + (sy - fit.offsetY) / fit.scale
        if (x < rect[0] || y < rect[1] || x >= rect[0] + rect[2] || y >= rect[1] + rect[3]) return null
        return x.toInt() to y.toInt()
    }

    private class Fit(val scale: Float, val offsetX: Float, val offsetY: Float)

    private fun fit(rect: FloatArray?, surfaceW: Int, surfaceH: Int): Fit? {
        if (rect == null || rect.size < 4 || rect[2] <= 0f || rect[3] <= 0f || surfaceW <= 0 || surfaceH <= 0) return null
        val scale = min(surfaceW / rect[2], surfaceH / rect[3])
        return Fit(scale, (surfaceW - rect[2] * scale) / 2f, (surfaceH - rect[3] * scale) / 2f)
    }
}

/**
 * One tall X screen cut into the part shown on the top screen and the part shown on the bottom screen.
 * The game draws its world in [top] and its UI in [bottom] (e.g. via a mod that knows the layout).
 */
class ScreenSplit(val top: FloatArray, val bottom: FloatArray) {
    override fun equals(other: Any?): Boolean =
        other is ScreenSplit && top.contentEquals(other.top) && bottom.contentEquals(other.bottom)

    override fun hashCode(): Int = 31 * top.contentHashCode() + bottom.contentHashCode()

    companion object {
        /**
         * Splits a [screenW] x [screenH] X screen: the top part has the top display's shape ([topAspect] = w / h),
         * the bottom part gets the rest. Null when the X screen is not tall enough to leave a bottom part.
         */
        fun of(screenW: Int, screenH: Int, topAspect: Float): ScreenSplit? {
            if (screenW <= 0 || screenH <= 0 || topAspect <= 0f) return null
            val topH = (screenW / topAspect).roundToInt()
            if (topH >= screenH - 16) return null
            return ScreenSplit(
                top = floatArrayOf(0f, 0f, screenW.toFloat(), topH.toFloat()),
                bottom = floatArrayOf(0f, topH.toFloat(), screenW.toFloat(), (screenH - topH).toFloat()),
            )
        }

        /** X screen size whose top and bottom parts match both displays' shapes exactly at [width] pixels wide. */
        fun recommendedSize(width: Int, topAspect: Float, bottomAspect: Float): Pair<Int, Int> =
            width to ((width / topAspect).roundToInt() + (width / bottomAspect).roundToInt())
    }
}
