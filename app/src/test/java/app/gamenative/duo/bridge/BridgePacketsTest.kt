package app.gamenative.duo.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BridgePacketsTest {
    private fun statePacket(flags: Int, json: String): ByteArray {
        val body = json.toByteArray()
        return byteArrayOf('G'.code.toByte(), 'D'.code.toByte(), 1, DuoBridge.STATE, flags.toByte(), 0, 0, 0) + body
    }

    @Test
    fun stateBecomesPlainValues() {
        val p = statePacket(1, """{"hk.geo": 120, "hk.hp": 4.5, "hk.map": {"zone": "Crossroads"}, "hk.charms": [1, 2], "hk.gone": null}""")
        val (flags, changes) = BridgePackets.state(p, p.size)!!
        assertEquals(DuoBridge.STATE_FULL, flags)
        assertEquals(120L, changes["hk.geo"])
        assertEquals(4.5, changes["hk.hp"])
        assertEquals(mapOf("zone" to "Crossroads"), changes["hk.map"])
        assertEquals(listOf(1L, 2L), changes["hk.charms"])
        assertEquals(true, changes.containsKey("hk.gone"))
        assertNull(changes["hk.gone"])
    }

    @Test
    fun brokenStateIsDropped() {
        val p = statePacket(0, "{not json")
        assertNull(BridgePackets.state(p, p.size))
        assertNull(BridgePackets.state(p, 8))
    }

    @Test
    fun subscribeCarriesTheKeys() {
        val p = BridgePackets.subscribe(listOf("hk.map.", "hk.geo"))!!
        assertEquals(DuoBridge.SUBSCRIBE, p[3])
        val o = JSONObject(String(p, 4, p.size - 4))
        assertEquals(2, o.getJSONArray("keys").length())
        assertNull(BridgePackets.subscribe(List(2000) { "a.very.long.state.key.$it" }))
    }
}
