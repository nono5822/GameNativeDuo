package app.gamenative.duo.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModStateTest {
    @Test
    fun aModWritesOnlyItsOwnKeys() {
        assertTrue(ModState.accepts("hk.geo", "hk"))
        assertFalse(ModState.accepts("sdv.money", "hk"))
        assertFalse(ModState.accepts("user.notes", "hk"))
        // No api in the manifest: anything but the app's own prefixes.
        assertTrue(ModState.accepts("sdv.money", null))
        assertFalse(ModState.accepts("user.notes", null))
        assertFalse(ModState.accepts("session.fps", null))
        assertFalse(ModState.accepts("nodot", null))
    }

    @Test
    fun subscriptionKeepsOnlyModKeys() {
        val keys = listOf("session.clock", "hk.geo", "user.notes", "hk.charms", "hk.geo")
        assertEquals(listOf("hk.charms", "hk.geo"), ModState.subscription(keys, "hk"))
        assertEquals(emptyList<String>(), ModState.subscription(emptyList(), "hk"))
    }
}
