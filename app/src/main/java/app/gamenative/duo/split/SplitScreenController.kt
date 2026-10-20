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
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.bridge.DuoBridge
import app.gamenative.duo.display.DeviceProfile
import app.gamenative.duo.display.ScreenCalibration
import app.gamenative.duo.display.ScreenCalibrationStore
import app.gamenative.duo.display.realSize
import app.gamenative.duo.pc.PcEditorHost
import app.gamenative.duo.pc.PcEditorPanel
import app.gamenative.duo.pc.PcEditorServer
import app.gamenative.duo.ui.Snapshots
import app.gamenative.duo.ui.UiLayout
import app.gamenative.duo.ui.UiLayoutController
import app.gamenative.duo.ui.UiLayoutEditor
import app.gamenative.duo.ui.WidgetLayer
import com.winlator.container.Container
import com.winlator.renderer.VulkanRenderer
import com.winlator.xserver.Pointer
import com.winlator.xserver.XServer
import timber.log.Timber

/**
 * Shows one tall X screen across both screens: the top screen keeps upstream's renderer, cropped to the
 * upper part, and the same renderer (same GPU device and imported window buffers) also draws the lower part
 * into a surface on the bottom screen. No pixels are copied and the game's buffers have a single reader.
 * Bottom-screen touches go straight to the game's UI through the [DuoBridge] when the game's mod supports it
 * ([nativeTouch]: no cursor, no mouse input the game could react to), else they become mouse clicks at the matching
 * X screen position. A two-finger long press on the bottom screen opens [DuoMenuPanel]: the UI layout editor
 * ([UiLayoutEditor]) and [ScreenCalibrationPanel] to line the physical screens up.
 *
 * When the bottom screen is missing or off, the top screen goes back to showing the whole X screen.
 */
class SplitScreenController(
    private val context: Context,
    private val xServer: XServer,
    private val profile: DeviceProfile,
    private val container: Container,
    private val layout: SplitLayout,
    private val bridge: DuoBridge?,
    private val uiLayout: UiLayoutController?,
    private val nativeTouch: Boolean,
    /** Render scale of the native layout (see DuoContainerSettings.splitScale). */
    private val scale: Float = 1f,
    /** Region view: the bottom screen's part of the game (fractions, see [RegionView]); saved with [onRegionSaved]. */
    private var region: String = "",
    private val onRegionSaved: (String) -> Unit = {},
) {
    private val topRenderer: VulkanRenderer? get() = xServer.renderer as? VulkanRenderer
    private var presentation: SplitBottomPresentation? = null
    private var split: ScreenSplit? = null
    private var calibration = ScreenCalibrationStore.load(context, profile)

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
                    SplitLayout.NATIVE -> ScreenSplit.nativeFor(context, scale)?.takeIf { it.screenSize() == screenW to screenH }
                    SplitLayout.REGION -> {
                        val size = it.realSize()
                        ScreenSplit(
                            top = floatArrayOf(0f, 0f, screenW.toFloat(), screenH.toFloat()),
                            bottom = RegionView.pixels(RegionView.parse(region), screenW, screenH, size.x.toFloat() / size.y),
                        )
                    }
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
            if (layout == SplitLayout.REGION) {
                // The game is untouched: upstream's own view on top, no layout.json (mods must not split anything).
                top.setDuoViewRect(null)
                LayoutFile.delete(container)
            } else {
                top.setDuoViewRect(newSplit.top)
                LayoutFile.write(container, newSplit, calibration, bridge?.port ?: 0)
            }
            presentation = SplitBottomPresentation(
                context, bottomDisplay, xServer, top, newSplit.top, newSplit.bottom, calibration, profile.calibration,
                bridge = bridge.takeIf { nativeTouch && layout != SplitLayout.REGION },
                uiLayout = uiLayout.takeIf { layout != SplitLayout.REGION },
                regionView = layout == SplitLayout.REGION,
                onRegionDone = ::onRegionDone,
                onCalibrationChange = ::onCalibrationChange,
                onCalibrationDone = { ScreenCalibrationStore.save(context, profile, it) },
            ).also { it.show() }
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

    /** Region chosen on the bottom screen (null: back to the default); kept for this container. */
    private fun onRegionDone(pixels: FloatArray?) {
        val w = xServer.screenInfo.width.toInt()
        val h = xServer.screenInfo.height.toInt()
        region = pixels?.let { RegionView.format(RegionView.fractions(it, w, h)) }.orEmpty()
        onRegionSaved(region)
        if (pixels == null) {
            // Back to the default: rebuild the bottom view with it.
            split = null
            update()
        }
    }

    private fun onCalibrationChange(new: ScreenCalibration) {
        calibration = new
        split?.let { LayoutFile.write(container, it, new, bridge?.port ?: 0) }
    }

    // ---- for GameNative's in-game menu (duo/ui/DuoQuickMenu.kt) ---------------------------------------------------

    /** Opens the layout editor on the bottom screen. False when there is no bottom screen or no layout. */
    fun openEditor(): Boolean = presentation?.openEditor() == true

    /** Opens the screen alignment panel on the bottom screen. */
    fun openCalibration(): Boolean = presentation?.openCalibration() == true

    /** Starts the PC editor (if needed); its address and code, or null when it cannot run. */
    fun startPcEditor(showCard: Boolean): Pair<String?, String>? = presentation?.startPc(showCard)

    fun stopPcEditor() {
        presentation?.stopPc()
    }

    /** The PC editor's address and code while it runs. */
    val pcEditor: Pair<String?, String>? get() = presentation?.pcInfo

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
            SplitLayout.NATIVE -> ScreenSplit.nativeFor(context, scale)?.screenSize() ?: return
            SplitLayout.REGION -> return
        }
        Timber.tag(DualScreenHost.TAG).w(
            "Screen split is on but the X screen %dx%d has no room for a bottom part; set the container's screen size to %dx%d",
            xServer.screenInfo.width, xServer.screenInfo.height, w, h,
        )
    }
}

