package app.gamenative.duo.display

import android.content.Context
import app.gamenative.duo.DualScreenHost
import java.io.File
import org.json.JSONObject
import timber.log.Timber

/**
 * How the two physical screens sit relative to each other, so a picture spanning both looks continuous across
 * the hinge. All values are in top-screen pixels. It belongs to the device, not to a game, so it is stored per
 * device profile and tuned live from the bottom screen (see `ScreenCalibrationPanel`).
 */
data class ScreenCalibration(
    /** Top-screen pixels hidden between the screens (bezels and hinge). */
    val gapPx: Int = 0,
    /** Bottom-screen pixels per top-screen pixel of the same physical length (pixel density ratio). */
    val bottomScale: Float = 1f,
    /** Horizontal offset of the bottom screen's centre from the top screen's centre, in top-screen pixels. */
    val bottomShiftPx: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("gap", gapPx)
        put("bottomScale", Math.round(bottomScale * 1000.0) / 1000.0)
        put("bottomShift", bottomShiftPx)
    }

    companion object {
        val GAP_RANGE = 0..800
        const val SCALE_MIN = 0.8f
        const val SCALE_MAX = 1.4f
        val SHIFT_RANGE = -300..300

        fun fromJson(json: JSONObject, fallback: ScreenCalibration): ScreenCalibration = ScreenCalibration(
            gapPx = json.optInt("gap", fallback.gapPx).coerceIn(GAP_RANGE),
            bottomScale = json.optDouble("bottomScale", fallback.bottomScale.toDouble()).toFloat().coerceIn(SCALE_MIN, SCALE_MAX),
            bottomShiftPx = json.optInt("bottomShift", fallback.bottomShiftPx).coerceIn(SHIFT_RANGE),
        )
    }
}

/** Calibration per device profile in `files/duo/calibration/<profile id>.json`; the profile's own values otherwise. */
object ScreenCalibrationStore {
    fun load(context: Context, profile: DeviceProfile): ScreenCalibration = try {
        val file = fileFor(context, profile)
        if (file.isFile) ScreenCalibration.fromJson(JSONObject(file.readText()), profile.calibration) else profile.calibration
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).w(t, "Ignoring unreadable screen calibration")
        profile.calibration
    }

    fun save(context: Context, profile: DeviceProfile, calibration: ScreenCalibration) {
        try {
            val file = fileFor(context, profile)
            file.parentFile?.mkdirs()
            file.writeText(calibration.toJson().toString(2))
            Timber.tag(DualScreenHost.TAG).i("Saved screen calibration for %s: %s", profile.id, calibration)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to save screen calibration")
        }
    }

    private fun fileFor(context: Context, profile: DeviceProfile) =
        File(context.filesDir, "duo/calibration/${profile.id.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json")
}
