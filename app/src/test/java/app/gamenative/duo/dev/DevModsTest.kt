package app.gamenative.duo.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DevModsTest {
    @Test
    fun devFolderMirrorsTheBundledAssets() {
        assertEquals("GameNativeDuo-split", DevMods.relative("duo/mods/balatro/GameNativeDuo-split", "balatro"))
        val dll = "plugin/GameNativeDuo.HollowKnight.dll"
        assertEquals(dll, DevMods.relative("duo/mods/hollow-knight/$dll", "hollow-knight"))
        assertEquals("shared/unity-split", DevMods.relative("duo/mods/shared/unity-split", "hollow-knight"))
        assertNull(DevMods.relative("duo/mods/stardew/mod", "balatro"))
    }
}
