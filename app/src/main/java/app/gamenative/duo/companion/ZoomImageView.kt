package app.gamenative.duo.companion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import app.gamenative.duo.ui.DuoUi

/**
 * A picture that fits the card, pinch to zoom (up to 8x), drag to pan, double tap to fit again; an optional marker at
 * image fractions (a map's "you are here"). Draws only when the picture, the marker or the user's fingers change.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class ZoomImageView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DuoUi.ACCENT }
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3 * resources.displayMetrics.density
    }
    private var markerX: Float? = null
    private var markerY: Float? = null
    private var fit = 1f
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private val pt = FloatArray(2)

    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val next = (zoom * d.scaleFactor).coerceIn(1f, MAX_ZOOM)
                val f = next / zoom
                // Zoom around the fingers.
                tx = d.focusX - (d.focusX - tx) * f
                ty = d.focusY - (d.focusY - ty) * f
                zoom = next
                apply()
                return true
            }
        },
    )
    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (zoom <= 1f) return false
                tx -= dx
                ty -= dy
                apply()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                reset()
                return true
            }
        },
    )

    fun setBitmap(b: Bitmap?) {
        bitmap = b
        reset()
    }

    /** The marker at image fractions (0..1), or none. */
    fun setMarker(x: Float?, y: Float?) {
        if (x == markerX && y == markerY) return
        markerX = x?.takeIf { it in 0f..1f }
        markerY = y?.takeIf { it in 0f..1f }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = reset()

    private fun reset() {
        val b = bitmap
        zoom = 1f
        if (b != null && width > 0 && height > 0) {
            fit = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
            tx = (width - b.width * fit) / 2f
            ty = (height - b.height * fit) / 2f
        }
        apply()
    }

    /** Keeps the picture covering the view where it can (no panning into emptiness), then redraws. */
    private fun apply() {
        val b = bitmap
        if (b != null) {
            val w = b.width * fit * zoom
            val h = b.height * fit * zoom
            tx = if (w <= width) (width - w) / 2f else tx.coerceIn(width - w, 0f)
            ty = if (h <= height) (height - h) / 2f else ty.coerceIn(height - h, 0f)
            matrix.setScale(fit * zoom, fit * zoom)
            matrix.postTranslate(tx, ty)
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        // Two fingers or a zoomed picture belong to the picture; otherwise the page may scroll.
        if (event.pointerCount > 1 || zoom > 1f) parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, matrix, paint)
        val x = markerX
        val y = markerY
        if (x != null && y != null) {
            pt[0] = x * b.width
            pt[1] = y * b.height
            matrix.mapPoints(pt)
            val r = 7 * resources.displayMetrics.density
            canvas.drawCircle(pt[0], pt[1], r, markerFill)
            canvas.drawCircle(pt[0], pt[1], r, markerRing)
        }
    }

    private companion object {
        const val MAX_ZOOM = 8f
    }
}
