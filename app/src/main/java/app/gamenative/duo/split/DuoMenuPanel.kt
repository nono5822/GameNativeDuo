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
    /** Why the UI layout editor is unavailable, or null when it can open ([app.gamenative.duo.ui.EditCapabilities]). */
    editUiOff: String?,
    onEditUi: () -> Unit,
    /** Lines the screens up (null in region view: the game does not continue across both screens there). */
    onCalibrate: (() -> Unit)?,
    onClose: () -> Unit,
    /** Opens the PC editor (null where it is unavailable); [pcRunning] when it is already on. */
    onEditOnPc: (() -> Unit)? = null,
    pcRunning: Boolean = false,
    /** Opens the game's companion (null when it has none). */
    onCompanion: (() -> Unit)? = null,
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
        onCompanion?.let { item("Companion") { it() } }
        item("Edit UI layout", editUiOff == null) { onEditUi() }
        onEditOnPc?.let { item(if (pcRunning) "PC editor (on)" else "Edit on PC", editUiOff == null) { it() } }
        if (editUiOff != null) card.addView(DuoUi.label(context, editUiOff, 12f, DuoUi.MUTED))
        onCalibrate?.let { item("Line up screens") { it() } }
        card.addView(View(context), LinearLayout.LayoutParams(1, dp(4)))
        item("Close") { onClose() }
        addView(card, LayoutParams(dp(320), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
