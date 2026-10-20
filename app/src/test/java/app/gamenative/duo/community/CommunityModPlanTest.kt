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

    private fun install(game: Map<String, String> = emptyMap(), prefix: Map<String, String> = emptyMap(), shared: Map<String, String> = emptyMap()) =
        CommunityInstall(game, prefix, shared, listOf("winhttp=n,b"), null, null)

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
            setOf("BepInEx/plugins/Duo/Duo.dll", "BepInEx/plugins/Duo/sub/x.json", "loader.dll", "BepInEx/config/duo.json", "docs/extra.txt"),
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
        assertEquals(mapOf("bepinex" to ""), CommunityModInstaller.plan(pkg(install(game = mapOf("a.dll" to ""), shared = mapOf("bepinex" to "")), "a.dll")).sharedAssets)
    }
}
