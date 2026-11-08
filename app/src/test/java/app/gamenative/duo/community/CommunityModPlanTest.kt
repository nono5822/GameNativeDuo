package app.gamenative.duo.community

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommunityModPlanTest {
    private fun file(path: String) = CommunityFile(path, path.hashCode().toString(), "https://x/$path", 10)

    private fun pkg(install: CommunityInstall, vararg paths: String) = CommunityPackage(
        id = "a.b", kind = CommunityKind.MOD, version = "1.0.0", title = "t", description = "d", authors = listOf("a"),
        license = "MIT", adapter = null, testedOn = emptyList(), gameBuilds = emptyList(), tags = emptyList(),
        files = paths.map(::file), screenshots = emptyList(), updated = "", install = install,
    )

    private fun install(
        game: Map<String, String> = emptyMap(),
        prefix: Map<String, String> = emptyMap(),
        shared: Map<String, String> = emptyMap(),
        overrides: List<String> = emptyList(),
    ) = CommunityInstall(game, prefix, shared, overrides, null, null)

    @Test
    fun foldersFilesAndRoots() {
        val plan = CommunityModInstaller.plan(
            pkg(
                install(
                    game = mapOf(
                        "plugin/" to "BepInEx/plugins/Duo",
                        "loader.dll" to "",
                        "config.json" to "BepInEx/config/duo.json",
                        "extra.txt" to "docs/",
                    ),
                    prefix = mapOf("save/" to "users/xuser/AppData/Roaming/Game"),
                ),
                "plugin/Duo.dll", "plugin/sub/x.json", "loader.dll", "config.json", "extra.txt", "save/a.cfg",
            ),
        )
        assertEquals(
            setOf(
                "BepInEx/plugins/Duo/Duo.dll", "BepInEx/plugins/Duo/sub/x.json", "loader.dll", "BepInEx/config/duo.json", "docs/extra.txt",
            ),
            plan.gameFiles.keys,
        )
        assertEquals(setOf("users/xuser/AppData/Roaming/Game/a.cfg"), plan.prefixFiles.keys)
        assertEquals(60L, plan.downloadBytes)
    }

    @Test
    fun missingSourceOrUnknownSharedAssetIsRefused() {
        assertThrows(IOException::class.java) { CommunityModInstaller.plan(pkg(install(game = mapOf("nope/" to "")), "a.dll")) }
        assertThrows(IOException::class.java) {
            CommunityModInstaller.plan(pkg(install(game = mapOf("a.dll" to ""), shared = mapOf("../../secret" to "")), "a.dll"))
        }
        val shared = CommunityModInstaller.plan(pkg(install(game = mapOf("a.dll" to ""), shared = mapOf("bepinex" to "")), "a.dll"))
        assertEquals(mapOf("bepinex" to ""), shared.sharedAssets)
    }
}

class CommunityInstallRulesTest {
    private fun pkg(install: CommunityInstall, vararg paths: String) = CommunityPackage(
        id = "a.b", kind = CommunityKind.MOD, version = "1.0.0", title = "t", description = "d", authors = listOf("a"),
        license = "MIT", adapter = null, testedOn = emptyList(), gameBuilds = emptyList(), tags = emptyList(),
        files = paths.map { CommunityFile(it, "0", "https://x/$it", 1) }, screenshots = emptyList(), updated = "",
        install = install,
    )

    private fun plan(
        game: Map<String, String> = emptyMap(),
        prefix: Map<String, String> = emptyMap(),
        shared: Map<String, String> = emptyMap(),
        overrides: List<String> = emptyList(),
        vararg paths: String,
    ) =
        CommunityModInstaller.plan(pkg(CommunityInstall(game, prefix, shared, overrides, null, null), *paths))

    @Test
    fun prefixFilesStayInTheUsersFolders() {
        plan(prefix = mapOf("c.cfg" to "users/xuser/AppData/LocalLow/Game/c.cfg"), paths = arrayOf("c.cfg"))
        assertThrows(IOException::class.java) { plan(prefix = mapOf("x.dll" to "windows/system32/x.dll"), paths = arrayOf("x.dll")) }
        assertThrows(IOException::class.java) {
            plan(prefix = mapOf("l.json" to "users/xuser/AppData/Roaming/GameNativeDuo/layout.json"), paths = arrayOf("l.json"))
        }
        assertThrows(IOException::class.java) { plan(game = mapOf("a.txt" to "../other/a.txt"), paths = arrayOf("a.txt")) }
    }

    @Test
    fun dllOverridesNeedThatDllAtTheRoot() {
        plan(shared = mapOf("bepinex" to ""), overrides = listOf("winhttp=n,b"))
        plan(game = mapOf("loader/" to ""), overrides = listOf("version=n,b"), paths = arrayOf("loader/version.dll"))
        assertThrows(IOException::class.java) {
            plan(game = mapOf("loader/" to "sub/"), overrides = listOf("version=n,b"), paths = arrayOf("loader/version.dll"))
        }
        assertThrows(IOException::class.java) { plan(overrides = listOf("d3d11=n")) }
    }
}

class CommunityRatingTest {
    @Test
    fun wilsonPrefersManyGoodVotesOverOne() {
        val one = CommunityRating(1, 0, 0, "https://github.com/x")
        val many = CommunityRating(40, 2, 0, "https://github.com/x")
        val none = CommunityRating(0, 0, 0, "https://github.com/x")
        org.junit.Assert.assertTrue(many.score > one.score)
        org.junit.Assert.assertTrue(one.score > none.score)
        org.junit.Assert.assertEquals(0.0, none.score, 0.0)
    }

    @Test
    fun ratingLinksOnlyToGithub() {
        org.junit.Assert.assertNull(CommunityRating.fromJson(org.json.JSONObject().put("url", "https://evil.example/x")))
        val ok = CommunityRating.fromJson(org.json.JSONObject().put("url", "https://github.com/a/b/discussions/3").put("up", 2))
        org.junit.Assert.assertEquals(2, ok?.up)
    }
}
