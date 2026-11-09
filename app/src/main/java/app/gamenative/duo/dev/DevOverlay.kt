package app.gamenative.duo.dev

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

/**
 * Developer mode's overlay on the bottom screen (toggled in the two-finger menu): the mod's HELLO (engine, SDK,
 * capabilities), bridge packets per second, the last mod log lines, and DEV when dev-folder files are loaded. Redraws
 * once a second while shown; touches go through it.
 */
@SuppressLint("ViewConstructor")
class DevOverlay(context: Context, private val dev: DevSession) : TextView(context) {
    private val tick = object : Runnable {
        override fun run() {
            update()
            postDelayed(this, 1000)
        }
    }

    init {
        setTextColor(Color.WHITE)
        typeface = Typeface.MONOSPACE
        textSize = 11f
        val pad = (8 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
        background = GradientDrawable().apply {
            setColor(Color.argb(170, 10, 12, 24))
            cornerRadius = pad.toFloat()
        }
        isClickable = false
        isFocusable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        tick.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun update() {
        val b = dev.bridge
        val (rin, rout) = dev.rates()
        val mod = if (b.engine.isEmpty()) "no mod yet" else "${b.engine}${if (b.sdkVersion.isNotEmpty()) " sdk${b.sdkVersion}" else ""}"
        val head = (if (dev.devFiles.isNotEmpty()) "DEV  " else "") + "$mod  caps 0x${Integer.toHexString(b.capabilities)}"
        val lines = listOf(head, "bridge in %.1f/s out %.1f/s".format(rin, rout)) + dev.lastLogs(4).map { it.take(70) }
        text = lines.joinToString("\n")
    }
}
