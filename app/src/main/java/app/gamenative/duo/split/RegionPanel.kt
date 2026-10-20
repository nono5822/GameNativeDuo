package app.gamenative.duo.split

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Choosing what the bottom screen shows in region view: drag to pan, pinch to zoom, on the live picture (the renderer
 * redraws the bottom screen right away; no copy is involved). [onChange] gets every new region in screen pixels,
 * [onDone] the final one. Swallows all touches while open.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class RegionPanel(
    context: Context,
    initial: FloatArray,
    private val screenW: Int,
    private val screenH: Int,
    private val aspect: Float,
    private val onChange: (FloatArray) -> Unit,
    private val onDone: (FloatArray?) -> Unit,
) : FrameLayout(context) {
    private var region = initial.copyOf()
    private var lastX = 0f
    private var lastY = 0f
    private var lastDistance = 0f

    init {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.argb(200, 20, 20, 24))
                cornerRadius = dp(14).toFloat()
            }
            addView(
                TextView(context).apply {
                    text = "Drag to move, pinch to zoom"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(button("Reset") { onDone(null) })
            addView(button("Done") { onDone(region) })
        }
        val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        params.setMargins(dp(8), dp(8), dp(8), dp(8))
        addView(bar, params)
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width == 0 || height == 0) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // Restart from the fingers now down, so nothing jumps.
                lastDistance = if (event.pointerCount >= 2) distance(event) else 0f
                val keep = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) 1 - event.actionIndex.coerceAtMost(1) else 0
                lastX = event.getX(keep)
                lastY = event.getY(keep)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && lastDistance > 0f) {
                    val d = distance(event)
                    val mx = (event.getX(0) + event.getX(1)) / 2f
                    val my = (event.getY(0) + event.getY(1)) / 2f
                    region = RegionView.zoom(region, d / lastDistance, mx / width, my / height, screenW, screenH, aspect)
                    lastDistance = d
                } else {
                    // The picture follows the finger: the region moves the other way.
                    val perPixel = region[2] / width
                    region = RegionView.pan(region, -(event.x - lastX) * perPixel, -(event.y - lastY) * perPixel, screenW, screenH)
                    lastX = event.x
                    lastY = event.y
                }
                onChange(region)
            }
        }
        return true
    }

    private fun distance(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)

    private fun button(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
