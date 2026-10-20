package app.gamenative.duo.split

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenSplitScaleTest {
    // AYN Thor: top 1920x1080 (landscape), bottom 1240x1080.
    @Test
    fun fullResolution() {
        val s = ScreenSplit.native(1920, 1080, 1240, 1080)
        assertEquals(1920 to 2160, s.screenSize())
        assertEquals(listOf(340f, 1080f, 1240f, 1080f), s.bottom.toList())
    }

    @Test
    fun threeQuartersAndHalf() {
        val s75 = ScreenSplit.native(1920, 1080, 1240, 1080, 0.75f)
        assertEquals(1440 to 1620, s75.screenSize())
        assertEquals(listOf(0f, 0f, 1440f, 810f), s75.top.toList())
        assertEquals(listOf(255f, 810f, 930f, 810f), s75.bottom.toList())
        val s50 = ScreenSplit.native(1920, 1080, 1240, 1080, 0.5f)
        assertEquals(960 to 1080, s50.screenSize())
        // Sizes stay even (offsets may not: the bottom part is centred).
        listOf(s50.top[2], s50.top[3], s50.bottom[2], s50.bottom[3]).forEach { assertEquals(0f, it % 2f, 0f) }
    }
}
