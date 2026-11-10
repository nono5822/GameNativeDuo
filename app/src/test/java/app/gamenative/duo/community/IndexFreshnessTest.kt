package app.gamenative.duo.community

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A signed index file must also be the one asked for, and not older than one already accepted (issue #27). */
class IndexFreshnessTest {
    private fun game(key: String, sequence: Long? = null) = JSONObject()
        .put("format", "gamenative-duo/index-game").put("key", key).put("packages", JSONArray())
        .apply { if (sequence != null) put("sequence", sequence) }

    @Test
    fun theRequestedGameIsAccepted() {
        assertNull(IndexFreshness.problem(game("steam-433340", 1_800_000_000), "steam-433340", 0))
    }

    @Test
    fun anotherGamesListIsRefused() {
        assertNotNull(IndexFreshness.problem(game("steam-367520", 1_800_000_000), "steam-433340", 0))
        // A file that names no game at all is not the one asked for either.
        assertNotNull(IndexFreshness.problem(JSONObject().put("sequence", 5), "steam-433340", 0))
    }

    @Test
    fun anOlderListIsRefused() {
        assertNotNull(IndexFreshness.problem(game("steam-433340", 1_799_999_999), "steam-433340", 1_800_000_000))
    }

    @Test
    fun theSameOrANewerListIsAccepted() {
        assertNull(IndexFreshness.problem(game("steam-433340", 1_800_000_000), "steam-433340", 1_800_000_000))
        assertNull(IndexFreshness.problem(game("steam-433340", 1_800_000_001), "steam-433340", 1_800_000_000))
    }

    @Test
    fun aListWithoutSequenceLoadsUntilOneWasSeen() {
        // Indexes published before sequences existed still load...
        assertNull(IndexFreshness.problem(game("steam-433340"), "steam-433340", 0))
        // ...but cannot replace a list that had one.
        assertNotNull(IndexFreshness.problem(game("steam-433340"), "steam-433340", 1_800_000_000))
    }

    @Test
    fun filesWithoutAKeyAreOnlyCheckedForAge() {
        val catalog = JSONObject().put("format", "gamenative-duo/index-catalog").put("sequence", 10)
        assertNull(IndexFreshness.problem(catalog, null, 10))
        assertNotNull(IndexFreshness.problem(catalog, null, 11))
    }

    @Test
    fun sequenceIsReadFromTheGameFile() {
        assertEquals(1_800_000_000L, CommunityGame.fromJson(game("steam-433340", 1_800_000_000)).sequence)
        assertEquals(0L, CommunityGame.fromJson(game("steam-433340")).sequence)
        assertEquals(0L, IndexFreshness.sequenceOf(JSONObject().put("sequence", -4)))
    }
}
