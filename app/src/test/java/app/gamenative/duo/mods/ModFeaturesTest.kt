package app.gamenative.duo.mods

import app.gamenative.duo.ui.UiBackgroundMode
import app.gamenative.duo.ui.UiScreen
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Reads the bundled `features.json` files themselves, so a change to a mod's declarations shows up here. */
@RunWith(RobolectricTestRunner::class)
class ModFeaturesTest {
    private fun bundled(path: String): ModFeatures {
        val file = listOf(File("src/main/assets/duo/mods/$path"), File("app/src/main/assets/duo/mods/$path")).first { it.isFile }
        return ModFeatures.parse(file.readText()) ?: throw AssertionError("unreadable $path")
    }

    @Test
    fun balatroIsPartial() {
        val f = bundled("balatro/features.json")
        assertEquals(EditingLevel.PARTIAL, f.level)
        assertEquals(4, f.elements.size)
        val menus = f.elements.first { it.id == "Menus" }
        assertFalse(menus.canMove)
        assertNull(menus.scaleRange)
        assertEquals(setOf(UiScreen.BOTTOM, UiScreen.TOP), menus.screens)
        assertEquals(0.8f..1.4f, f.elements.first { it.id == "Hand" }.scaleRange)
        assertFalse(UiBackgroundMode.BLUR in f.backgrounds)
    }

    @Test
    fun stardewAndUnityAreFull() {
        assertEquals(EditingLevel.FULL, bundled("stardew/features.json").level)
        val unity = bundled("shared/unity-features.json")
        assertEquals(EditingLevel.FULL, unity.level)
        assertTrue(unity.dynamic)
    }

    @Test
    fun aModsOwnElementDoesNotLowerTheLevel() {
        val hk = bundled("hollow-knight/features.json")
        assertTrue(hk.elements.single().own)
        assertEquals(EditingLevel.FULL, hk.level)
    }

    @Test
    fun noColorBackgroundIsPartial() {
        val f = ModFeatures.parse("""{"engine":"x","background":["game"],"elements":[],"dynamic":true}""")!!
        assertEquals(EditingLevel.PARTIAL, f.level)
    }

    @Test
    fun brokenFileIsNull() {
        assertNull(ModFeatures.parse("not json"))
    }
}
