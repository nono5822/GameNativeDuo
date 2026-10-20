package app.gamenative.duo.split

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import app.gamenative.duo.display.DeviceProfile
import app.gamenative.duo.display.realSize
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject

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

    /**
     * X screen coordinates under surface point ([sx], [sy]) as floats, also outside the shown rectangle (a finger
     * dragged off it keeps reporting); null only when nothing is shown.
     */
    fun surfaceToScreenUnbounded(rect: FloatArray, surfaceW: Int, surfaceH: Int, sx: Float, sy: Float, out: FloatArray): Boolean {
        val fit = fit(rect, surfaceW, surfaceH) ?: return false
        out[0] = rect[0] + (sx - fit.offsetX) / fit.scale
        out[1] = rect[1] + (sy - fit.offsetY) / fit.scale
        return true
    }

    private class Fit(val scale: Float, val offsetX: Float, val offsetY: Float)

    private fun fit(rect: FloatArray?, surfaceW: Int, surfaceH: Int): Fit? {
        if (rect == null || rect.size < 4 || rect[2] <= 0f || rect[3] <= 0f || surfaceW <= 0 || surfaceH <= 0) return null
        val scale = min(surfaceW / rect[2], surfaceH / rect[3])
        return Fit(scale, (surfaceW - rect[2] * scale) / 2f, (surfaceH - rect[3] * scale) / 2f)
    }
}

/** How a tall X screen is cut into the two screens. */
enum class SplitLayout(val id: String) {
    /** Both parts as wide as the X screen; the bottom part is scaled to fit the bottom display. */
    FILL("fill"),

    /** Each part at its display's own resolution, 1:1 (see [ScreenSplit.native]). */
    NATIVE("native"),
    ;

    companion object {
        fun fromId(id: String?): SplitLayout = entries.firstOrNull { it.id == id } ?: FILL
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

    /** Smallest X screen that holds both parts. */
    fun screenSize(): Pair<Int, Int> =
        maxOf(top[0] + top[2], bottom[0] + bottom[2]).roundToInt() to maxOf(top[1] + top[3], bottom[1] + bottom[3]).roundToInt()

    /** The layout as written to the game's prefix for mods (see `GameNativeDuo/layout.json`). */
    fun toJson(): JSONObject {
        fun rect(r: FloatArray) = JSONObject()
            .put("x", r[0].roundToInt())
            .put("y", r[1].roundToInt())
            .put("w", r[2].roundToInt())
            .put("h", r[3].roundToInt())
        val (w, h) = screenSize()
        return JSONObject().put("screen", JSONObject().put("w", w).put("h", h)).put("top", rect(top)).put("bottom", rect(bottom))
    }

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

        /**
         * Native layout: the X screen is the top display's own resolution stacked on the bottom display's, with the
         * bottom part centred. Each part maps 1:1 to its display's pixels; the game decides what to draw where
         * (e.g. a mod puts the world on top and the UI below). Sizes are as the displays are currently rotated.
         */
        fun native(topW: Int, topH: Int, bottomW: Int, bottomH: Int, scale: Float = 1f): ScreenSplit {
            // Below 100% the game renders fewer pixels and the renderer scales both parts up (even sizes for codecs/GPUs).
            fun s(v: Int) = if (scale >= 1f) v else ((v * scale).roundToInt() / 2) * 2
            return nativeUnscaled(s(topW), s(topH), s(bottomW), s(bottomH))
        }

        private fun nativeUnscaled(topW: Int, topH: Int, bottomW: Int, bottomH: Int): ScreenSplit {
            val w = maxOf(topW, bottomW)
            return ScreenSplit(
                top = floatArrayOf(((w - topW) / 2).toFloat(), 0f, topW.toFloat(), topH.toFloat()),
                bottom = floatArrayOf(((w - bottomW) / 2).toFloat(), topH.toFloat(), bottomW.toFloat(), bottomH.toFloat()),
            )
        }

        /** [native] for this device's displays (top in landscape), or null without a bottom screen. */
        fun nativeFor(context: Context, scale: Float = 1f): ScreenSplit? = displaySizes(context)?.let { (t, b) ->
            native(t.first, t.second, b.first, b.second, scale)
        }

        /** Container screen size for [layout] at [scale] (see DuoContainerSettings.splitScale), or null without a bottom screen. */
        fun recommendedFor(context: Context, layout: SplitLayout, scale: Float = 1f): Pair<Int, Int>? = when (layout) {
            SplitLayout.FILL -> recommendedFor(context, width = (1280 * minOf(scale, 1f)).roundToInt() / 2 * 2)
            SplitLayout.NATIVE -> nativeFor(context, scale)?.screenSize()
        }

        /** (top w, h) in landscape and (bottom w, h) as currently shown, or null without a bottom screen. */
        private fun displaySizes(context: Context): Pair<Pair<Int, Int>, Pair<Int, Int>>? = try {
            val bottom = DeviceProfile.detect().findBottomDisplay(context)
            val top = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            if (bottom == null || top == null) {
                null
            } else {
                val t = top.realSize()
                val b = bottom.realSize()
                (maxOf(t.x, t.y) to minOf(t.x, t.y)) to (b.x to b.y)
            }
        } catch (t: Throwable) {
            null
        }

        /** X screen size whose top and bottom parts match both displays' shapes exactly at [width] pixels wide. */
        fun recommendedSize(width: Int, topAspect: Float, bottomAspect: Float): Pair<Int, Int> =
            width to ((width / topAspect).roundToInt() + (width / bottomAspect).roundToInt())

        /** Container screen size ([width] wide) whose parts match this device's screens; null without a bottom screen. */
        fun recommendedFor(context: Context, width: Int = 1280): Pair<Int, Int>? = try {
            val bottom = DeviceProfile.detect().findBottomDisplay(context)
            val top = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            if (bottom == null || top == null) {
                null
            } else {
                val t = top.realSize()
                val b = bottom.realSize()
                // The game always runs landscape on the top screen.
                recommendedSize(width, maxOf(t.x, t.y).toFloat() / minOf(t.x, t.y), b.x.toFloat() / b.y)
            }
        } catch (t: Throwable) {
            null
        }
    }
}
