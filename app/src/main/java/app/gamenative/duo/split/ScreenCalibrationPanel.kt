package app.gamenative.duo.split

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import app.gamenative.duo.display.ScreenCalibration
import kotlin.math.roundToInt

/**
 * Sliders that line the two screens up while the game runs: every change goes straight to [onChange] (which
 * rewrites `layout.json`, picked up live by the game's mod), [onDone] saves. Shown over the lower half of the
 * bottom screen so the area next to the hinge stays visible. Swallows all touches while open.
 */
internal class ScreenCalibrationPanel(
    context: Context,
    initial: ScreenCalibration,
    private val defaults: ScreenCalibration,
    private val onChange: (ScreenCalibration) -> Unit,
    private val onDone: (ScreenCalibration) -> Unit,
) : FrameLayout(context) {
    private var value = initial
    private val rows = mutableListOf<Row>()

    private inner class Row(
        private val label: String,
        private val steps: Int,
        private val get: (ScreenCalibration) -> Int,
        private val set: (ScreenCalibration, Int) -> ScreenCalibration,
        private val format: (ScreenCalibration) -> String,
    ) {
        private val text = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        private val bar = SeekBar(context).apply {
            max = steps
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) update(set(value, progress))
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                },
            )
        }
        val view: View = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text, LinearLayout.LayoutParams(dp(170), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(stepButton("−", -1))
            addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(stepButton("+", 1))
        }

        private fun stepButton(symbol: String, delta: Int) = Button(context).apply {
            text = symbol
            textSize = 18f
            setOnClickListener { update(set(value, (get(value) + delta).coerceIn(0, steps))) }
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(48))
        }

        fun refresh() {
            text.text = "$label  ${format(value)}"
            bar.progress = get(value)
        }
    }

    init {
        val scaleSteps = ((ScreenCalibration.SCALE_MAX - ScreenCalibration.SCALE_MIN) * 1000).roundToInt()
        rows += Row(
            "Gap",
            ScreenCalibration.GAP_RANGE.last - ScreenCalibration.GAP_RANGE.first,
            { it.gapPx - ScreenCalibration.GAP_RANGE.first },
            { c, p -> c.copy(gapPx = ScreenCalibration.GAP_RANGE.first + p) },
            { "${it.gapPx} px" },
        )
        rows += Row(
            "Scale",
            scaleSteps,
            { ((it.bottomScale - ScreenCalibration.SCALE_MIN) * 1000).roundToInt() },
            { c, p -> c.copy(bottomScale = ScreenCalibration.SCALE_MIN + p / 1000f) },
            { "%.3f".format(it.bottomScale) },
        )
        rows += Row(
            "Shift",
            ScreenCalibration.SHIFT_RANGE.last - ScreenCalibration.SHIFT_RANGE.first,
            { it.bottomShiftPx - ScreenCalibration.SHIFT_RANGE.first },
            { c, p -> c.copy(bottomShiftPx = ScreenCalibration.SHIFT_RANGE.first + p) },
            { "${it.bottomShiftPx} px" },
        )

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(215, 20, 20, 24))
                cornerRadius = dp(16).toFloat()
            }
            addView(
                TextView(context).apply {
                    text = "Line up the screens: drag until paths and fences run straight across the hinge"
                    setTextColor(Color.LTGRAY)
                    textSize = 13f
                },
            )
            rows.forEach { addView(it.view) }
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.END
                    addView(textButton("Reset") { update(defaults) })
                    addView(textButton("Done") { onDone(value) })
                },
            )
        }
        val cardParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        cardParams.setMargins(dp(12), 0, dp(12), dp(12))
        addView(card, cardParams)
        isClickable = true // swallow touches meant for the game while calibrating
        rows.forEach { it.refresh() }
    }

    private fun update(new: ScreenCalibration) {
        value = new
        rows.forEach { it.refresh() }
        onChange(value)
    }

    private fun textButton(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
