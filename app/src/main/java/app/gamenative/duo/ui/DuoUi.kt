package app.gamenative.duo.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The look of GameNative Duo's own panels on the bottom screen (layout editor, PC editor card): dark rounded cards,
 * pill buttons, the logo blue as the accent. Plain views, nothing drawn when nothing changes.
 */
internal object DuoUi {
    /** The logo blue and its quieter variants (see duo/branding). */
    val ACCENT = Color.rgb(0x5B, 0x8D, 0xF6)
    val ACCENT_DIM = Color.argb(140, 0x5B, 0x8D, 0xF6)
    val PANEL = Color.argb(238, 13, 16, 30)
    val CHIP = Color.rgb(36, 42, 66)
    val CHIP_PRESSED = Color.rgb(52, 60, 92)
    val MUTED = Color.rgb(160, 168, 196)

    fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).roundToInt()

    fun panelBackground(context: Context) = GradientDrawable().apply {
        setColor(PANEL)
        cornerRadius = dp(context, 18).toFloat()
        setStroke(dp(context, 1), Color.argb(60, 255, 255, 255))
    }

    fun label(context: Context, text: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    /** A pill button. [Chip.on] fills it with the accent; disabled chips fade. */
    class Chip(context: Context, text: String, onClick: () -> Unit) : TextView(context) {
        private val shape = GradientDrawable().apply { cornerRadius = dp(context, 20).toFloat() }
        var on = false
            set(value) {
                field = value
                refresh()
            }

        init {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            minHeight = dp(context, 40)
            minWidth = dp(context, 44)
            setPadding(dp(context, 14), 0, dp(context, 14), 0)
            background = shape
            isClickable = true
            isFocusable = true
            setOnClickListener { if (isEnabled) onClick() }
            refresh()
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            refresh()
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            refresh()
        }

        private fun refresh() {
            shape.setColor(
                when {
                    on -> ACCENT
                    isPressed -> CHIP_PRESSED
                    else -> CHIP
                },
            )
            alpha = if (isEnabled) 1f else 0.38f
            setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    fun chip(context: Context, text: String, onClick: () -> Unit) = Chip(context, text, onClick)

    /** Views in a row with small gaps. */
    fun row(context: Context, vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { add(this, it) }
    }

    fun add(row: LinearLayout, view: View, weight: Float = 0f) {
        val gap = dp(row.context, 6)
        val width = if (weight > 0f) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
        // A plain View measured "wrap content" takes all the height it is offered: spacers get one pixel.
        val height = if (view.javaClass == View::class.java) 1 else LinearLayout.LayoutParams.WRAP_CONTENT
        val params = LinearLayout.LayoutParams(width, height, weight)
        params.setMargins(0, gap / 2, gap, gap / 2)
        row.addView(view, params)
    }

    /** A 0..1 slider in the accent color; [onChange] gets (value, fromUser and finished). */
    fun slider(context: Context, value: Float, onChange: (Float, Boolean) -> Unit) = SeekBar(context).apply {
        max = 100
        progress = (value * 100).roundToInt()
        progressTintList = ColorStateList.valueOf(ACCENT)
        thumbTintList = ColorStateList.valueOf(ACCENT)
        minimumHeight = dp(context, 40)
        setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChange(progress / 100f, false)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}

                override fun onStopTrackingTouch(seekBar: SeekBar) = onChange(seekBar.progress / 100f, true)
            },
        )
    }
}
