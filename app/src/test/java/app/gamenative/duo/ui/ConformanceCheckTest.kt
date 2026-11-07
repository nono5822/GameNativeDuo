package app.gamenative.duo.ui

import android.graphics.RectF
import app.gamenative.duo.ui.ConformanceCheck.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConformanceCheckTest {
    private fun el(
        screen: UiScreen = UiScreen.BOTTOM,
        rect: RectF = RectF(100f, 1200f, 300f, 1300f),
        screens: Set<UiScreen>? = null,
        move: Boolean = true,
        scale: ClosedFloatingPointRange<Float>? = UiRule.SCALE_MIN..UiRule.SCALE_MAX,
    ) = UiElement(0, "e", "E", -1, 0, screen, rect, graphic = true, screens = screens, canMove = move, scaleRange = scale)

    @Test
    fun planCoversDeclaredFeaturesOnly() {
        assertEquals(listOf(Feature.SCREEN, Feature.HIDE, Feature.MOVE, Feature.RESIZE), ConformanceCheck.planFor(el()))
        // Balatro's Menus: bottom or top, nothing else.
        val menus = el(screens = setOf(UiScreen.BOTTOM, UiScreen.TOP), move = false, scale = null)
        assertEquals(listOf(Feature.SCREEN), ConformanceCheck.planFor(menus))
        // Hand info: bottom or hidden, moves, no resize.
        val info = el(screens = setOf(UiScreen.BOTTOM, UiScreen.HIDDEN), scale = null)
        assertEquals(listOf(Feature.HIDE, Feature.MOVE), ConformanceCheck.planFor(info))
    }

    @Test
    fun resizeFactorFitsTheDeclaredRange() {
        assertEquals(1.25f, ConformanceCheck.scaleFactor(el()))
        assertEquals(0.8f, ConformanceCheck.scaleFactor(el(scale = 0.7f..1.2f)))
        assertNull(ConformanceCheck.scaleFactor(el(scale = 0.9f..1.1f)))
        assertNull(ConformanceCheck.scaleFactor(el(scale = null)))
    }

    @Test
    fun moveMustGoTheRightWayAndRoughlyTheRightAmount() {
        val before = el()
        // 10% of a 1240 px wide bottom screen: 124 px.
        assertTrue(ConformanceCheck.judge(Feature.MOVE, before, el(rect = RectF(220f, 1200f, 420f, 1300f)), 1240f, 1080f).first)
        assertFalse(ConformanceCheck.judge(Feature.MOVE, before, before, 1240f, 1080f).first)
        assertFalse(ConformanceCheck.judge(Feature.MOVE, before, el(rect = RectF(-24f, 1200f, 176f, 1300f)), 1240f, 1080f).first)
    }

    @Test
    fun resizeComparesWidths() {
        val before = el()
        val bigger = el(rect = RectF(75f, 1190f, 325f, 1310f))
        assertTrue(ConformanceCheck.judge(Feature.RESIZE, before, bigger, 1240f, 1080f, 1.25f).first)
        assertFalse(ConformanceCheck.judge(Feature.RESIZE, before, before, 1240f, 1080f, 1.25f).first)
        val smaller = el(rect = RectF(120f, 1200f, 280f, 1300f))
        assertTrue(ConformanceCheck.judge(Feature.RESIZE, before, smaller, 1240f, 1080f, 0.8f).first)
    }

    @Test
    fun screenAndHide() {
        val before = el()
        assertTrue(ConformanceCheck.judge(Feature.SCREEN, before, el(screen = UiScreen.TOP), 1240f, 1080f).first)
        assertFalse(ConformanceCheck.judge(Feature.SCREEN, before, before, 1240f, 1080f).first)
        assertTrue(ConformanceCheck.judge(Feature.HIDE, before, el(screen = UiScreen.HIDDEN), 1240f, 1080f).first)
        assertTrue(ConformanceCheck.judge(Feature.HIDE, before, el(rect = RectF(100f, 1200f, 100f, 1200f)), 1240f, 1080f).first)
        assertFalse(ConformanceCheck.judge(Feature.HIDE, before, before, 1240f, 1080f).first)
    }
}
