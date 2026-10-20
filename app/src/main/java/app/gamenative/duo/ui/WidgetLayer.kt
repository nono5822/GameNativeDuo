package app.gamenative.duo.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.view.View
import app.gamenative.powercontrol.PowerManager
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * The layout's widgets (FPS, CPU, battery...) on the bottom screen, above the game. Reads values GameNative already
 * measures for the session (PowerManager's metrics, sampled every 500 ms) and redraws once a second, only while at
 * least one widget is placed; with none it is gone and costs nothing. Touches pass through to the game.
 */
@SuppressLint("ViewConstructor")
internal class WidgetLayer(context: Context) : View(context) {
    private var widgets: List<UiWidget> = emptyList()
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val clock = DateFormat.getTimeInstance(DateFormat.SHORT)
    private val density = resources.displayMetrics.density
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(185, 12, 15, 30) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val box = RectF()
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            if (widgets.isNotEmpty() && isAttachedToWindow) postDelayed(this, 1000)
        }
    }

    init {
        isClickable = false
        isFocusable = false
        visibility = GONE
    }

    fun show(list: List<UiWidget>) {
        widgets = list
        removeCallbacks(tick)
        visibility = if (list.isEmpty()) GONE else VISIBLE
        if (list.isNotEmpty()) tick.run()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (widgets.isNotEmpty()) tick.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        widgets.forEach { w ->
            if (bounds(w, box)) {
                val r = box.height() / 2f
                canvas.drawRoundRect(box, r, r, pill)
                val (label, value) = text(w.type)
                val size = TEXT_DP * density * w.scale
                labelPaint.textSize = size * 0.8f
                valuePaint.textSize = size
                val pad = PAD_DP * density * w.scale
                val baseline = box.centerY() + size * 0.36f
                canvas.drawText(label, box.left + pad, baseline, labelPaint)
                canvas.drawText(value, box.left + pad + labelPaint.measureText(label) + pad * 0.6f, baseline, valuePaint)
            }
        }
    }

    /** The widget's box in view pixels, kept on screen. Used by the editors to draw and hit-test widgets. */
    fun bounds(w: UiWidget, out: RectF): Boolean {
        if (width == 0 || height == 0) return false
        val size = TEXT_DP * density * w.scale
        labelPaint.textSize = size * 0.8f
        valuePaint.textSize = size
        val pad = PAD_DP * density * w.scale
        val (label, value) = text(w.type, sample = true)
        val boxW = pad * 2.6f + labelPaint.measureText(label) + valuePaint.measureText(value)
        val boxH = size * 1.9f
        val left = (w.x * width).coerceIn(0f, (width - boxW).coerceAtLeast(0f))
        val top = (w.y * height).coerceIn(0f, (height - boxH).coerceAtLeast(0f))
        out.set(left, top, left + boxW, top + boxH)
        return true
    }

    /** Label and value; [sample] gives the widest value, so a box keeps its size as the number changes. */
    private fun text(type: UiWidgetType, sample: Boolean = false): Pair<String, String> {
        val m = PowerManager.latestMetrics
        return when (type) {
            UiWidgetType.FPS -> "FPS" to if (sample) "000" else m?.fps?.roundToInt()?.toString() ?: "--"
            UiWidgetType.CPU -> "CPU" to if (sample) "100%" else m?.cpuUsagePercent?.let { "${it.roundToInt()}%" } ?: "--"
            UiWidgetType.GPU -> "GPU" to if (sample) "100%" else m?.gpuUsagePercent?.let { "${it.roundToInt()}%" } ?: "--"
            UiWidgetType.TEMPERATURE -> "TEMP" to if (sample) "100°C" else m?.cpuTempC?.let { "$it°C" } ?: "--"
            UiWidgetType.BATTERY ->
                "BAT" to
                    if (sample) "100%" else battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.let { "$it%" } ?: "--"
            UiWidgetType.CLOCK -> "TIME" to if (sample) "00:00 PM" else clock.format(Date())
        }
    }

    companion object {
        /** The logo blue (see duo/branding). */
        val ACCENT = Color.rgb(0x5B, 0x8D, 0xF6)
        private const val TEXT_DP = 13f
        private const val PAD_DP = 9f
    }
}
