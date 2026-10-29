package app.gamenative.duo.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionDataTest {
    @Test
    fun templatesFillKeysAndFormats() {
        val d = CompanionData()
        d.put("a.n", 1234.4)
        d.put("a.s", "x")
        d.put("t", 65L)
        d.put("w", 3.0)
        assertEquals("1,234 g", d.format("{a.n:n0} g"))
        assertEquals("x--", d.format("{a.s}{missing}"))
        assertEquals("{a.s}", d.format("{{a.s}}"))
        assertEquals("1:05", d.format("{t:time}"))
        assertEquals("3", d.format("{w}"))
        assertEquals("50%", d.format("{p:pct}", mapOf("p" to 0.5)))
        assertEquals("no brace {", d.format("no brace {"))
    }

    @Test
    fun listScopeAnswersFirst() {
        val d = CompanionData()
        d.put("name", "store")
        assertEquals("Bob", d.format("{name}", mapOf("name" to "Bob")))
        assertEquals("z", d.format("{.}", "z"))
        assertEquals("store", d.format("{name}", "not a map"))
    }

    @Test
    fun conditionsCompareNumbersAcrossTypes() {
        val d = CompanionData()
        d.put("n", 2)
        assertTrue(d.test(Condition.Compare("n", Condition.Op.EQ, 2.0)))
        assertTrue(d.test(Condition.Compare("n", Condition.Op.GT, 1L)))
        assertFalse(d.test(Condition.Compare("n", Condition.Op.LT, 1L)))
        assertTrue(d.test(Condition.Compare("n", Condition.Op.IN, listOf(1L, 2L))))
        assertTrue(d.test(Condition.Compare("n", Condition.Op.EXISTS, true)))
        assertTrue(d.test(Condition.Compare("nope", Condition.Op.EXISTS, false)))
        assertFalse(d.test(Condition.Not(Condition.Compare("n", Condition.Op.EQ, 2L))))
        assertTrue(d.test(Condition.All(emptyList())))
        assertFalse(d.test(Condition.AnyOf(emptyList())))
        assertFalse(d.test(Condition.Compare("n", Condition.Op.GT, "1")))
    }

    @Test
    fun listenerHearsOnlyRealChanges() {
        val d = CompanionData()
        val events = mutableListOf<Set<String>>()
        d.listener = { events += it }
        d.put("k", 1)
        d.put("k", 1L)
        d.putAll(mapOf("p.a" to 1, "p.b" to 2))
        d.putAll(mapOf("p.a" to 1), clearPrefix = "p.")
        assertEquals(3, events.size)
        assertEquals(setOf("p.b"), events.last())
        assertNull(d["p.b"])
        assertEquals(1L, d["p.a"])
    }

    @Test
    fun keysOfFindsTemplateKeys() {
        assertEquals(setOf("a.b", "c"), CompanionData.keysOf("{a.b} and {c:n0} {.} {{x}}"))
        assertTrue(CompanionData.keysOf(null).isEmpty())
    }

    @Test
    fun timerStateRoundTripsAndPauses() {
        val t = TimerState(elapsedMs = 1000, sinceMs = 5000)
        assertEquals(3000L, t.elapsedAt(7000))
        assertEquals(TimerState(elapsedMs = 3000, sinceMs = 0), t.paused(7000))
        assertEquals(t, TimerState.from(t.toValue()))
        assertEquals(TimerState(), TimerState.from("junk"))
        assertEquals(listOf(2000L), t.lap(6000).laps)
    }
}
