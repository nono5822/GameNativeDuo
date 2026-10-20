package app.gamenative.duo.mods

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.ui.graphics.vector.ImageVector
import app.gamenative.duo.split.SplitLayout

/**
 * Games with a bundled dual-screen mod. Each mod ships in the app's assets (`assets/duo/mods/<id>/`) and is
 * applied per container by [DuoModInstaller]: files are copied into the game folder and the container's Wine
 * prefix, the container's screen size and env vars are set, and GameNative Duo's screen split is switched on.
 *
 * To add a game: put its files under `assets/duo/mods/<id>/` and add an entry here.
 */
data class DuoGameMod(
    val id: String,
    /** Container id / app id, e.g. "STEAM_2379780". */
    val appId: String,
    val title: String,
    /**
     * Asset path (under `assets/`) -> path relative to the game's install folder. An asset folder is copied
     * with everything in it.
     */
    val gameFiles: Map<String, String>,
    /** Asset path (file or folder, under `assets/`) -> path relative to the Wine prefix's `drive_c`. */
    val prefixFiles: Map<String, String> = emptyMap(),
    /** Copies inside the game folder, source -> target (e.g. a loader needing its own copy of a game file). */
    val gameFileCopies: Map<String, String> = emptyMap(),
    /** Wine DLL overrides to add to `WINEDLLOVERRIDES`, e.g. "winmm=n,b". */
    val dllOverrides: List<String> = emptyList(),
    /** Executable to launch instead of the game's own, relative to the game folder (e.g. a mod loader). */
    val launchExecutable: String? = null,
    /** How the screen split cuts the tall X screen for this game's mod. */
    val splitLayout: SplitLayout = SplitLayout.FILL,
    /**
     * Split resolution the mod is tuned for (DuoContainerSettings.splitScale), set when Dual screen is turned on and
     * restored when it is turned off; null keeps the container's own. The user can still change it afterwards.
     */
    val splitScale: Float? = null,
)

object DuoModCatalog {
    private const val BALATRO_APPDATA = "users/xuser/AppData/Roaming/Balatro"
    private const val SHARED = "duo/mods/shared"

    val mods: List<DuoGameMod> = listOf(
        DuoGameMod(
            id = "balatro",
            appId = "STEAM_2379780",
            title = "Balatro",
            // Lovely (https://github.com/ethangreen-dev/lovely-injector, MIT) loads the Lua mod through winmm.dll.
            gameFiles = mapOf(
                "duo/mods/balatro/lovely/winmm.dll" to "winmm.dll",
            ),
            // Our Lua mod (hand, shop, packs and menus on the bottom screen, the table on top), one file per part.
            prefixFiles = mapOf(
                "duo/mods/balatro/GameNativeDuo-split" to "$BALATRO_APPDATA/Mods/GameNativeDuo-split",
                "duo/mods/balatro/lovely/LICENSE.md" to "$BALATRO_APPDATA/Mods/GameNativeDuo-split/LICENSE-lovely.md",
            ),
            dllOverrides = listOf("winmm=n,b"),
            // Each part at its screen's shape, so the bottom screen holds a vanilla-sized hand and shop at the top
            // screen's tile size. 75%: on the Thor the frame's present cost grows with its size (12.8 ms at 1920x2160,
            // 7.8 ms at 75%), which kept a late-game table at 43 fps; 75% holds about 57-60.
            splitLayout = SplitLayout.NATIVE,
            splitScale = 0.75f,
        ),
        DuoGameMod(
            id = "stardew",
            appId = "STEAM_413150",
            title = "Stardew Valley",
            // SMAPI 4.5.2 (https://github.com/Pathoschild/SMAPI, LGPL-3.0, unmodified; licence and notice alongside).
            gameFiles = mapOf(
                "duo/mods/stardew/smapi" to "",
                "duo/mods/stardew/SMAPI-LICENSE.txt" to "smapi-internal/LICENSE.txt",
                "duo/mods/stardew/SMAPI-NOTICE.txt" to "smapi-internal/NOTICE-GameNativeDuo.txt",
                // Our SMAPI mod (source: duo/mods-src/stardew): world on top, HUD and menus on the bottom screen.
                "duo/mods/stardew/mod" to "Mods/GameNativeDuo-split",
            ),
            // SMAPI's installer gives it its own copy of the game's dependency manifest.
            gameFileCopies = mapOf("Stardew Valley.deps.json" to "StardewModdingAPI.deps.json"),
            launchExecutable = "StardewModdingAPI.exe",
            splitLayout = SplitLayout.NATIVE,
        ),
        unityGame(id = "slime-rancher", appId = "STEAM_433340", title = "Slime Rancher"),
        unityGame(id = "subnautica", appId = "STEAM_264710", title = "Subnautica"),
        // Hollow Knight, Silksong and Risk of Rain 2 use the same plugin but are installed from the community list
        // (experimental mod packages reusing these shared assets).
    )

    /**
     * A Unity game with the Mono backend and uGUI: BepInEx 5.4.23.5 (https://github.com/BepInEx/BepInEx, MIT,
     * unmodified; licence and notice alongside) loads through winhttp.dll, plus our generic plugin (source:
     * duo/mods-src/unity). Both are shared by every Unity game (`assets/duo/mods/shared/`); a game's own folder only
     * holds its optional UI layout preset (`ui-layout.json`).
     */
    private fun unityGame(id: String, appId: String, title: String) = DuoGameMod(
        id = id,
        appId = appId,
        title = title,
        gameFiles = mapOf(
            "$SHARED/bepinex" to "",
            "$SHARED/BepInEx-LICENSE.txt" to "BepInEx/LICENSE-BepInEx.txt",
            "$SHARED/BepInEx-NOTICE.txt" to "BepInEx/NOTICE-GameNativeDuo.txt",
            "$SHARED/unity-split" to "BepInEx/plugins/GameNativeDuo-split",
        ),
        dllOverrides = listOf("winhttp=n,b"),
        splitLayout = SplitLayout.NATIVE,
    )

    private val byAppId = mods.associateBy { it.appId }

    /** Icon of the home screen's "Dual screen" tab. */
    @JvmStatic
    val tabIcon: ImageVector get() = Icons.Outlined.Splitscreen

    @JvmStatic
    fun forApp(appId: String?): DuoGameMod? = appId?.let { byAppId[it] }

    @JvmStatic
    fun hasMod(appId: String?): Boolean = forApp(appId) != null
}
