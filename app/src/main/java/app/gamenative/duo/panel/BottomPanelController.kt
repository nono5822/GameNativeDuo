package app.gamenative.duo.panel

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.gamenative.R
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.display.DeviceProfile
import app.gamenative.duo.display.realSize
import app.gamenative.duo.settings.DuoContainerSettings
import com.winlator.container.Container
import timber.log.Timber

/**
 * Shows the devkit's panel on the bottom screen while a game runs on the top screen.
 *
 * The panel window is never focusable, so the game keeps keyboard and controller input; taps on the panel
 * still reach its views. It stays out of the way when it is switched off in the container's GameNative Duo
 * settings, or when the container uses upstream's own second-screen features (external touchpad/keyboard,
 * or swapping the game onto the other screen).
 */
class BottomPanelController(
    private val context: Context,
    private val container: Container,
    private val profile: DeviceProfile,
    private val settings: DuoContainerSettings,
) {
    private var presentation: BottomPanelPresentation? = null

    /** Upstream already owns the second screen when either of its external-display features is on. */
    private val upstreamUsesSecondScreen: Boolean
        get() = container.isExternalDisplaySwap ||
            !container.externalDisplayMode.equals(Container.EXTERNAL_DISPLAY_MODE_OFF, ignoreCase = true)

    /** Shows, moves or hides the panel to match the current displays. Safe to call on every display change. */
    fun update() {
        try {
            val target = if (!settings.bottomPanelEnabled || upstreamUsesSecondScreen) null else profile.findBottomDisplay(context)
            val current = presentation
            if (target == null) {
                if (current != null) Timber.tag(DualScreenHost.TAG).i("Bottom panel hidden")
                dismiss()
                return
            }
            if (current != null && current.display.displayId == target.displayId) return
            dismiss()
            presentation = BottomPanelPresentation(context, target, container).also { it.show() }
            Timber.tag(DualScreenHost.TAG).i("Bottom panel shown on display %d", target.displayId)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to update bottom panel")
            dismiss()
        }
    }

    fun stop() = dismiss()

    private fun dismiss() {
        try {
            presentation?.dismiss()
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to dismiss bottom panel")
        }
        presentation = null
    }
}

private class BottomPanelPresentation(
    outerContext: Context,
    display: Display,
    private val container: Container,
) : Presentation(outerContext, display) {
    private var taps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        setContentView(buildContent())
    }

    private fun buildContent(): LinearLayout {
        val pad = dp(24)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK) // AMOLED: black pixels are off
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val size = display.realSize()
        root.addView(text(context.getString(R.string.duo_panel_title), 28f))
        root.addView(text(context.getString(R.string.duo_panel_game, container.name.ifBlank { container.id }), 18f))
        root.addView(
            text(
                context.getString(
                    R.string.duo_panel_device,
                    DeviceProfile.detect().displayName,
                    size.x,
                    size.y,
                    display.refreshRate,
                ),
                14f,
            ),
        )
        val counter = text(context.getString(R.string.duo_panel_touch_count, taps), 16f)
        root.addView(
            Button(context).apply {
                text = context.getString(R.string.duo_panel_touch_test)
                isFocusable = false
                setOnClickListener {
                    taps++
                    counter.text = context.getString(R.string.duo_panel_touch_count, taps)
                    Timber.tag(DualScreenHost.TAG).i("Bottom panel tap %d", taps)
                }
            },
        )
        root.addView(counter)
        return root
    }

    private fun text(value: String, sizeSp: Float) = TextView(context).apply {
        text = value
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
