package app.gamenative.duo.split

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The bottom screen's in-game menu (two-finger long press): edit the game's UI layout, line up the screens.
 * Tapping outside the card closes it. Swallows all touches while open.
 */
@SuppressLint("ViewConstructor")
internal class DuoMenuPanel(
    context: Context,
    canEditUi: Boolean,
    onEditUi: () -> Unit,
    onCalibrate: () -> Unit,
    onClose: () -> Unit,
    /** Region view only: pan and zoom what the bottom screen shows. */
    onChooseRegion: (() -> Unit)? = null,
) : FrameLayout(context) {
    init {
        setBackgroundColor(Color.argb(110, 0, 0, 0))
        isClickable = true
        setOnClickListener { onClose() }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 20, 20, 24))
                cornerRadius = dp(16).toFloat()
            }
            isClickable = true // taps on the card do not close it
            addView(text("GameNative Duo", 17f, Color.WHITE))
            if (onChooseRegion != null) {
                addView(button("Choose bottom view") { onChooseRegion() })
            } else {
                addView(button("Edit UI layout") { onEditUi() }.apply { isEnabled = canEditUi })
                if (!canEditUi) addView(text("Needs this game's GameNative Duo mod, running.", 12f, Color.LTGRAY))
            }
            addView(button("Line up screens") { onCalibrate() })
            addView(button("Close") { onClose() })
        }
        addView(card, LayoutParams(dp(300), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun text(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
