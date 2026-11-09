package app.gamenative.duo.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompanionManifestTest {
    @Test
    fun fullManifestParses() {
        val json = JSONObject(
            """{"format":"gamenative-duo/companion","version":1,"id":"hk-journal","name":"Journal","games":["steam-367520"],"requires":{"api":{"name":"hk","min":2},"builds":[{"source":"game.build","in":["1.5"]}]},"data":{"charms":"data/charms.json"},"pages":[{"id":"p","title":"P","cards":[{"type":"text","text":"hi"},{"type":"value","value":"{hk.geo}"}]}]}"""
        )
        val m = CompanionManifest.parse(json, "fallback")
        assertEquals("hk-journal", m.id)
        assertEquals("hk" to 2, m.api)
        assertEquals(listOf(BuildGate("game.build", listOf("1.5"))), m.builds)
        assertEquals(mapOf("charms" to "data/charms.json"), m.data)
        assertEquals(listOf(CardType.TEXT, CardType.VALUE), m.pages[0].cards.map { it.type })
        assertTrue(m.problems.isEmpty())
    }

    @Test
    fun unknownCardTypeIsKeptAsUnsupported() {
        val json = JSONObject("""{"pages":[{"cards":[{"type":"radar"}]}]}""")
        val m = CompanionManifest.parse(json, "fallback")
        val card = m.pages[0].cards[0]
        assertEquals(CardType.UNSUPPORTED, card.type)
        assertEquals("radar", card.typeName)
        assertTrue(m.problems.any { "radar" in it })
    }

    @Test
    fun idsAreGeneratedAndUnique() {
        val json = JSONObject(
            """{"pages":[{"cards":[{"type":"text"}]},{"cards":[{"id":"x","type":"text"},{"id":"x","type":"text"}]}]}"""
        )
        val m = CompanionManifest.parse(json, "fallback")
        assertEquals(listOf("page1", "page2"), m.pages.map { it.id })
        assertEquals("page1.card1", m.pages[0].cards[0].id)
        assertEquals(listOf("x", "x-2"), m.pages[1].cards.map { it.id })
    }

    @Test
    fun pageAndCardIdsDoNotCollide() {
        // User state is keyed by card id: a "notes" card on a "notes" page must stay "notes".
        val json = JSONObject("""{"pages":[{"id":"notes","cards":[{"id":"notes","type":"notes"}]}]}""")
        val m = CompanionManifest.parse(json, "fallback")
        assertEquals("notes", m.pages[0].id)
        assertEquals("notes", m.pages[0].cards[0].id)
    }

    @Test
    fun bundledGeneralCompanionHasNoProblems() {
        val file = java.io.File("src/main/assets/duo/companions/general/companion.json")
        val m = CompanionManifest.parse(JSONObject(file.readText()), "general")
        assertTrue(m.problems.toString(), m.problems.isEmpty())
        assertTrue(m.general)
    }

    @Test
    fun unsafeDataPathsAreDropped() {
        val json = JSONObject("""{"data":{"a":"../a.json","b":"data/b.json"}}""")
        val m = CompanionManifest.parse(json, "fallback")
        assertEquals(mapOf("b" to "data/b.json"), m.data)
        assertTrue(m.problems.isNotEmpty())
    }
}
