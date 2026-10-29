package app.gamenative.duo.split

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.ui.DuoUi
import timber.log.Timber

/**
 * A small card at the top of the bottom screen that says what Dual screen is doing (starting, the mod did not start, a
 * hint), so the bottom screen is never a silent black panel. Only the card takes touches; the rest of the screen still
 * goes to the game. Plain views: nothing is drawn while it does not change.
 */
@SuppressLint("ViewConstructor")
internal class StatusCard(context: Context) : FrameLayout(context) {
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = DuoUi.panelBackground(context)
        isClickable = true
    }
    private val title = DuoUi.label(context, "", 16f, Color.WHITE, bold = true)
    private val body = DuoUi.label(context, "", 14f, DuoUi.MUTED).apply { maxLines = 4 }
    private val hide = DuoUi.chip(context, "Hide") { dismiss() }

    /** What the card shows now (null: hidden); for tests and logs. */
    var showing: String? = null
        private set

    /** Told when the card appears (true) or goes (false), so what it would cover can step aside. */
    var onShownChanged: ((Boolean) -> Unit)? = null

    init {
        card.addView(title)
        card.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(
            hide,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                topMargin = dp(6)
            },
        )
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setPadding(dp(16), dp(16), dp(16), 0)
        visibility = GONE
    }

    fun show(titleText: String, bodyText: String, dismissible: Boolean = true) {
        title.text = titleText
        body.text = bodyText
        body.visibility = if (bodyText.isEmpty()) GONE else VISIBLE
        hide.visibility = if (dismissible) VISIBLE else GONE
        visibility = VISIBLE
        if (showing != titleText) Timber.tag(DualScreenHost.TAG).i("Bottom screen status: %s", titleText)
        if (showing == null) onShownChanged?.invoke(true)
        showing = titleText
    }

    fun dismiss() {
        visibility = GONE
        if (showing != null) onShownChanged?.invoke(false)
        showing = null
    }

    private fun dp(v: Int) = DuoUi.dp(context, v)
}

/**
 * The bottom screen when the screen split cannot run (Gamescope, a game window without room for a bottom part): a
 * card saying why and where to fix it, instead of whatever was there before. Never takes focus.
 */
internal class StatusPresentation(
    context: Context,
    display: Display,
    private val titleText: String,
    private val bodyText: String,
) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        val card = StatusCard(context).apply { show(titleText, bodyText, dismissible = false) }
        setContentView(
            FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                val wide = FrameLayout.LayoutParams.MATCH_PARENT
                addView(card, FrameLayout.LayoutParams(wide, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            },
        )
    }

    companion object {
        /** Shows a status presentation on [display]; null (logged) if it cannot. */
        fun show(context: Context, display: Display, title: String, body: String): StatusPresentation? = try {
            StatusPresentation(context, display, title, body).also { it.show() }
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Could not show the bottom screen status")
            null
        }
    }
}

/** One-time hints, remembered for the whole app (not per game). */
internal object DuoHints {
    const val TWO_FINGER_MENU = "two_finger_menu"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("gnduo_hints", Context.MODE_PRIVATE)

    fun shown(context: Context, key: String): Boolean = prefs(context).getBoolean(key, false)

    fun markShown(context: Context, key: String) {
        prefs(context).edit().putBoolean(key, true).apply()
    }
}
