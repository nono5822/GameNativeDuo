package app.gamenative.duo.split

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.display.DeviceProfile
import app.gamenative.duo.display.realSize
import com.winlator.renderer.VulkanRenderer
import com.winlator.widget.XServerView
import com.winlator.xserver.Pointer
import com.winlator.xserver.XServer
import timber.log.Timber

/**
 * Shows one tall X screen across both screens: the top screen keeps upstream's renderer, cropped to the
 * upper part; the bottom screen gets a second Vulkan renderer of the same X server, cropped to the lower
 * part. Both sample the same window buffers, so no pixels are copied. Taps on the bottom screen become
 * mouse clicks at the matching X screen position.
 *
 * When the bottom screen is missing or off, the top screen goes back to showing the whole X screen.
 */
class SplitScreenController(
    private val context: Context,
    private val xServer: XServer,
    private val profile: DeviceProfile,
) {
    private val topRenderer: VulkanRenderer? get() = xServer.renderer as? VulkanRenderer
    private var presentation: SplitBottomPresentation? = null
    private var split: ScreenSplit? = null

    /** Applies or removes the split to match the current displays. Safe to call on every display change. */
    fun update() {
        try {
            val top = topRenderer ?: run {
                Timber.tag(DualScreenHost.TAG).w("Screen split needs the Vulkan renderer; this session uses %s", xServer.renderer)
                return
            }
            val bottomDisplay = profile.findBottomDisplay(context)
            val newSplit = bottomDisplay?.let {
                ScreenSplit.of(xServer.screenInfo.width.toInt(), xServer.screenInfo.height.toInt(), topAspect())
            }
            if (bottomDisplay == null || newSplit == null) {
                if (bottomDisplay != null) logSizeHint(bottomDisplay)
                stop()
                return
            }
            if (presentation?.display?.displayId == bottomDisplay.displayId && split == newSplit) return
            dismissPresentation()
            split = newSplit
            top.setDuoViewRect(newSplit.top)
            presentation = SplitBottomPresentation(context, bottomDisplay, xServer, top, newSplit.bottom).also { it.show() }
            Timber.tag(DualScreenHost.TAG).i(
                "Screen split on: X screen %dx%d, top %s, bottom %s on display %d",
                xServer.screenInfo.width, xServer.screenInfo.height,
                newSplit.top.contentToString(), newSplit.bottom.contentToString(), bottomDisplay.displayId,
            )
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to update screen split")
            stop()
        }
    }

    fun stop() {
        dismissPresentation()
        if (split != null) Timber.tag(DualScreenHost.TAG).i("Screen split off")
        split = null
        try {
            topRenderer?.setDuoViewRect(null)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to restore top screen view")
        }
    }

    private fun dismissPresentation() {
        try {
            presentation?.dismiss()
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to dismiss bottom split view")
        }
        presentation = null
    }

    /** Shape of the top screen as the game sees it (landscape on the Thor). */
    private fun topAspect(): Float {
        val size = (context.display ?: return 16f / 9f).realSize()
        return if (size.y > 0) size.x.toFloat() / size.y else 16f / 9f
    }

    private fun logSizeHint(bottomDisplay: Display) {
        val size = bottomDisplay.realSize()
        val (w, h) = ScreenSplit.recommendedSize(xServer.screenInfo.width.toInt(), topAspect(), size.x.toFloat() / size.y)
        Timber.tag(DualScreenHost.TAG).w(
            "Screen split is on but the X screen %dx%d has no room for a bottom part; set the container's screen size to %dx%d",
            xServer.screenInfo.width, xServer.screenInfo.height, w, h,
        )
    }
}

private class SplitBottomPresentation(
    outerContext: Context,
    display: Display,
    private val xServer: XServer,
    private val topRenderer: VulkanRenderer,
    private val rect: FloatArray,
) : Presentation(outerContext, display) {
    private var view: XServerView? = null
    private var mirror: VulkanRenderer? = null
    private var buttonDown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        // The renderer registers X server listeners in its constructor; hold the locks the X thread uses
        // while it walks those lists.
        val xView = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE).use {
            XServerView(context, xServer, "vulkan")
        }
        val renderer = xView.renderer as VulkanRenderer
        renderer.setUnviewableWMClasses("explorer.exe")
        renderer.setForceFullscreenWMClass(topRenderer.forceFullscreenWMClass)
        renderer.setDuoViewRect(rect)
        xView.setOnTouchListener { v, event -> onTouch(v.width, v.height, event) }
        setContentView(
            FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                addView(xView)
            },
        )
        topRenderer.setDuoMirror(renderer)
        view = xView
        mirror = renderer
    }

    override fun onStop() {
        topRenderer.setDuoMirror(null)
        if (buttonDown) inject { xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT) }
        val renderer = mirror ?: return super.onStop()
        mirror = null
        try {
            xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE).use {
                xServer.windowManager.removeOnWindowModificationListener(renderer)
                xServer.pointer.removeOnPointerMotionListener(renderer)
            }
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to detach bottom renderer")
        }
        super.onStop()
        // The surface is destroyed with the window; free the renderer's native context after that.
        Handler(Looper.getMainLooper()).post {
            try {
                renderer.duoRelease()
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "Failed to release bottom renderer")
            }
        }
    }

    private fun onTouch(width: Int, height: Int, event: MotionEvent): Boolean {
        val pos = DuoViewport.surfaceToScreen(rect, width, height, event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (pos != null) {
                inject {
                    xServer.injectPointerMove(pos.first, pos.second)
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
                }
                buttonDown = true
            }
            MotionEvent.ACTION_MOVE -> if (pos != null && buttonDown) {
                inject { xServer.injectPointerMove(pos.first, pos.second) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (buttonDown) {
                inject {
                    if (pos != null) xServer.injectPointerMove(pos.first, pos.second)
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                }
                buttonDown = false
            }
        }
        return true
    }

    private inline fun inject(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to inject bottom screen touch")
        }
    }
}
