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
import android.widget.Button
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
    private val screenButtons = mutableMapOf<UiScreen, Button>()
    private var toolbarAtTop = false
    private var toolbarPlace = -1
    private lateinit var controls: LinearLayout

    init {
        isClickable = true // the game gets no touches while editing
        addView(board, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        buildToolbar()
        buildList()
        refresh()
    }

    // ---- data ------------------------------------------------------------------------------------------------

    private fun refresh() {
        controller.requestElements { result ->
            if (result == null) {
                status = "The game's mod did not answer. Is the game still running?"
            } else {
                elements = result
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

    // ---- toolbar ---------------------------------------------------------------------------------------------

    private fun buildToolbar() {
        toolbar.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.argb(225, 20, 20, 24))
                cornerRadius = dp(14).toFloat()
            }
        }
        title.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        toolbar.addView(
            row(
                title,
                button("Parent") { selected?.let { e -> elements?.parentOf(e)?.let { select(it) } } },
                button("List") { showList(true) },
                button("Done") { onDone() },
            ),
        )
        controls = row()
        listOf(UiScreen.BOTTOM to "Bottom", UiScreen.TOP to "Top", UiScreen.HIDDEN to "Hide").forEach { (screen, text) ->
            controls.addView(
                button(text) {
                    val e = selected ?: return@button
                    val old = controller.layout.rule(e.id)
                    // Offsets are relative to a screen, so they start over on the new one.
                    val rule = if (old.screen == screen) old else old.copy(screen = screen, x = 0f, y = 0f)
                    change(e.id, rule, final = true)
                }.also { screenButtons[screen] = it },
            )
        }
        controls.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(button("−") { stepScale(1f / 1.1f) })
        controls.addView(sizeLabel)
        controls.addView(button("+") { stepScale(1.1f) })
        controls.addView(button("Reset") { selected?.let { change(it.id, UiRule(), final = true) } })
        toolbar.addView(controls)
        addView(toolbar)
        placeToolbar(force = true)
        updateToolbar()
    }

    /**
     * Never in the way: with a selection the toolbar sits on the edge away from it; without one it shrinks to a row
     * at the top, middle or bottom, wherever it covers the least of the game's UI.
     */
    private fun placeToolbar(force: Boolean = false) {
        val gravity = when {
            selected == null -> board.emptiestBand(dp(64).toFloat())
            board.selectionInLowerHalf() -> Gravity.TOP
            else -> Gravity.BOTTOM
        }
        if (gravity == toolbarPlace && !force) return
        toolbarPlace = gravity
        toolbarAtTop = gravity == Gravity.TOP
        controls.visibility = if (selected == null) GONE else VISIBLE
        toolbar.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, gravity)
            .apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }
        board.invalidate()
    }

    private fun stepScale(factor: Float) {
        val e = selected ?: return
        val rule = controller.layout.rule(e.id)
        change(e.id, rule.copy(scale = (rule.scale * factor).coerceIn(UiRule.SCALE_MIN, UiRule.SCALE_MAX)), final = true)
    }

    private fun updateToolbar() {
        val e = selected
        val rule = e?.let { controller.layout.rule(it.id) }
        title.text = e?.let { it.id.substringAfter('/') } ?: status
        sizeLabel.text = rule?.let { "${(it.scale * 100).roundToInt()}%" } ?: "—"
        val current = e?.let { effectiveScreen(it) }
        screenButtons.forEach { (screen, b) ->
            b.isEnabled = e != null
            b.alpha = if (current == screen) 1f else 0.55f
            b.setTypeface(null, if (current == screen) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
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
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 20, 20, 24))
                cornerRadius = dp(16).toFloat()
            }
            addView(
                row(
                    button("Close list") { showList(false) },
                    button("Reset all") {
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
            // Top-right, under the toolbar when that is at the top too: always away from a low selection.
            val y = (if (toolbarAtTop) toolbar.bottom.toFloat() else 0f) + margin + dp(16)
            map.set(width - mw - margin, y, width - margin, y + mh)
        }

        override fun onDraw(canvas: Canvas) {
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
            var best: UiElement? = null
            var bestInside = false
            for (e in elements?.elements.orEmpty()) {
                if ((e.screen == UiScreen.TOP) != inMap || e.depth == 0 || !viewRect(e, tmp)) continue
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

    private enum class Mode { NONE, TAP, DRAG_PENDING, DRAG, PINCH }

    // ---- small view helpers ---------------------------------------------------------------------------------

    private fun row(vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    }

    private fun label(size: Float, color: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        setPadding(dp(6), 0, dp(6), 0)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        minWidth = dp(44)
        minimumWidth = dp(44)
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
