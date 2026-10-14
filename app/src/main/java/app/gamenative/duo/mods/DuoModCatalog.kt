package app.gamenative.duo.mods

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.ui.graphics.vector.ImageVector

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
    /** Asset path (under `assets/`) -> path relative to the game's install folder. */
    val gameFiles: Map<String, String>,
    /** Asset path (under `assets/`) -> path relative to the Wine prefix's `drive_c`. */
    val prefixFiles: Map<String, String>,
    /** Wine DLL overrides to add to `WINEDLLOVERRIDES`, e.g. "winmm=n,b". */
    val dllOverrides: List<String>,
)

object DuoModCatalog {
    private const val BALATRO_APPDATA = "users/xuser/AppData/Roaming/Balatro"

    val mods: List<DuoGameMod> = listOf(
        DuoGameMod(
            id = "balatro",
            appId = "STEAM_2379780",
            title = "Balatro",
            // Lovely (https://github.com/ethangreen-dev/lovely-injector, MIT) loads the Lua mod through winmm.dll.
            gameFiles = mapOf(
                "duo/mods/balatro/lovely/winmm.dll" to "winmm.dll",
            ),
            prefixFiles = mapOf(
                "duo/mods/balatro/GameNativeDuo-split/lovely.toml" to "$BALATRO_APPDATA/Mods/GameNativeDuo-split/lovely.toml",
                "duo/mods/balatro/GameNativeDuo-split/duo_split.lua" to "$BALATRO_APPDATA/Mods/GameNativeDuo-split/duo_split.lua",
                "duo/mods/balatro/lovely/LICENSE.md" to "$BALATRO_APPDATA/Mods/GameNativeDuo-split/LICENSE-lovely.md",
            ),
            dllOverrides = listOf("winmm=n,b"),
        ),
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
