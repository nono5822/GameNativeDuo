package app.gamenative.duo.display

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenCalibrationTest {
    private val thor = DeviceProfile.AYN_THOR.calibration

    @Test
    fun defaultsAndThorProfile() {
        val defaults = ScreenCalibration()
        assertEquals(0, defaults.gapPx)
        assertEquals(1f, defaults.bottomScale, 0f)
        assertEquals(0, defaults.bottomShiftPx)

        assertEquals(249, thor.gapPx)
        assertEquals(1.142f, thor.bottomScale, 1e-6f)
        assertEquals(0, thor.bottomShiftPx)
    }

    @Test
    fun rangesMatchTheDocumentedLimits() {
        assertEquals(0..800, ScreenCalibration.GAP_RANGE)
        assertEquals(-300..300, ScreenCalibration.SHIFT_RANGE)
        assertEquals(0.8f, ScreenCalibration.SCALE_MIN, 0f)
        assertEquals(1.4f, ScreenCalibration.SCALE_MAX, 0f)
    }

    @Test
    fun jsonRoundTripKeepsValidValues() {
        val calibration = ScreenCalibration(gapPx = 0, bottomScale = 1.142f, bottomShiftPx = 300)
        val restored = ScreenCalibration.fromJson(calibration.toJson(), ScreenCalibration())
        assertEquals(calibration, restored)

        val thorRestored = ScreenCalibration.fromJson(thor.toJson(), ScreenCalibration())
        assertEquals(thor, thorRestored)
    }

    @Test
    fun jsonClampsOutOfRangeValues() {
        val high = ScreenCalibration(gapPx = 9999, bottomScale = 9f, bottomShiftPx = 9999).toJson()
        val clampedHigh = ScreenCalibration.fromJson(high, ScreenCalibration())
        assertEquals(800, clampedHigh.gapPx)
        assertEquals(ScreenCalibration.SCALE_MAX, clampedHigh.bottomScale, 0f)
        assertEquals(300, clampedHigh.bottomShiftPx)

        val low = ScreenCalibration(gapPx = -1, bottomScale = 0.1f, bottomShiftPx = -9999).toJson()
        val clampedLow = ScreenCalibration.fromJson(low, ScreenCalibration())
        assertEquals(0, clampedLow.gapPx)
        assertEquals(ScreenCalibration.SCALE_MIN, clampedLow.bottomScale, 0f)
        assertEquals(-300, clampedLow.bottomShiftPx)
    }

    @Test
    fun edgeCasesStayInside() {
        val zero = ScreenCalibration(gapPx = 0, bottomScale = ScreenCalibration.SCALE_MIN, bottomShiftPx = -300)
        assertEquals(zero, ScreenCalibration.fromJson(zero.toJson(), ScreenCalibration()))

        val extreme = ScreenCalibration(gapPx = 800, bottomScale = ScreenCalibration.SCALE_MAX, bottomShiftPx = 300)
        assertEquals(extreme, ScreenCalibration.fromJson(extreme.toJson(), ScreenCalibration()))
    }
}
