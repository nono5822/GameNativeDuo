package app.gamenative.duo.mods

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Bundled copies of the Duo SDK must match `duo/sdk/` (the source of truth): copy them again after an SDK change. */
class SdkCopiesTest {
    @Test
    fun balatroCarriesTheCurrentDuoLua() {
        val sdk = File("../duo/sdk/lua/duo.lua")
        val bundled = File("src/main/assets/duo/mods/balatro/GameNativeDuo-split/duo.lua")
        if (!sdk.isFile) return // run from elsewhere than the app module
        assertEquals("duo.lua differs from duo/sdk/lua/duo.lua", sdk.readText(), bundled.readText())
    }
}
