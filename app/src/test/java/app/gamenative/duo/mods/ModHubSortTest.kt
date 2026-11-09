package app.gamenative.duo.mods

import app.gamenative.duo.community.CommunityKind
import app.gamenative.duo.community.CommunityPackage
import app.gamenative.duo.community.CommunityRating
import app.gamenative.duo.community.CompatReports
import app.gamenative.duo.community.CompatVerdict
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModHubSortTest {
    private val thor = "ayn-thor"
    private val bundled = DuoGameMod(id = "slime-rancher", appId = "STEAM_433340", title = "Slime Rancher", gameFiles = emptyMap())

    private fun pkg(
        id: String,
        kind: CommunityKind = CommunityKind.MOD,
        up: Int = 0,
        down: Int = 0,
        updated: String = "2026-01-01",
        testedOn: List<String> = emptyList(),
        compat: CompatReports? = null,
        reportUrl: String? = null,
    ) = HubEntry.Community(
        CommunityPackage(
            id = id, kind = kind, version = "1.2.0", title = id, description = "", authors = listOf("a"), license = "MIT",
            adapter = null, testedOn = testedOn, gameBuilds = emptyList(), tags = emptyList(), files = emptyList(),
            screenshots = emptyList(), updated = updated,
            rating = CommunityRating(up, down, 0, "https://github.com/o/r/discussions/1", compat = compat?.let { mapOf(thor to it) }.orEmpty(), reportUrl = reportUrl),
        ),
    )

    private fun ids(list: List<HubEntry>) = list.map { it.id }

    @Test
    fun bundledFirstThenWilsonRating() {
        val entries = listOf(pkg("one-vote", up = 1), pkg("many", up = 40, down = 2), pkg("none"), HubEntry.Bundled(bundled))
        assertEquals(listOf("slime-rancher", "many", "one-vote", "none"), ids(ModHub.sort(entries, HubSort.RATING, thor)))
    }

    @Test
    fun recentByUpdateDate() {
        val entries = listOf(pkg("old", updated = "2025-05-01"), pkg("new", updated = "2026-10-01"), pkg("mid", updated = "2026-02-01"))
        assertEquals(listOf("new", "mid", "old"), ids(ModHub.sort(entries, HubSort.RECENT, thor)))
    }

    @Test
    fun compatPutsReportsFromThisDeviceFirst() {
        val entries = listOf(
            pkg("broken", up = 50, compat = CompatReports(0, 0, 3)),
            pkg("unknown", up = 10),
            pkg("issues", compat = CompatReports(1, 2, 0)),
            pkg("tested", testedOn = listOf(thor)),
            pkg("works", compat = CompatReports(5, 1, 0)),
        )
        assertEquals(listOf("works", "tested", "unknown", "issues", "broken"), ids(ModHub.sort(entries, HubSort.COMPAT, thor)))
    }

    @Test
    fun switchableIsCodeModsElseSettings() {
        val layout = pkg("layout", kind = CommunityKind.UI_LAYOUT)
        val settings = pkg("settings", kind = CommunityKind.DUO_SETTINGS)
        assertEquals(listOf("slime-rancher"), ids(ModHub.switchable(listOf(HubEntry.Bundled(bundled), layout, settings))))
        assertEquals(listOf("settings"), ids(ModHub.switchable(listOf(layout, settings))))
        assertTrue(ModHub.switchable(listOf(layout)).isEmpty())
    }

    @Test
    fun reportUrlIsPrefilledOrFallsBackToTheDiscussion() {
        val withForm = pkg("a.b", reportUrl = "https://github.com/o/r/issues/new?template=compat-report.yml").pkg
        val url = ModHub.reportUrl(withForm, thor, CompatVerdict.ISSUES, "steam-433340")!!
        val query = url.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("compat-report.yml", query["template"])
        assertEquals("a.b", query["package"])
        assertEquals("1.2.0", query["version"])
        assertEquals(thor, query["device"])
        assertEquals("issues", query["result"])
        assertEquals("https://github.com/o/r/discussions/1", ModHub.reportUrl(pkg("c.d").pkg, thor, CompatVerdict.WORKS, "k"))
    }
}
