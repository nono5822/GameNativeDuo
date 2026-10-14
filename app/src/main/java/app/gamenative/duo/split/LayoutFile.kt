package app.gamenative.duo.split

import app.gamenative.duo.DualScreenHost
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

/**
 * Tells in-game mods where the two screens are: `%AppData%\GameNativeDuo\layout.json` in the container's Wine
 * prefix, with the X screen size and the top and bottom rectangles (see [ScreenSplit.toJson]). Mods use it only
 * when the game window has exactly that size, so a stale file is harmless.
 */
object LayoutFile {
    fun file(container: Container) = File(container.rootDir, ".wine/drive_c/users/xuser/AppData/Roaming/GameNativeDuo/layout.json")

    fun write(container: Container, split: ScreenSplit) {
        try {
            file(container).apply { parentFile?.mkdirs() }.writeText(split.toJson().toString(2))
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to write layout.json")
        }
    }

    fun delete(container: Container) {
        try {
            file(container).delete()
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to delete layout.json")
        }
    }
}
