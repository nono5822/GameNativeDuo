package app.gamenative.duo.community

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RatingAggregationTest {
    private fun rating(json: String) = CommunityRating.fromJson(JSONObject(json))

    @Test
    fun parsesVotesCompatAndRules() {
        val r = rating(
            """{"up":9,"down":1,"comments":3,"url":"https://github.com/o/r/discussions/1","rules":2,
               "reportUrl":"https://github.com/o/r/issues/new?template=compat-report.yml",
               "compat":{"ayn-thor":{"works":4,"issues":1,"broken":0},"empty":{"works":0}}}""",
        )!!
        assertEquals(10, r.votes)
        assertEquals(90, r.percentUp)
        assertTrue(r.verified)
        assertEquals(setOf("ayn-thor"), r.compat.keys)
        assertEquals(CompatVerdict.WORKS, r.compat.getValue("ayn-thor").verdict)
        assertEquals("https://github.com/o/r/issues/new?template=compat-report.yml", r.reportUrl)
    }

    @Test
    fun oldIndexAndHostileValues() {
        val r = rating("""{"up":-5,"down":2,"comments":0,"url":"https://github.com/x","reportUrl":"https://evil.example/form"}""")!!
        assertEquals(0, r.up)
        assertFalse(r.verified)
        assertNull(r.reportUrl)
        // Only GitHub links are opened, even from the signed index.
        assertNull(rating("""{"up":1,"down":0,"url":"https://evil.example/d"}"""))
        assertNull(rating("""{"up":0,"down":0,"url":"https://github.com/x"}""")!!.percentUp)
    }

    @Test
    fun compatVerdictLeansToTheWorseOutcome() {
        assertEquals(CompatVerdict.WORKS, CompatReports(3, 1, 1).verdict)
        assertEquals(CompatVerdict.ISSUES, CompatReports(1, 0, 1).verdict)
        assertEquals(CompatVerdict.ISSUES, CompatReports(2, 2, 0).verdict)
        assertEquals(CompatVerdict.BROKEN, CompatReports(0, 1, 3).verdict)
        assertEquals(CompatVerdict.UNKNOWN, CompatReports(0, 0, 0).verdict)
        assertNull(CompatReports.fromJson(JSONObject("""{"works":0,"issues":0,"broken":0}""")))
    }

    @Test
    fun officialComesFromThePinnedListOrTheIndexListNeverThePackage() {
        val game = CommunityGame.fromJson(
            JSONObject(
                """{"key":"steam-1","official":["gamenative-duo.new-one"],"packages":[
                  ${entry("someone.fake", extra = "\"official\":true,")},
                  ${entry("gamenative-duo.new-one")},
                  ${entry("gamenative-duo.crosshair-on-top")},
                  ${entry("gamenative-duo.not-listed")}]}""",
            ),
        )
        val official = game.packages.associate { it.id to it.official }
        assertEquals(
            mapOf(
                "someone.fake" to false,
                "gamenative-duo.new-one" to true,
                "gamenative-duo.crosshair-on-top" to true,
                "gamenative-duo.not-listed" to false,
            ),
            official,
        )
    }

    private fun entry(id: String, extra: String = "") =
        """{"id":"$id","kind":"ui-layout","packageVersion":"1.0.0","title":"$id",$extra"files":[]}"""
}
