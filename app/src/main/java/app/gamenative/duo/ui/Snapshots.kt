package app.gamenative.duo.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import app.gamenative.duo.DualScreenHost
import java.io.ByteArrayOutputStream
import timber.log.Timber

/**
 * Pictures of what a screen shows, for the PC editor and the "game snapshot" background. PixelCopy reads the
 * surface's last frame from the GPU, so the game is not slowed down and our own overlays (editor, widgets) are not
 * in the picture. Only on request; nothing runs otherwise.
 */
internal object Snapshots {
    /** The activity behind [context] (the game's, on the top screen), if any. */
    fun activityOf(context: Context): Activity? =
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()

    /** The largest visible SurfaceView under [root]: the game's picture. */
    fun findSurface(root: View?): SurfaceView? {
        var best: SurfaceView? = null
        fun visit(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is SurfaceView && v.width * v.height > (best?.let { it.width * it.height } ?: 0)) best = v
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        root?.let { visit(it) }
        return best
    }

    /** Copies [view]'s current frame; [done] runs on [handler]'s thread with the picture, or null. */
    fun capture(view: SurfaceView, handler: Handler, done: (Bitmap?) -> Unit) {
        try {
            if (view.width == 0 || view.height == 0 || view.holder.surface?.isValid != true) {
                done(null)
                return
            }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(view, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    done(bitmap)
                } else {
                    bitmap.recycle()
                    done(null)
                }
            }, handler)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Snapshot failed")
            done(null)
        }
    }

    fun jpeg(bitmap: Bitmap, quality: Int = 85): ByteArray = ByteArrayOutputStream(256 * 1024).also {
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)
    }.toByteArray()
}
