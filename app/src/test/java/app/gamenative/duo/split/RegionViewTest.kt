package app.gamenative.duo.split

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionViewTest {
    private val aspect = 1240f / 1080f // AYN Thor bottom screen

    @Test
    fun parsesAndFormats() {
        val f = RegionView.parse("0.8, 0.02,0.15,0.2")!!
        assertArrayEquals(floatArrayOf(0.8f, 0.02f, 0.15f, 0.2f), f, 1e-6f)
        assertEquals("0.8000,0.0200,0.1500,0.2000", RegionView.format(f))
        assertNull(RegionView.parse("1,2,3"))
        assertNull(RegionView.parse("0,0,-1,1"))
        assertNull(RegionView.parse(null))
    }

    @Test
    fun keepsTheBottomShapeAndStaysInside() {
        for (f in listOf(null, floatArrayOf(0.9f, 0.9f, 0.5f, 0.1f), floatArrayOf(-1f, -1f, 3f, 3f))) {
            val r = RegionView.pixels(f, 1920, 1080, aspect)
            assertEquals(aspect, r[2] / r[3], 1e-3f)
            assertTrue(r[0] >= 0f && r[1] >= 0f && r[0] + r[2] <= 1920.5f && r[1] + r[3] <= 1080.5f)
        }
    }

    @Test
    fun panAndZoomStayInsideAndKeepTheSpotUnderTheFingers() {
        val r = RegionView.pixels(null, 1920, 1080, aspect)
        val moved = RegionView.pan(r, -5000f, 5000f, 1920, 1080)
        assertEquals(0f, moved[0], 0f)
        assertEquals(1080f - moved[3], moved[1], 1e-3f)
        val mid = floatArrayOf(800f, 400f, 300f, 300f / aspect)
        val zoomed = RegionView.zoom(mid, 2f, 0.5f, 0.5f, 1920, 1080, aspect)
        assertEquals(150f, zoomed[2], 1e-3f)
        assertEquals(800f + 150f, zoomed[0] + zoomed[2] / 2f, 1e-3f) // centre kept
        val out = RegionView.zoom(mid, 0.001f, 0.5f, 0.5f, 1920, 1080, aspect)
        assertTrue(out[2] <= 1920.5f && out[3] <= 1080.5f)
    }
}
