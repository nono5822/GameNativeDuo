package app.gamenative.duo.pc

import app.gamenative.duo.pc.PcPairing.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PC editor pairing: code once, the player allows the address, then a token bound to that address (issue #28). */
class PcPairingTest {
    private var time = 1_000_000L
    private var tokens = 0
    private val asked = mutableListOf<String>()
    private val pairing = PcPairing("ABC234", now = { time }, newToken = { "token${++tokens}" }).apply { onConfirm = { asked += it } }
    private val pc = "192.168.1.20"
    private val other = "192.168.1.66"

    @Test
    fun aNewAddressWithTheCodeWaitsForThePlayer() {
        assertEquals(Result.Confirm, pairing.pair(pc, "abc234 "))
        assertEquals(Result.Confirm, pairing.pair(pc, "ABC234"))
        assertEquals("asked once, not on every retry", listOf(pc), asked)
        assertEquals(pc, pairing.pending)
    }

    @Test
    fun allowedAddressGetsATokenThatOnlyWorksFromThere() {
        pairing.pair(pc, "ABC234")
        pairing.answer(pc, allow = true)
        assertNull(pairing.pending)
        assertEquals(Result.Paired("token1"), pairing.pair(pc, "ABC234"))
        assertTrue(pairing.valid(pc, "token1"))
        assertFalse(pairing.valid(pc, "token2"))
        assertFalse(pairing.valid(pc, null))
        assertFalse("a token read off the network is useless from another address", pairing.valid(other, "token1"))
    }

    @Test
    fun refusedAddressStaysOut() {
        pairing.pair(pc, "ABC234")
        pairing.answer(pc, allow = false)
        assertEquals(Result.Refused, pairing.pair(pc, "ABC234"))
        assertEquals(listOf(pc), asked)
        assertFalse(pairing.valid(pc, "token1"))
    }

    @Test
    fun anAnswerNobodyAskedForDoesNothing() {
        pairing.answer(other, allow = true)
        assertEquals(Result.Confirm, pairing.pair(other, "ABC234"))
    }

    @Test
    fun wrongCodesLockOutOnlyTheirSender() {
        repeat(PcPairing.MAX_WRONG) { assertEquals(Result.WrongCode, pairing.pair(other, "ZZZZZZ")) }
        // Even the right code has to wait now.
        assertEquals(Result.Locked(300), pairing.pair(other, "ABC234"))
        assertTrue(asked.isEmpty())
        // The player's own PC is not affected.
        assertEquals(Result.Confirm, pairing.pair(pc, "ABC234"))
        time += PcPairing.LOCK_MS
        assertEquals(Result.Confirm, pairing.pair(other, "ABC234"))
    }

    @Test
    fun aTypoDoesNotCountAgainstALaterRightCode() {
        repeat(PcPairing.MAX_WRONG - 1) { pairing.pair(pc, "ABC235") }
        assertEquals(Result.Confirm, pairing.pair(pc, "ABC234"))
        repeat(PcPairing.MAX_WRONG - 1) { assertEquals(Result.WrongCode, pairing.pair(pc, "ABC235")) }
    }

    @Test
    fun manyAddressesCannotGrowTheTableOrEvictAPairedOne() {
        pairing.pair(pc, "ABC234")
        pairing.answer(pc, allow = true)
        val token = (pairing.pair(pc, "ABC234") as Result.Paired).token
        repeat(PcPairing.MAX_CLIENTS + 10) { pairing.pair("10.0.0.$it", "ZZZZZZ") }
        assertTrue(pairing.pair("10.9.9.9", "ABC234") is Result.Locked)
        assertTrue(pairing.valid(pc, token))
    }

    @Test
    fun codesAndTokensAreRandom() {
        assertTrue(Regex("[A-HJ-NP-Z2-9]{6}").matches(PcPairing.randomCode()))
        assertTrue(Regex("[0-9a-f]{32}").matches(PcPairing.randomToken()))
        assertFalse(PcPairing.randomToken() == PcPairing.randomToken())
    }
}
