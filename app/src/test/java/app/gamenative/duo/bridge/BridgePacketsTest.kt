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

    @Test
    fun helloCarriesTheSdkVersion() {
        assertEquals("unity-ugui", BridgePackets.engineOf("unity-ugui/sdk1.0"))
        assertEquals("1.0", BridgePackets.sdkOf("unity-ugui/sdk1.0"))
        assertEquals("", BridgePackets.sdkOf("balatro-love"))
        assertEquals(true, BridgePackets.sdkIsOlder("0.9"))
        assertEquals(false, BridgePackets.sdkIsOlder(BridgePackets.SDK_VERSION))
        assertEquals(false, BridgePackets.sdkIsOlder(""))
        assertEquals(false, BridgePackets.sdkIsOlder("2.0"))
    }

    // ---- HELLO and the session token (issue #26) ----

    private val token = "00112233445566778899aabbccddeeff"

    private fun judge(given: String?, samePort: Boolean = false, modAlive: Boolean = false, tokenSeen: Boolean = false) =
        BridgePackets.judgeHello(token, given, samePort, modAlive, tokenSeen)

    @Test
    fun helloTextSplitsIntoEngineSdkAndToken() {
        val hello = "unity-ugui/sdk1.1\u0000$token"
        assertEquals("unity-ugui", BridgePackets.engineOf(hello))
        assertEquals("1.1", BridgePackets.sdkOf(hello))
        assertEquals(token, BridgePackets.helloToken(hello))
        // A mod built before tokens.
        assertEquals("1.0", BridgePackets.sdkOf("unity-ugui/sdk1.0"))
        assertNull(BridgePackets.helloToken("unity-ugui/sdk1.0"))
    }

    @Test
    fun helloWithTheTokenIsAcceptedFromAnyPort() {
        assertEquals(BridgePackets.HelloVerdict.ACCEPT, judge(token))
        // A restarted game speaks from a new port while the old one still counts as alive.
        assertEquals(BridgePackets.HelloVerdict.ACCEPT, judge(token, samePort = false, modAlive = true, tokenSeen = true))
    }

    @Test
    fun helloWithAWrongTokenIsRefused() {
        assertEquals(BridgePackets.HelloVerdict.REFUSE, judge("ffeeddccbbaa99887766554433221100"))
        assertEquals(BridgePackets.HelloVerdict.REFUSE, judge(""))
        assertEquals(BridgePackets.HelloVerdict.REFUSE, judge(token.dropLast(1)))
        assertEquals(BridgePackets.HelloVerdict.REFUSE, judge(token + "0", samePort = true, modAlive = true))
    }

    @Test
    fun helloWithoutATokenIsTakenOnlyFromAModBuiltBeforeTokens() {
        // The first mod of the session, and its repeated HELLOs.
        assertEquals(BridgePackets.HelloVerdict.ACCEPT_LEGACY, judge(null))
        assertEquals(BridgePackets.HelloVerdict.ACCEPT_LEGACY, judge(null, samePort = true, modAlive = true))
        // The mod went quiet for longer than the timeout (the game restarted on another port).
        assertEquals(BridgePackets.HelloVerdict.ACCEPT_LEGACY, judge(null, samePort = false, modAlive = false))
    }

    @Test
    fun helloWithoutATokenCannotTakeOverALiveMod() {
        assertEquals(BridgePackets.HelloVerdict.REFUSE, judge(null, samePort = false, modAlive = true))
    }

    @Test
    fun helloWithoutATokenIsRefusedOnceATokenWasSeen() {
        assertEquals(BridgePackets.HelloVerdict.REFUSE, judge(null, samePort = true, modAlive = true, tokenSeen = true))
        assertEquals(BridgePackets.HelloVerdict.REFUSE, judge(null, samePort = false, modAlive = false, tokenSeen = true))
    }

    @Test
    fun tokensAre32HexCharactersAndDiffer() {
        val a = BridgePackets.newToken()
        assertEquals(true, Regex("[0-9a-f]{32}").matches(a))
        assertEquals(false, a == BridgePackets.newToken())
    }

    @Test
    fun describeLeavesTheTokenOut() {
        val body = "love/sdk1.1\u0000$token".toByteArray(Charsets.US_ASCII)
        val p = byteArrayOf('G'.code.toByte(), 'D'.code.toByte(), 1, DuoBridge.HELLO, 3, 0, 0, 0) + body
        assertEquals("HELLO caps 0x3 love/sdk1.1", BridgePackets.describe(p, p.size))
    }
}
