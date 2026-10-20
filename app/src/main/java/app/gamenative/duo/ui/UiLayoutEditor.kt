package app.gamenative.duo.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Edits the game's UI layout on the bottom screen while the game runs. Tap an element to select it ("Parent" widens
 * the selection to its group, "List" shows everything, hidden elements too), drag to move it, pinch to resize it,
 * and send it to the bottom screen, the top screen, or nowhere. Elements on the top screen are edited in the
 * "Top screen" map. Every change reaches the game within a frame (throttled while a finger moves) and is saved
 * per game when the finger lifts.
 *
 * Draws only when something changes; costs nothing while idle. Swallows all touches while open.
 *
 * [topRect] and [bottomRect] are the game-window rectangles shown on each screen (`{x, y, w, h}`).
 */
@SuppressLint("ViewConstructor")
internal class UiLayoutEditor(
    context: Context,
    private val controller: UiLayoutController,
    private val topRect: FloatArray,
    private val bottomRect: FloatArray,
    /** The bottom screen's widgets (null where widgets are unavailable): drawn there, edited here. */
    private val widgetLayer: WidgetLayer?,
    /** Opens the system's image picker for the background (null when it cannot be shown). */
    private val onPickImage: (() -> Unit)?,
    /** Uses the top screen's current picture as the background (null when unavailable). */
    private val onSnapshotBackground: (() -> Unit)?,
    private val onDone: () -> Unit,
) : FrameLayout(context) {
    private var elements: UiElementList? = null
    private var selectedId: String? = null
    private var status = "Loading the game's UI…"
    private val board = Board()
    private val toolbar = LinearLayout(context)
    private val list = ListView(context)
    private val listPanel = LinearLayout(context)
    private lateinit var listAdapter: BaseAdapter
    private val title = label(15f, Color.WHITE)
    private val sizeLabel = label(14f, Color.WHITE)
    private val screenButtons = mutableMapOf<UiScreen, DuoUi.Chip>()
    private var toolbarAtTop = false
    private var toolbarPlace = -1
    private lateinit var controls: LinearLayout

    // ---- data ------------------------------------------------------------------------------------------------

    // Unanswered requests in a row: GameNative pauses the game while its own menu is open, so the editor (opened from
    // that menu) keeps asking for a while before it gives up.
    private var unanswered = 0

    private fun refresh() {
        controller.requestElements { result ->
            if (result == null) {
                unanswered++
                if (unanswered <= MAX_WAIT_TRIES && isAttachedToWindow) {
                    status = "Waiting for the game to answer..."
                    postDelayed({ if (isAttachedToWindow) refresh() }, 1500)
                } else {
                    status = "The game's mod did not answer. Is the game still running?"
                }
            } else {
                unanswered = 0
                elements = result
                board.chooseMapSide()
                status = if (result.elements.isEmpty()) "No UI on screen right now" else "Tap a UI element to change it"
                board.clearPreview()
                if (result.byId(selectedId) == null) selectedId = null
            }
            placeToolbar()
            updateToolbar()
            listAdapter.notifyDataSetChanged()
            board.invalidate()
        }
    }

    private val selected: UiElement? get() = elements?.byId(selectedId)

    private fun select(e: UiElement?) {
        selectedId = e?.id
        updateToolbar()
        placeToolbar()
        board.invalidate()
    }

    /**
     * Applies [rule] to element [id]; [final] also saves it and fetches the new positions. A rule without a screen
     * pins the one the element is on now (the mod may have chosen it, e.g. crosshairs on top), so a saved layout
     * means the same thing everywhere.
     */
    private fun change(id: String, rule: UiRule, final: Boolean) {
        val onScreen = elements?.byId(id)?.screen?.takeIf { it == UiScreen.TOP || it == UiScreen.BOTTOM }
        val pinned = if (rule.screen == UiScreen.DEFAULT && !rule.isNeutral && onScreen != null) rule.copy(screen = onScreen) else rule
        controller.set(id, pinned, save = final)
        if (final) {
            updateToolbar()
            postDelayed({ refresh() }, 120)
        }
    }

    /** Where the element really is now: its rule, or the screen the mod put it on. */
    private fun effectiveScreen(e: UiElement): UiScreen {
        val rule = controller.layout.rule(e.id).screen
        return if (rule != UiScreen.DEFAULT) rule else e.screen
    }

    // ---- toolbar: tabs, then the current tab's controls --------------------------------------------------------

    private enum class Tab(val label: String) { ELEMENTS("Elements"), BACKGROUND("Background"), WIDGETS("Widgets") }

    private var tab = Tab.ELEMENTS
    private val tabChips = mutableMapOf<Tab, DuoUi.Chip>()
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun buildToolbar() {
        toolbar.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = DuoUi.panelBackground(context)
            isClickable = true
        }
        val header = DuoUi.row(context)
        Tab.entries.forEach { t -> DuoUi.add(header, DuoUi.chip(context, t.label) { showTab(t) }.also { tabChips[t] = it }) }
        DuoUi.add(header, View(context), weight = 1f)
        DuoUi.add(header, DuoUi.chip(context, "Done") { onDone() })
        toolbar.addView(header)
        toolbar.addView(content)
        addView(toolbar)
        showTab(Tab.ELEMENTS)
    }

    private fun showTab(t: Tab) {
        tab = t
        tabChips.forEach { (k, chip) -> chip.on = k == t }
        content.removeAllViews()
        when (t) {
            Tab.ELEMENTS -> buildElementControls()
            Tab.BACKGROUND -> buildBackgroundControls()
            Tab.WIDGETS -> buildWidgetControls()
        }
        placeToolbar(force = true)
        updateToolbar()
        board.invalidate()
    }

    private fun buildElementControls() {
        title.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }
        val top = DuoUi.row(context)
        DuoUi.add(top, title, weight = 1f)
        DuoUi.add(top, DuoUi.chip(context, "Parent") { selected?.let { e -> elements?.parentOf(e)?.let { select(it) } } })
        DuoUi.add(top, DuoUi.chip(context, "List") { showList(true) })
        content.addView(top)
        controls = DuoUi.row(context)
        screenButtons.clear()
        listOf(UiScreen.BOTTOM to "Bottom", UiScreen.TOP to "Top", UiScreen.HIDDEN to "Hide").forEach { (screen, text) ->
            DuoUi.add(
                controls,
                DuoUi.chip(context, text) {
                    val e = selected ?: return@chip
                    val old = controller.layout.rule(e.id)
                    // Offsets are relative to a screen, so they start over on the new one.
                    val rule = if (old.screen == screen) old else old.copy(screen = screen, x = 0f, y = 0f)
                    change(e.id, rule, final = true)
                }.also { screenButtons[screen] = it },
            )
        }
        DuoUi.add(controls, View(context), weight = 1f)
        DuoUi.add(controls, DuoUi.chip(context, "−") { stepScale(1f / 1.1f) })
        DuoUi.add(controls, sizeLabel)
        DuoUi.add(controls, DuoUi.chip(context, "+") { stepScale(1.1f) })
        DuoUi.add(controls, DuoUi.chip(context, "Reset") { selected?.let { change(it.id, UiRule(), final = true) } })
        content.addView(controls)
    }

    // ---- background tab --------------------------------------------------------------------------------------

    private val modeChips = mutableMapOf<UiBackgroundMode, DuoUi.Chip>()

    private fun buildBackgroundControls() {
        val bg = controller.layout.background
        val modes = DuoUi.row(context)
        modeChips.clear()
        UiBackgroundMode.entries.forEach { mode ->
            DuoUi.add(
                modes,
                DuoUi.chip(context, mode.label) {
                    // Image without a picture yet: show its options (gallery, snapshot) first, change nothing.
                    if (mode == UiBackgroundMode.IMAGE && !controller.layout.background.image) {
                        imageChoice = true
                    } else {
                        imageChoice = false
                        setBackground(controller.layout.background.copy(mode = mode), save = true)
                    }
                    showTab(Tab.BACKGROUND)
                }.also {
                    it.isEnabled = controller.supports(mode)
                    it.on = if (imageChoice) mode == UiBackgroundMode.IMAGE else bg.mode == mode
                    modeChips[mode] = it
                },
            )
        }
        content.addView(modes)
        if (bg.mode != UiBackgroundMode.GAME && !controller.supports(bg.mode)) {
            val note = "This game's mod cannot draw this background yet: the bottom screen keeps its default."
            content.addView(DuoUi.label(context, note, 13f, DuoUi.MUTED))
        }
        when (if (imageChoice) UiBackgroundMode.IMAGE else bg.mode) {
            UiBackgroundMode.BLUR -> content.addView(
                sliderRow("Blur", bg.blur) { v, done ->
                    setBackground(controller.layout.background.copy(blur = v), done)
                },
            )
            UiBackgroundMode.COLOR -> {
                val swatches = DuoUi.row(context)
                SWATCHES.forEach { color ->
                    DuoUi.add(
                        swatches,
                        // A View subclass: DuoUi.add squashes plain Views (spacers) to one pixel.
                        object : View(context) {}.apply {
                            background = GradientDrawable().apply {
                                setColor(color)
                                cornerRadius = dp(18).toFloat()
                                setStroke(
                                    dp(
                                        if (color ==
                                            bg.color
                                        ) {
                                            3
                                        } else {
                                            1
                                        },
                                    ),
                                    if (color == bg.color) DuoUi.ACCENT else Color.argb(90, 255, 255, 255),
                                )
                            }
                            minimumWidth = dp(40)
                            minimumHeight = dp(40)
                            setOnClickListener {
                                setBackground(controller.layout.background.copy(color = color), save = true)
                                showTab(Tab.BACKGROUND)
                            }
                        },
                    )
                }
                content.addView(swatches)
            }
            UiBackgroundMode.IMAGE -> {
                val row = DuoUi.row(context)
                onPickImage?.let { pick -> DuoUi.add(row, DuoUi.chip(context, "Choose image") { pick() }) }
                onSnapshotBackground?.let { snap -> DuoUi.add(row, DuoUi.chip(context, "Use a snapshot of the game") { snap() }) }
                content.addView(row)
            }
            UiBackgroundMode.GAME -> {}
        }
        if (bg.mode != UiBackgroundMode.COLOR) {
            content.addView(
                sliderRow("Dim", bg.dim / MAX_DIM) { v, done ->
                    setBackground(
                        controller.layout.background.copy(
                            dim =
                            v * MAX_DIM,
                        ),
                        done,
                    )
                },
            )
        }
    }

    /** Image chosen but no picture yet: its options are shown, the background is unchanged until one is picked. */
    private var imageChoice = false

    private fun setBackground(bg: UiBackground, save: Boolean) = controller.setBackground(bg, save)

    private fun sliderRow(name: String, value: Float, onChange: (Float, Boolean) -> Unit): View {
        val row = DuoUi.row(context)
        DuoUi.add(row, DuoUi.label(context, name, 14f, Color.WHITE).apply { minWidth = dp(56) })
        DuoUi.add(row, DuoUi.slider(context, value, onChange), weight = 1f)
        return row
    }

    /** Called back after an image was chosen or captured: show the result. */
    fun onBackgroundChanged() {
        imageChoice = false
        if (tab == Tab.BACKGROUND) showTab(Tab.BACKGROUND)
    }

    // ---- widgets tab -----------------------------------------------------------------------------------------

    private var selectedWidget = -1

    private fun buildWidgetControls() {
        val add = DuoUi.row(context)
        DuoUi.add(add, DuoUi.label(context, "Add", 14f, DuoUi.MUTED))
        UiWidgetType.entries.forEach { type ->
            DuoUi.add(
                add,
                DuoUi.chip(context, type.label) {
                    val list = controller.layout.widgets
                    if (list.size >= UiLayout.MAX_WIDGETS) return@chip
                    // New widgets stack down the left edge; drag them where they belong.
                    val widget = UiWidget(type, 0.03f, (0.04f + 0.09f * list.size).coerceAtMost(0.9f))
                    controller.setWidgets(list + widget, save = true)
                    selectedWidget = list.size
                    showTab(Tab.WIDGETS)
                }.also { it.isEnabled = widgetLayer != null },
            )
        }
        content.addView(add)
        val w = controller.layout.widgets.getOrNull(selectedWidget)
        if (w != null) {
            val row = DuoUi.row(context)
            DuoUi.add(row, DuoUi.label(context, w.type.label, 15f, Color.WHITE, bold = true), weight = 1f)
            DuoUi.add(row, DuoUi.chip(context, "−") { scaleWidget(1f / 1.15f) })
            DuoUi.add(row, DuoUi.label(context, "${(w.scale * 100).roundToInt()}%", 14f, Color.WHITE))
            DuoUi.add(row, DuoUi.chip(context, "+") { scaleWidget(1.15f) })
            DuoUi.add(
                row,
                DuoUi.chip(context, "Remove") {
                    controller.setWidgets(controller.layout.widgets.filterIndexed { i, _ -> i != selectedWidget }, save = true)
                    selectedWidget = -1
                    showTab(Tab.WIDGETS)
                },
            )
            content.addView(row)
        } else {
            content.addView(
                DuoUi.label(
                    context,
                    if (controller.layout.widgets.isEmpty()) {
                        "Live readouts drawn by GameNative Duo over the game."
                    } else {
                        "Tap a widget to resize or remove it, drag it to move it."
                    },
                    13f,
                    DuoUi.MUTED,
                ),
            )
        }
    }

    private fun scaleWidget(factor: Float) {
        val list = controller.layout.widgets.toMutableList()
        val w = list.getOrNull(selectedWidget) ?: return
        list[selectedWidget] = w.copy(scale = (w.scale * factor).coerceIn(UiWidget.SCALE_MIN, UiWidget.SCALE_MAX))
        controller.setWidgets(list, save = true)
        showTab(Tab.WIDGETS)
    }

    /**
     * Never in the way: with a selection the toolbar sits on the edge away from it; without one it goes to the
     * top, middle or bottom, wherever it covers the least of the game's UI.
     */
    private fun placeToolbar(force: Boolean = false) {
        val gravity = when {
            tab == Tab.WIDGETS -> if (board.widgetInLowerHalf()) Gravity.TOP else Gravity.BOTTOM
            tab == Tab.BACKGROUND -> Gravity.BOTTOM
            selected == null -> board.emptiestBand(dp(96).toFloat())
            board.selectionInLowerHalf() -> Gravity.TOP
            else -> Gravity.BOTTOM
        }
        if (gravity == toolbarPlace && !force) return
        toolbarPlace = gravity
        toolbarAtTop = gravity == Gravity.TOP
        if (::controls.isInitialized) controls.visibility = if (selected == null) GONE else VISIBLE
        toolbar.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, gravity)
            .apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
        board.invalidate()
    }

    private fun stepScale(factor: Float) {
        val e = selected ?: return
        val rule = controller.layout.rule(e.id)
        change(e.id, rule.copy(scale = (rule.scale * factor).coerceIn(UiRule.SCALE_MIN, UiRule.SCALE_MAX)), final = true)
    }

    private fun updateToolbar() {
        if (tab != Tab.ELEMENTS) return
        val e = selected
        val rule = e?.let { controller.layout.rule(it.id) }
        title.text = e?.let { it.name.ifEmpty { it.id.substringAfterLast('/') } } ?: status
        sizeLabel.text = rule?.let { "${(it.scale * 100).roundToInt()}%" } ?: "-"
        val current = e?.let { effectiveScreen(it) }
        screenButtons.forEach { (screen, b) ->
            b.isEnabled = e != null
            b.on = current == screen
        }
        if (::controls.isInitialized) controls.visibility = if (e == null) GONE else VISIBLE
    }

    // ---- element list (reaches hidden and tiny elements) ------------------------------------------------------

    private fun buildList() {
        list.apply {
            listAdapter = object : BaseAdapter() {
                override fun getCount() = elements?.elements?.size ?: 0

                override fun getItem(position: Int) = elements!!.elements[position]

                override fun getItemId(position: Int) = position.toLong()

                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val v = (convertView as? TextView) ?: label(14f, Color.WHITE).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    }
                    val e = getItem(position)
                    val rule = controller.layout.rule(e.id)
                    val tag = when (effectiveScreen(e)) {
                        UiScreen.TOP -> "  ▲ top"
                        UiScreen.HIDDEN -> "  ⊘ hidden"
                        else -> ""
                    } + if (!rule.isNeutral) "  •" else ""
                    v.text = e.name + tag
                    v.setPaddingRelative(dp(12) + dp(14) * min(e.depth, 8), dp(10), dp(12), dp(10))
                    v.setTextColor(
                        when {
                            e.id == selectedId -> Color.CYAN
                            e.graphic -> Color.WHITE
                            else -> Color.LTGRAY
                        },
                    )
                    return v
                }
            }
            adapter = listAdapter
            setOnItemClickListener { _, _, position, _ ->
                select(elements?.elements?.getOrNull(position))
                showList(false)
            }
        }
        listPanel.apply {
            orientation = LinearLayout.VERTICAL
            visibility = GONE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = DuoUi.panelBackground(context)
            addView(
                DuoUi.row(
                    context,
                    DuoUi.chip(context, "Close list") { showList(false) },
                    DuoUi.chip(context, "Reset all") {
                        controller.resetAll()
                        showList(false)
                        updateToolbar()
                        postDelayed({ refresh() }, 120)
                    },
                ),
            )
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val listParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        listParams.setMargins(dp(8), dp(8), dp(8), dp(8))
        addView(listPanel, listParams)
    }

    private fun showList(show: Boolean) {
        listPanel.visibility = if (show) VISIBLE else GONE
        toolbar.visibility = if (show) GONE else VISIBLE
        if (show) {
            listAdapter.notifyDataSetChanged()
            elements?.elements?.indexOfFirst { it.id == selectedId }?.takeIf { it >= 0 }?.let { list.setSelection(it) }
        }
    }

    // ---- the board: element boxes, the top-screen map, gestures -----------------------------------------------

    private inner class Board : View(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val minTarget = dp(40).toFloat()
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
            color = Color.argb(150, 0, 0, 0)
        }
        private val fillPaint = Paint().apply { style = Paint.Style.FILL }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(12).toFloat()
        }
        private val tmp = RectF()
        private val map = RectF()
        private var mapOnLeft = false

        // Gesture state. Preview: offset (view px) and scale applied to the selection's box until fresh positions arrive.
        private var mode = Mode.NONE
        private var downX = 0f
        private var downY = 0f
        private var startDistance = 1f
        private var startRule = UiRule()
        private var previewDx = 0f
        private var previewDy = 0f
        private var previewScale = 1f
        private var baseDx = 0f
        private var baseDy = 0f
        private var baseScale = 1f
        private var lastPush = 0L

        // ---- widgets (Widgets tab) ---------------------------------------------------------------------------

        private var widgetStart: UiWidget? = null

        fun widgetInLowerHalf(): Boolean {
            val w = controller.layout.widgets.getOrNull(selectedWidget) ?: return false
            return widgetLayer?.bounds(w, tmp) == true && tmp.centerY() > height / 2f
        }

        private fun widgetAt(x: Float, y: Float): Int {
            val layer = widgetLayer ?: return -1
            val list = controller.layout.widgets
            for (i in list.indices.reversed()) {
                if (layer.bounds(list[i], tmp)) {
                    tmp.inset(-slop, -slop)
                    if (tmp.contains(x, y)) return i
                }
            }
            return -1
        }

        private fun drawWidgets(canvas: Canvas) {
            val layer = widgetLayer ?: return
            controller.layout.widgets.forEachIndexed { i, w ->
                if (layer.bounds(w, tmp)) {
                    tmp.inset(-dp(3).toFloat(), -dp(3).toFloat())
                    if (i == selectedWidget) {
                        fillPaint.color = Color.argb(60, 0x5B, 0x8D, 0xF6)
                        canvas.drawRoundRect(tmp, tmp.height() / 2f, tmp.height() / 2f, fillPaint)
                    }
                    boxPaint.color = if (i == selectedWidget) DuoUi.ACCENT else Color.argb(150, 255, 255, 255)
                    boxPaint.strokeWidth = dp(if (i == selectedWidget) 2 else 1).toFloat()
                    canvas.drawRoundRect(tmp, tmp.height() / 2f, tmp.height() / 2f, boxPaint)
                }
            }
        }

        private fun widgetTouch(event: MotionEvent): Boolean {
            val list = controller.layout.widgets
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    val hit = widgetAt(event.x, event.y)
                    if (hit != selectedWidget) {
                        selectedWidget = hit
                        showTab(Tab.WIDGETS)
                    }
                    widgetStart = list.getOrNull(hit)
                    mode = if (widgetStart != null) Mode.DRAG_PENDING else Mode.NONE
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (widgetStart != null && event.pointerCount == 2) {
                    widgetStart = list.getOrNull(selectedWidget)
                    mode = Mode.PINCH
                    startDistance = distance(event).coerceAtLeast(1f)
                }
                MotionEvent.ACTION_MOVE -> {
                    val start = widgetStart ?: return true
                    when (mode) {
                        Mode.DRAG_PENDING -> if (moved(event)) mode = Mode.DRAG
                        Mode.DRAG -> moveWidget(
                            start.copy(
                                x = (start.x + (event.x - downX) / width).coerceIn(0f, 1f),
                                y = (
                                    start.y +
                                        (event.y - downY) / height
                                    ).coerceIn(0f, 1f),
                            ),
                            false,
                        )
                        Mode.PINCH -> if (event.pointerCount >= 2) {
                            val scale = (start.scale * distance(event) / startDistance).coerceIn(UiWidget.SCALE_MIN, UiWidget.SCALE_MAX)
                            moveWidget(start.copy(scale = scale), false)
                        }
                        else -> {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode == Mode.DRAG || mode == Mode.PINCH) {
                        list.getOrNull(selectedWidget)?.let { moveWidget(it, true) }
                        showTab(Tab.WIDGETS)
                    }
                    if (event.actionMasked != MotionEvent.ACTION_POINTER_UP) widgetStart = null
                    mode = Mode.NONE
                }
            }
            return true
        }

        /** Live while the finger moves (the app redraws its own layer, nothing reaches the game), saved at the end. */
        private fun moveWidget(w: UiWidget, final: Boolean) {
            val list = controller.layout.widgets.toMutableList()
            if (selectedWidget !in list.indices) return
            list[selectedWidget] = w
            val now = SystemClock.uptimeMillis()
            if (!final && now - lastPush < 33) return
            lastPush = now
            controller.setWidgets(list, save = final)
            invalidate()
        }

        /** True when the selection's box is in the lower half of the bottom screen (or the top-screen map is low). */
        fun selectionInLowerHalf(): Boolean {
            val e = selected ?: return false
            if (height == 0 || !viewRect(e, tmp)) return false
            return tmp.centerY() > height / 2f
        }

        /** [Gravity.TOP], [Gravity.CENTER_VERTICAL] or [Gravity.BOTTOM]: the band of height [band] over the least UI. */
        fun emptiestBand(band: Float): Int {
            if (height == 0) return Gravity.CENTER_VERTICAL
            val bands = listOf(Gravity.TOP to 0f, Gravity.CENTER_VERTICAL to (height - band) / 2f, Gravity.BOTTOM to height - band)
            return bands.minBy { (_, top) ->
                elements?.elements.orEmpty().sumOf { e ->
                    if (e.screen != UiScreen.BOTTOM || !e.graphic || !viewRect(e, tmp)) {
                        0.0
                    } else {
                        (tmp.width() * (minOf(tmp.bottom, top + band) - maxOf(tmp.top, top)).coerceAtLeast(0f)).toDouble()
                    }
                }
            }.first
        }

        fun clearPreview() {
            previewDx = 0f
            previewDy = 0f
            previewScale = 1f
        }

        // Bottom screen: the game window's lower part, fitted like the renderer shows it.
        private fun bottomScale() = min(width / bottomRect[2], height / bottomRect[3])

        private fun mapScale() = map.width() / topRect[2]

        /** The element's box in view pixels, or false when it is not on a screen the board shows. */
        private fun viewRect(e: UiElement, out: RectF): Boolean {
            val r = e.rect
            when (e.screen) {
                UiScreen.TOP -> {
                    val s = mapScale()
                    out.set(map.left + (r.left - topRect[0]) * s, map.top + (r.top - topRect[1]) * s, 0f, 0f)
                    out.right = out.left + r.width() * s
                    out.bottom = out.top + r.height() * s
                }
                UiScreen.BOTTOM, UiScreen.DEFAULT -> {
                    val s = bottomScale()
                    val ox = (width - bottomRect[2] * s) / 2f
                    val oy = (height - bottomRect[3] * s) / 2f
                    out.set(ox + (r.left - bottomRect[0]) * s, oy + (r.top - bottomRect[1]) * s, 0f, 0f)
                    out.right = out.left + r.width() * s
                    out.bottom = out.top + r.height() * s
                }
                UiScreen.HIDDEN -> return false
            }
            if (e.id == selectedId) {
                val cx = out.centerX() + previewDx
                val cy = out.centerY() + previewDy
                val hw = out.width() / 2f * previewScale
                val hh = out.height() / 2f * previewScale
                out.set(cx - hw, cy - hh, cx + hw, cy + hh)
            }
            return out.width() > 0f && out.height() > 0f
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutMap()

        private fun layoutMap() {
            val mw = width * 0.42f
            val mh = mw * topRect[3] / topRect[2]
            val margin = dp(10).toFloat()
            // At the top, under the toolbar when that is at the top too: always away from a low selection.
            val y = (if (toolbarAtTop) toolbar.bottom.toFloat() else 0f) + margin + dp(16)
            val left = if (mapOnLeft) margin else width - mw - margin
            map.set(left, y, left + mw, y + mh)
        }

        /**
         * The top corner whose map covers less of the bottom screen's UI, so those elements stay tappable. Chosen when
         * the element list arrives, not per frame, so the map does not jump while an element is dragged.
         */
        fun chooseMapSide() {
            if (width == 0) return
            mapOnLeft = false
            layoutMap()
            val right = coveredArea(map)
            mapOnLeft = true
            layoutMap()
            mapOnLeft = coveredArea(map) < right
            invalidate()
        }

        private fun coveredArea(r: RectF): Float = elements?.elements.orEmpty().sumOf { e ->
            val covered = e.screen == UiScreen.BOTTOM && e.graphic && viewRect(e, tmp) && tmp.intersect(r)
            if (covered) (tmp.width() * tmp.height()).toDouble() else 0.0
        }.toFloat()

        override fun onDraw(canvas: Canvas) {
            when (tab) {
                Tab.WIDGETS -> return drawWidgets(canvas)
                Tab.BACKGROUND -> return
                Tab.ELEMENTS -> {}
            }
            layoutMap()
            val list = elements?.elements.orEmpty()
            // Top-screen map.
            fillPaint.color = Color.argb(200, 10, 10, 14)
            canvas.drawRoundRect(map, dp(6).toFloat(), dp(6).toFloat(), fillPaint)
            canvas.drawText("Top screen", map.left + dp(4), map.top - dp(5), textPaint)
            for (e in list) {
                if (e.id == selectedId || !e.graphic || !viewRect(e, tmp)) continue
                // Amber: changed by the layout; white: as the game made it.
                val changed = controller.layout.rules.containsKey(e.id)
                drawBox(canvas, tmp, if (changed) Color.argb(220, 255, 190, 60) else Color.argb(110, 255, 255, 255))
            }
            selected?.let { e ->
                if (viewRect(e, tmp)) {
                    fillPaint.color = Color.argb(50, 0, 220, 255)
                    canvas.drawRect(tmp, fillPaint)
                    drawBox(canvas, tmp, Color.CYAN, 2f)
                }
            }
        }

        private fun drawBox(canvas: Canvas, r: RectF, color: Int, width: Float = 1f) {
            canvas.drawRect(r, shadowPaint)
            boxPaint.color = color
            boxPaint.strokeWidth = dp(1) * width
            canvas.drawRect(r, boxPaint)
        }

        /**
         * The element under (x, y): one the finger is really on beats one it only reaches through the finger-sized
         * padding small elements get; then drawn graphics first, then the smallest.
         */
        private fun hit(x: Float, y: Float): UiElement? {
            val inMap = map.contains(x, y)
            // Under the map: a top-screen element first, else the bottom-screen one beneath the map.
            return hit(x, y, top = inMap) ?: if (inMap) hit(x, y, top = false) else null
        }

        private fun hit(x: Float, y: Float, top: Boolean): UiElement? {
            var best: UiElement? = null
            var bestInside = false
            for (e in elements?.elements.orEmpty()) {
                if ((e.screen == UiScreen.TOP) != top || e.depth == 0 || !viewRect(e, tmp)) continue
                val inside = tmp.contains(x, y)
                // Small elements (a crosshair in the top-screen map is a few pixels) still get a finger-sized target.
                tmp.inset(-((minTarget - tmp.width()) / 2f).coerceAtLeast(0f), -((minTarget - tmp.height()) / 2f).coerceAtLeast(0f))
                if (!tmp.contains(x, y)) continue
                val b = best
                val better = b == null ||
                    (inside && !bestInside) ||
                    (inside == bestInside && ((e.graphic && !b.graphic) || (e.graphic == b.graphic && e.area < b.area)))
                if (better) {
                    best = e
                    bestInside = inside
                }
            }
            return best
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (tab) {
                Tab.WIDGETS -> return widgetTouch(event)
                Tab.BACKGROUND -> return true
                Tab.ELEMENTS -> {}
            }
            val e = selected
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    // A finger that lands on (or next to) the selection drags it; anywhere else it selects.
                    val onSelection = e != null && viewRect(e, tmp) && tmp.apply { inset(-slop, -slop) }.contains(event.x, event.y)
                    mode = if (onSelection) Mode.DRAG_PENDING else Mode.TAP
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e != null && event.pointerCount == 2) {
                    finishGesture(commit = mode == Mode.DRAG)
                    mode = Mode.PINCH
                    startGesture(e)
                    startDistance = distance(event).coerceAtLeast(1f)
                }
                MotionEvent.ACTION_MOVE -> when (mode) {
                    Mode.TAP -> if (moved(event)) mode = Mode.NONE
                    Mode.DRAG_PENDING -> if (moved(event) && e != null) {
                        mode = Mode.DRAG
                        startGesture(e)
                    }
                    Mode.DRAG -> {
                        previewDx = baseDx + event.x - downX
                        previewDy = baseDy + event.y - downY
                        e?.let { push(it, final = false) }
                        invalidate()
                    }
                    Mode.PINCH -> if (event.pointerCount >= 2) {
                        previewScale = baseScale * (distance(event) / startDistance).coerceIn(0.1f, 10f)
                        e?.let { push(it, final = false) }
                        invalidate()
                    }
                    Mode.NONE -> {}
                }
                MotionEvent.ACTION_POINTER_UP -> if (mode == Mode.PINCH) finishGesture(commit = true)
                MotionEvent.ACTION_UP -> {
                    if (mode == Mode.TAP) select(hit(event.x, event.y)) else finishGesture(commit = mode == Mode.DRAG)
                    mode = Mode.NONE
                }
                MotionEvent.ACTION_CANCEL -> finishGesture(commit = false)
            }
            return true
        }

        private fun finishGesture(commit: Boolean) {
            val e = selected
            if ((mode == Mode.DRAG || mode == Mode.PINCH) && e != null) {
                if (commit) push(e, final = true) else controller.set(e.id, startRule, save = false)
            }
            if (!commit) {
                previewDx = baseDx
                previewDy = baseDy
                previewScale = baseScale
            }
            mode = Mode.NONE
            invalidate()
        }

        /**
         * The preview piles up over gestures until fresh positions arrive from the game; a gesture's rule change is
         * only its own part of it.
         */
        private fun startGesture(e: UiElement) {
            startRule = controller.layout.rule(e.id)
            baseDx = previewDx
            baseDy = previewDy
            baseScale = previewScale
        }

        /** Turns the preview into a rule; while a finger moves at most 10 times a second. */
        private fun push(e: UiElement, final: Boolean) {
            val now = SystemClock.uptimeMillis()
            if (!final && now - lastPush < 100) return
            lastPush = now
            // The box is drawn where the game last reported it; offsets are fractions of that screen.
            val onTop = e.screen == UiScreen.TOP
            val pxPerWindowPx = if (onTop) mapScale() else bottomScale()
            val region = if (onTop) topRect else bottomRect
            val rule = startRule.copy(
                x = startRule.x + (previewDx - baseDx) / pxPerWindowPx / region[2],
                y = startRule.y + (previewDy - baseDy) / pxPerWindowPx / region[3],
                scale = (startRule.scale * previewScale / baseScale).coerceIn(UiRule.SCALE_MIN, UiRule.SCALE_MAX),
            )
            change(e.id, rule, final)
        }

        private fun moved(event: MotionEvent) = hypot(event.x - downX, event.y - downY) > slop

        private fun distance(event: MotionEvent) = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    // Last in the class body: Kotlin runs initializers top to bottom, and this uses every property above.
    init {
        isClickable = true // the game gets no touches while editing
        addView(board, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        buildToolbar()
        buildList()
        refresh()
    }

    private enum class Mode { NONE, TAP, DRAG_PENDING, DRAG, PINCH }

    private companion object {
        const val MAX_DIM = 0.9f

        /** Element list retries (1.5 s apart, after each 3 s timeout) before the editor says the mod did not answer. */
        const val MAX_WAIT_TRIES = 4

        /** Background colors to pick from: deep navy (the default), black, slate, the logo's blues and purple. */
        val SWATCHES = intArrayOf(
            UiBackground.DEFAULT_COLOR,
            Color.rgb(0, 0, 0),
            Color.rgb(28, 32, 44),
            Color.rgb(18, 40, 78),
            Color.rgb(28, 64, 120),
            Color.rgb(46, 34, 90),
            Color.rgb(84, 30, 92),
            Color.rgb(24, 52, 40),
        )
    }

    // ---- small view helpers ---------------------------------------------------------------------------------

    private fun label(size: Float, color: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        setPadding(dp(6), 0, dp(6), 0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
