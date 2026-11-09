package app.gamenative.duo.ui

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UiRegionTest {
    // A 1280x720 game, a 1240x1080 bottom screen.
    private val sw = 1280
    private val sh = 720
    private val bw = 1240
    private val bh = 1080

    private val minimap = UiRegion("region-1", "Minimap", listOf(0.75f, 0f, 0.25f, 0.25f), UiScreen.HIDDEN, 0.5f, 0.25f, 0.5f)

    @Test
    fun survivesTheLayoutFile() {
        val layout = UiLayout("STEAM_250900", regions = listOf(minimap, UiRegion.create(listOf(minimap)).copy(touch = false)))
        val back = UiLayout.fromJson(JSONObject(layout.toJson().toString()), "x")
        assertEquals(layout.regions, back.regions)
        assertEquals("region-2", back.regions[1].id)
    }

    @Test
    fun placeKeepsTheSourceShape() {
        // Source 320x180 px, half the bottom width = 620 px wide, so 348.75 px high, centred at (620, 270).
        val p = RegionOutput.placePx(minimap, sw, sh, bw, bh)
        assertEquals(620f, p[2], 0.01f)
        assertEquals(348.75f, p[3], 0.01f)
        assertEquals(310f, p[0], 0.01f)
        assertEquals(270f - 348.75f / 2f, p[1], 0.01f)
    }

    @Test
    fun placeNeverOverflowsTheScreen() {
        val tall = UiRegion("t", "t", listOf(0f, 0f, 0.1f, 1f), cx = 0f, cy = 1f, width = 1f)
        val p = RegionOutput.placePx(tall, sw, sh, bw, bh)
        assertEquals(bh.toFloat(), p[3], 0.01f)
        assertTrue(p[0] >= 0f && p[1] >= 0f && p[0] + p[2] <= bw + 0.01f)
    }

    @Test
    fun nativeTransformMapsSourceOntoPlace() {
        val d = RegionOutput.nativeData(listOf(minimap), sw, sh, bw, bh)
        assertEquals(8, d.size)
        // The renderer puts X screen point X at surface pixel (ox + X * sx) / sw * bw.
        val src = RegionOutput.sourcePx(minimap, sw, sh)
        val dst = RegionOutput.placePx(minimap, sw, sh, bw, bh)
        assertEquals(dst[0], (d[0] + src[0] * d[2]) / sw * bw, 0.01f)
        assertEquals(dst[0] + dst[2], (d[0] + (src[0] + src[2]) * d[2]) / sw * bw, 0.01f)
        assertEquals(dst[1] + dst[3], (d[1] + (src[1] + src[3]) * d[3]) / sh * bh, 0.01f)
        assertArrayEquals(dst, d.copyOfRange(4, 8), 0.01f)
    }

    @Test
    fun onlyShownRegionsRenderAndOnlyHiddenOnesMask() {
        val onTop = minimap.copy(id = "r2", screen = UiScreen.TOP)
        assertEquals(8, RegionOutput.nativeData(listOf(minimap, onTop), sw, sh, bw, bh).size)
        assertArrayEquals(floatArrayOf(960f, 0f, 320f, 180f), RegionOutput.masks(listOf(minimap, onTop), sw, sh), 0.01f)
        assertEquals(0, RegionOutput.masks(listOf(onTop), sw, sh).size)
    }

    @Test
    fun tapsClickAtTheSourcePoint() {
        val p = RegionOutput.placePx(minimap, sw, sh, bw, bh)
        val hit = RegionOutput.hit(listOf(minimap), p[0] + p[2] / 2f, p[1] + p[3] / 2f, sw, sh, bw, bh)
        assertEquals(1120 to 90, hit)
        assertNull(RegionOutput.hit(listOf(minimap), 5f, 1070f, sw, sh, bw, bh))
        assertNull(RegionOutput.hit(listOf(minimap.copy(touch = false)), p[0] + 1f, p[1] + 1f, sw, sh, bw, bh))
    }

    @Test
    fun legacyRegionFillsTheBottomScreen() {
        val r = UiRegion.fromLegacy(floatArrayOf(0.72f, 0f, 0.28f, 0.4f))!!
        assertEquals(1f, r.width)
        assertEquals(UiScreen.BOTTOM, r.screen)
    }

    @Test
    fun brokenRegionsAreDropped() {
        assertNull(UiRegion.fromJson(JSONObject("""{"id":"a","source":[0,0,1]}""")))
        assertNull(UiRegion.fromJson(JSONObject("""{"source":[0,0,1,1]}""")))
        val clamped = UiRegion.fromJson(JSONObject("""{"id":"a","source":[0.9,0.9,0.5,0.5]}"""))!!
        assertEquals(listOf(0.5f, 0.5f, 0.5f, 0.5f), clamped.source)
    }
}
