package app.gamenative.duo.split

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.display.DeviceProfile
import app.gamenative.duo.display.realSize
import com.winlator.container.Container
import com.winlator.renderer.VulkanRenderer
import com.winlator.xserver.Pointer
import com.winlator.xserver.XServer
import timber.log.Timber

/**
 * Shows one tall X screen across both screens: the top screen keeps upstream's renderer, cropped to the
 * upper part, and the same renderer (same GPU device and imported window buffers) also draws the lower part
 * into a surface on the bottom screen. No pixels are copied and the game's buffers have a single reader.
 * Taps on the bottom screen become mouse clicks at the matching X screen position.
 *
 * When the bottom screen is missing or off, the top screen goes back to showing the whole X screen.
 */
class SplitScreenController(
    private val context: Context,
    private val xServer: XServer,
    private val profile: DeviceProfile,
    private val container: Container,
    private val layout: SplitLayout,
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
            val screenW = xServer.screenInfo.width.toInt()
            val screenH = xServer.screenInfo.height.toInt()
            val newSplit = bottomDisplay?.let {
                when (layout) {
                    SplitLayout.FILL -> ScreenSplit.of(screenW, screenH, topAspect())
                    SplitLayout.NATIVE -> ScreenSplit.nativeFor(context)?.takeIf { it.screenSize() == screenW to screenH }
                }
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
            LayoutFile.write(container, newSplit)
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
        val (w, h) = when (layout) {
            SplitLayout.FILL -> ScreenSplit.recommendedSize(xServer.screenInfo.width.toInt(), topAspect(), size.x.toFloat() / size.y)
            SplitLayout.NATIVE -> ScreenSplit.nativeFor(context)?.screenSize() ?: return
        }
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
) : Presentation(outerContext, display),
    SurfaceHolder.Callback {
    private var buttonDown = false
    private val handler = Handler(Looper.getMainLooper())
    private var attachTries = 0
    private var surfaceSize: Pair<Int, Int>? = null
    private var holder: SurfaceHolder? = null

    /** The top renderer initialises asynchronously; retry until it accepts the second screen. */
    private val attach = object : Runnable {
        override fun run() {
            val h = holder ?: return
            val (width, height) = surfaceSize ?: return
            try {
                val t = DuoViewport.transform(rect, width, height, xServer.screenInfo.width.toInt(), xServer.screenInfo.height.toInt())
                if (topRenderer.duoSetSecondary(h.surface, t)) {
                    Timber.tag(DualScreenHost.TAG).i("Second screen output attached (%dx%d)", width, height)
                    return
                }
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).e(t, "Failed to attach second screen output")
                return
            }
            if (++attachTries <= 60) {
                handler.postDelayed(this, 500)
            } else {
                Timber.tag(DualScreenHost.TAG).w("Second screen output could not be created")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        // The top screen's renderer draws into this surface too (same GPU device, see DuoSecondaryOutput.cpp).
        val surfaceView = SurfaceView(context).apply {
            holder.addCallback(this@SplitBottomPresentation)
            setOnTouchListener { v, event -> onTouch(v.width, v.height, event) }
        }
        setContentView(
            FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                addView(surfaceView)
            },
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        this.holder = holder
        surfaceSize = width to height
        attachTries = 0
        handler.removeCallbacks(attach)
        attach.run()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        handler.removeCallbacks(attach)
        this.holder = null
        try {
            topRenderer.duoClearSecondary()
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to detach second screen output")
        }
    }

    override fun onStop() {
        if (buttonDown) inject { xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT) }
        buttonDown = false
        super.onStop()
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