private class SplitBottomPresentation(
    private val outerContext: Context,
    display: Display,
    private val xServer: XServer,
    private val topRenderer: VulkanRenderer,
    override val topRect: FloatArray,
    /** The part of the X screen the bottom screen shows (changes live in region view). */
    private var rect: FloatArray,
    private var calibration: ScreenCalibration,
    private val calibrationDefaults: ScreenCalibration,
    /** Native touch through the game's mod; null for mouse clicks only. */
    private val bridge: DuoBridge?,
    private val uiLayout: UiLayoutController?,
    private val regionView: Boolean,
    private val onRegionDone: (FloatArray?) -> Unit,
    private val onCalibrationChange: (ScreenCalibration) -> Unit,
    private val onCalibrationDone: (ScreenCalibration) -> Unit,
) : Presentation(outerContext, display),
    SurfaceHolder.Callback,
    PcEditorHost {
    /** How the current gesture reaches the game, decided when its first finger lands. */
    private enum class Gesture { NONE, MOUSE, NATIVE, IGNORED }

    private var gesture = Gesture.NONE
    private var buttonDown = false
    private val handler = Handler(Looper.getMainLooper())
    private var attachTries = 0
    private var surfaceSize: Pair<Int, Int>? = null
    private var holder: SurfaceHolder? = null
    private var root: FrameLayout? = null
    private var overlay: View? = null
    private var surfaceView: SurfaceView? = null
    private var widgets: WidgetLayer? = null
    private var editor: UiLayoutEditor? = null
    private var pc: PcEditorServer? = null
    private val layoutListener: (UiLayout) -> Unit = { widgets?.show(it.widgets) }

    /** Snapshots are copied and compressed here, never on the main thread (started on first use). */
    private var snapThread: android.os.HandlerThread? = null
    private var snapHandlerOrNull: Handler? = null
    private val snapHandler: Handler
        get() = snapHandlerOrNull ?: android.os.HandlerThread("GNDuo-snap", android.os.Process.THREAD_PRIORITY_BACKGROUND).let {
            it.start()
            snapThread = it
            Handler(it.looper).also { h -> snapHandlerOrNull = h }
        }
    private val openMenu = Runnable { showMenu() }
    private val menuStart = FloatArray(4)
    private val point = FloatArray(2)
    private var slop = 0f

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
        slop = ViewConfiguration.get(context).scaledTouchSlop * 2f
        // The top screen's renderer draws into this surface too (same GPU device, see DuoSecondaryOutput.cpp).
        val surfaceView = SurfaceView(context).apply {
            holder.addCallback(this@SplitBottomPresentation)
            setOnTouchListener { v, event -> onTouch(v.width, v.height, event) }
        }
        this.surfaceView = surfaceView
        // Widgets sit above the game and below our panels; touches go through them to the game.
        val layer = WidgetLayer(context)
        widgets = layer
        root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView)
            addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(root!!)
        uiLayout?.let {
            it.listeners += layoutListener
            layer.show(it.layout.widgets)
        }
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

    // ---- overlays: menu, layout editor, calibration ------------------------------------------------------------

    private fun showOverlay(view: View) {
        hideOverlay()
        root?.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlay = view
    }

    private fun hideOverlay() {
        overlay?.let { root?.removeView(it) }
        overlay = null
        editor = null
    }

    // ---- background image, snapshots, PC editor ----------------------------------------------------------------

    /** The system photo picker (on the top screen); the chosen image becomes the bottom screen's background. */
    private fun pickImage() {
        val ui = uiLayout ?: return
        val activity = Snapshots.activityOf(outerContext) as? ComponentActivity ?: return
        try {
            var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null
            launcher = activity.activityResultRegistry.register(
                "gnduo-background-" + android.os.SystemClock.uptimeMillis(),
                PickVisualMedia(),
            ) { uri ->
                launcher?.unregister()
                if (uri == null) return@register
                snapHandler.post {
                    val bytes = try {
                        activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (t: Throwable) {
                        Timber.tag(DualScreenHost.TAG).w(t, "Could not read the chosen image")
                        null
                    }
                    if (bytes != null) handler.post { setImage(ui, bytes) }
                }
            }
            launcher.launch(
                PickVisualMediaRequest(PickVisualMedia.ImageOnly),
            )
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Could not open the image picker")
        }
    }

    /** The top screen's current picture (the game without its UI) as the bottom screen's background. */
    private fun snapshotBackground() {
        val ui = uiLayout ?: return
        snapshot(top = true) { jpeg -> if (jpeg != null) handler.post { setImage(ui, jpeg) } }
    }

    private fun setImage(ui: UiLayoutController, bytes: ByteArray) {
        val (w, h) = bottomSize
        ui.setBackgroundImage(bytes, w, h) { ok ->
            if (!ok) Timber.tag(DualScreenHost.TAG).w("The background image could not be used")
            editor?.onBackgroundChanged()
        }
    }

    override val controller: UiLayoutController get() = uiLayout!!
    override val windowW: Int get() = xServer.screenInfo.width.toInt()
    override val windowH: Int get() = xServer.screenInfo.height.toInt()
    override val bottomRect: FloatArray get() = rect
    override val bottomSize: Pair<Int, Int> get() = surfaceSize ?: (1240 to 1080)

    override fun snapshot(top: Boolean, done: (ByteArray?) -> Unit) {
        val view = if (top) Snapshots.activityOf(outerContext)?.window?.decorView?.let { Snapshots.findSurface(it) } else surfaceView
        if (view == null) {
            done(null)
            return
        }
        Snapshots.capture(view, snapHandler) { bitmap ->
            val jpeg = bitmap?.let { Snapshots.jpeg(it).also { _ -> it.recycle() } }
            done(jpeg)
        }
    }

    /** From GameNative's menu: the game is paused until the menu closes, so the editor opens a moment later. */
    fun openEditor(): Boolean {
        if (uiLayout?.modSeen != true) return false
        handler.postDelayed({ showEditor() }, 400)
        return true
    }

    fun openCalibration(): Boolean {
        if (regionView) return false
        handler.postDelayed({ showCalibration() }, 400)
        return true
    }

    val pcInfo: Pair<String?, String>? get() = pc?.let { it.url to it.code }

    fun startPc(showCard: Boolean): Pair<String?, String>? {
        if (uiLayout == null) return null
        val server = pc ?: PcEditorServer(context, this).takeIf { it.start() }?.also {
            pc = it
            it.onStopped = { if (overlay is PcEditorPanel) hideOverlay() }
        } ?: return null
        if (showCard) showPcCard(server)
        return server.url to server.code
    }

    fun stopPc() {
        pc?.stop()
        pc = null
        if (overlay is PcEditorPanel) hideOverlay()
    }

    /** Starts the PC editor (or shows it again) and its card with the address and code. */
    private fun showPc() {
        startPc(showCard = true)
    }

    private fun showPcCard(server: PcEditorServer) {
        showOverlay(
            PcEditorPanel(
                context, server.url, server.code,
                onHide = ::hideOverlay,
                onStop = ::stopPc,
            ),
        )
    }

    private fun showMenu() {
        if (overlay != null) return
        // The fingers that opened the menu must not finish as a tap or drag in the game.
        endGesture(cancel = true)
        gesture = Gesture.IGNORED
        try {
            showOverlay(
                DuoMenuPanel(
                    context,
                    canEditUi = uiLayout?.canEdit == true,
                    onEditUi = ::showEditor,
                    onCalibrate = ::showCalibration,
                    onClose = ::hideOverlay,
                    onChooseRegion = if (regionView) ::showRegion else null,
                    onEditOnPc = if (uiLayout != null) ::showPc else null,
                    pcRunning = pc != null,
                ),
            )
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to open the bottom screen menu")
        }
    }

    private fun showEditor() {
        val ui = uiLayout ?: return
        try {
            val e = UiLayoutEditor(
                context, ui, topRect, rect, widgets,
                onPickImage = ::pickImage,
                onSnapshotBackground = ::snapshotBackground,
                onDone = ::hideOverlay,
            )
            editor = e
            showOverlay(e)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to open the UI layout editor")
            hideOverlay()
        }
    }

    /** Pan and zoom what the bottom screen shows (region view), live. */
    private fun showRegion() {
        try {
            val w = xServer.screenInfo.width.toInt()
            val h = xServer.screenInfo.height.toInt()
            val (sw, sh) = surfaceSize ?: return
            showOverlay(
                RegionPanel(
                    context, rect, w, h, sw.toFloat() / sh,
                    onChange = ::setRegion,
                    onDone = { pixels ->
                        hideOverlay()
                        if (pixels != null) setRegion(pixels)
                        onRegionDone(pixels)
                    },
                ),
            )
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to open the region view editor")
        }
    }

    /** Shows another part of the frame: same surface, only the crop changes (cheap in the renderer). */
    private fun setRegion(pixels: FloatArray) {
        rect = pixels
        val h = holder ?: return
        val (width, height) = surfaceSize ?: return
        try {
            DuoViewport.transform(rect, width, height, xServer.screenInfo.width.toInt(), xServer.screenInfo.height.toInt())
                ?.let { topRenderer.duoSetSecondary(h.surface, it) }
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to move the region view")
        }
    }

    private fun showCalibration() {
        try {
            showOverlay(
                ScreenCalibrationPanel(
                    context,
                    calibration,
                    calibrationDefaults,
                    onChange = {
                        calibration = it
                        onCalibrationChange(it)
                    },
                    onDone = {
                        calibration = it
                        onCalibrationDone(it)
                        hideOverlay()
                    },
                ),
            )
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to open screen calibration")
        }
    }

    override fun onStop() {
        handler.removeCallbacks(openMenu)
        endGesture(cancel = true)
        uiLayout?.listeners?.remove(layoutListener)
        pc?.stop()
        pc = null
        snapThread?.quitSafely()
        snapThread = null
        snapHandlerOrNull = null
        super.onStop()
    }

    // ---- touch -------------------------------------------------------------------------------------------------

    private fun onTouch(width: Int, height: Int, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> gesture = if (bridge?.hasMod(DuoBridge.CAP_TOUCH) == true) Gesture.NATIVE else Gesture.MOUSE
            // Two fingers held still for a second open the menu.
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                for (i in 0..1) {
                    menuStart[i * 2] = event.getX(i)
                    menuStart[i * 2 + 1] = event.getY(i)
                }
                handler.postDelayed(openMenu, MENU_HOLD_MS)
            } else {
                handler.removeCallbacks(openMenu)
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 2 && fingersMoved(event)) handler.removeCallbacks(openMenu)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(openMenu)
        }
        when (gesture) {
            Gesture.NATIVE -> nativeTouch(width, height, event)
            Gesture.MOUSE -> mouseTouch(width, height, event)
            Gesture.IGNORED, Gesture.NONE -> {}
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) gesture = Gesture.NONE
        return true
    }

    private fun fingersMoved(event: MotionEvent): Boolean = (0..1).any { i ->
        Math.hypot((event.getX(i) - menuStart[i * 2]).toDouble(), (event.getY(i) - menuStart[i * 2 + 1]).toDouble()) > slop
    }

    /** Every finger goes to the game's UI as a touch at the matching window position (multi-touch, drags). */
    private fun nativeTouch(width: Int, height: Int, event: MotionEvent) {
        val b = bridge ?: return
        fun send(action: Int, index: Int) {
            val id = event.getPointerId(index)
            if (id >= MAX_FINGERS) return
            if (!DuoViewport.surfaceToScreenUnbounded(rect, width, height, event.getX(index), event.getY(index), point)) return
            b.sendTouch(action, id, point[0], point[1])
            fingerX[id] = point[0]
            fingerY[id] = point[1]
            val down = action == DuoBridge.TOUCH_DOWN || action == DuoBridge.TOUCH_MOVE
            fingersDown = if (down) fingersDown or (1 shl id) else fingersDown and (1 shl id).inv()
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> send(DuoBridge.TOUCH_DOWN, event.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) send(DuoBridge.TOUCH_MOVE, i)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> send(DuoBridge.TOUCH_UP, event.actionIndex)
            MotionEvent.ACTION_CANCEL -> for (i in 0 until event.pointerCount) send(DuoBridge.TOUCH_CANCEL, i)
        }
    }

    /** Fingers down in the game (bit per pointer id) and where they last were, to cancel them if the gesture is taken away. */
    private var fingersDown = 0
    private val fingerX = FloatArray(MAX_FINGERS)
    private val fingerY = FloatArray(MAX_FINGERS)

    /** The first finger clicks and drags with the left mouse button; a second finger never drags in the game. */
    private fun mouseTouch(width: Int, height: Int, event: MotionEvent) {
        val pos = DuoViewport.surfaceToScreen(rect, width, height, event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (buttonDown) {
                inject { xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT) }
                buttonDown = false
            }
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!buttonDown) return
                inject {
                    if (pos != null) xServer.injectPointerMove(pos.first, pos.second)
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                }
                buttonDown = false
            }
        }
    }

    /** Ends the current gesture in the game: lifts the mouse button, or cancels native touches (no click). */
    private fun endGesture(cancel: Boolean) {
        if (buttonDown) inject { xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT) }
        buttonDown = false
        val b = bridge
        if (b != null && cancel) {
            for (id in 0 until MAX_FINGERS) {
                if (fingersDown and (1 shl id) != 0) b.sendTouch(DuoBridge.TOUCH_CANCEL, id, fingerX[id], fingerY[id])
            }
        }
        fingersDown = 0
        gesture = Gesture.NONE
    }

    private inline fun inject(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to inject bottom screen touch")
        }
    }

    private companion object {
        const val MENU_HOLD_MS = 900L
        const val MAX_FINGERS = 16
    }
}
