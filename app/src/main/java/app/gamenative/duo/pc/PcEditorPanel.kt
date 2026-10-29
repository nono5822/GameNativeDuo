package app.gamenative.duo.pc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import app.gamenative.duo.ui.DuoUi
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.roundToInt

/**
 * Shown on the bottom screen while the PC editor runs: the address to open on the PC and the pairing code (the QR
 * code carries both). A small card in a corner; the rest of the bottom screen keeps working for the game.
 */
@SuppressLint("ViewConstructor")
internal class PcEditorPanel(
    context: Context,
    url: String?,
    code: String,
    onHide: () -> Unit,
    onStop: () -> Unit,
) : FrameLayout(context) {
    init {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(DuoUi.PANEL)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), DuoUi.ACCENT_DIM)
            }
            isClickable = true
        }
        val full = url?.let { "$it?k=$code" }
        full?.let { qr(it) }?.let { bitmap ->
            card.addView(
                ImageView(context).apply { setImageBitmap(bitmap) },
                LinearLayout.LayoutParams(dp(112), dp(112)).apply { marginEnd = dp(16) },
            )
        }
        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(DuoUi.label(context, "Edit on your PC", 17f, Color.WHITE, bold = true))
        text.addView(
            DuoUi.label(
                context,
                if (url != null) "Open this address in a browser on the same Wi-Fi" else "Connect the device to Wi-Fi to use the PC editor",
                13f,
                DuoUi.MUTED,
            ),
        )
        url?.let {
            text.addView(DuoUi.label(context, it, 18f, DuoUi.ACCENT, bold = true).apply { setPadding(0, dp(6), 0, 0) })
            text.addView(
                DuoUi.label(context, "Code  $code", 22f, Color.WHITE, bold = true).apply {
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setPadding(0, dp(2), 0, dp(8))
                },
            )
        }
        text.addView(DuoUi.row(context, DuoUi.chip(context, "Hide") { onHide() }, DuoUi.chip(context, "Stop") { onStop() }))
        card.addView(text)
        addView(
            card,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(10), dp(10), dp(10), dp(10))
            },
        )
    }

    private fun qr(text: String): Bitmap? = try {
        val size = dp(112)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until size) for (x in 0 until size) setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    } catch (t: Throwable) {
        null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
