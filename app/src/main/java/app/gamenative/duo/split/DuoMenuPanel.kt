package app.gamenative.duo.split

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.gamenative.duo.ui.DuoUi
import kotlin.math.roundToInt

/**
 * The bottom screen's in-game menu (two-finger long press): edit the game's UI layout here or on a PC, line up the
 * screens. Tapping outside the card closes it. Swallows all touches while open.
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
    /** Opens the PC editor (null where it is unavailable); [pcRunning] when it is already on. */
    onEditOnPc: (() -> Unit)? = null,
    pcRunning: Boolean = false,
) : FrameLayout(context) {
    init {
        setBackgroundColor(Color.argb(120, 0, 0, 0))
        isClickable = true
        setOnClickListener { onClose() }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = DuoUi.panelBackground(context)
            isClickable = true // taps on the card do not close it
        }
        card.addView(DuoUi.label(context, "GameNative Duo", 18f, Color.WHITE, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
        fun item(text: String, enabled: Boolean = true, onClick: () -> Unit) {
            card.addView(
                DuoUi.chip(context, text, onClick).apply { isEnabled = enabled },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dp(4), 0, dp(4))
                },
            )
        }
        if (onChooseRegion != null) {
            item("Choose bottom view") { onChooseRegion() }
        } else {
            item("Edit UI layout", canEditUi) { onEditUi() }
            onEditOnPc?.let { item(if (pcRunning) "PC editor (on)" else "Edit on PC", canEditUi) { it() } }
            if (!canEditUi) card.addView(DuoUi.label(context, "Needs this game's GameNative Duo mod, running.", 12f, DuoUi.MUTED))
            // Lining the screens up only matters when the game continues across both (not in region view).
            item("Line up screens") { onCalibrate() }
        }
        card.addView(View(context), LinearLayout.LayoutParams(1, dp(4)))
        item("Close") { onClose() }
        addView(card, LayoutParams(dp(320), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
